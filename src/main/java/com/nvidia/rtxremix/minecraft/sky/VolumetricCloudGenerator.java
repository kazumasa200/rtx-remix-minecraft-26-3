/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.sky;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GL44;
import org.lwjgl.opengl.GL45C;

/**
 * Ray marches the Unbound clouds into a panorama of the upper sky with a compute shader and
 * reads it back as premultiplied half-float radiance for the dome mesh to show. Radiance is
 * stored as it is, so the dome's material never has to change with the light.
 *
 * <p>The panorama spans every azimuth. Its upper {@link #UPPER_ROWS} rows run linearly from
 * the zenith down to {@link #MIN_ELEVATION_DEGREES}, and the rest of the rows on to the nadir,
 * for the clouds below a camera above them, past where the land ends: spaced by the square of
 * the distance from the split, as fine as the upper rows beside the horizon, where those
 * clouds show, and coarse toward the nadir, which the land covers. Each
 * dispatch refreshes half its texels and folds them into a running average, so a dispatch
 * costs half a march and the jitter that hides the march's steps averages out over a few
 * updates.
 *
 * <p>The land hides the sky, so clouds between the camera and the land are marched again,
 * per pixel, by the lens ({@link CloudField}), from the same density field and lighting.
 *
 * <p>Render thread only: it owns GL programs, textures and readback buffers, and every
 * entry point assumes the GL context is current.
 */
final class VolumetricCloudGenerator {

    static final int WIDTH = 2048;
    static final int HEIGHT = 576;
    static final int UPPER_ROWS = 512;
    static final float MIN_ELEVATION_DEGREES = -4.0f;
    static final int OUTPUT_BYTES = WIDTH * HEIGHT * 4 * Short.BYTES;
    /** The panorama plus one row below it, whose first texel holds the extinction at the camera. */
    private static final int READBACK_BYTES = OUTPUT_BYTES + WIDTH * 4 * Short.BYTES;
    private static final int NOISE_SIZE = 128;
    private static final int PASS_NOISE = 0;
    private static final int PASS_MARCH = 1;
    private static final int READBACK_RING_SIZE = 3;
    /** The full mip chain of a shown copy, down to one texel across. */
    private static final int SHOWN_LEVELS = Integer.SIZE - Integer.numberOfLeadingZeros(WIDTH);
    // Outside the twelve units whose bindings the GL backend caches, and apart from the
    // atmosphere's units 14 and 15.
    private static final int TEXTURE_UNIT = 13;
    private static final int CIRRUS_TEXTURE_UNIT = 12;
    private static final int PARAMETER_BINDING = 1;
    private static final String SHADER_RESOURCE =
            "/assets/rtx-remix-renderer/shaders/volumetric_clouds.comp";

    /** One dispatch's inputs, filled by the caller; see the shader's parameter block. */
    static final class Frame {
        float cameraX;
        float cameraZ;
        float cameraHeight;
        float frameIndex;
        final float[] lightDirection = new float[3];
        final float[] lightIrradiance = new float[3];
        final float[] ambientTop = new float[3];
        final float[] ambientBottom = new float[3];
        /** Coverage of the small cumulus, the large cumulus and the altostratus. */
        final float[] coverage = new float[3];
        float density;
        float base;
        float thickness;
        float featureScale;
        float historyWeight;
        float parity;
        float hazeDistance;
        float brightness;
        float detail;
        /** Radians the cirrus layer has turned about the vertical, as the sky layers turn it. */
        float cirrusAngle;
        /** How much of the cirrus layer shows: its brightness, with the weather taken out. */
        float cirrusStrength;
        /**
         * A lightning flash in the clouds' frame and the irradiance it gives a cloud right at
         * it, zero with none; see cloud_field.glsl's Flash.
         */
        final float[] flash = new float[4];
        final CloudShapes shapes = new CloudShapes();
    }

    private final ByteBuffer parameters = BufferUtils.createByteBuffer(9 * 4 * Float.BYTES);
    private ReadbackRing panoramaReadback;
    private float cameraExtinction;

    private int program;
    private int passLocation;
    private int noiseTexture;
    private int historyTexture;
    private int outputTexture;
    /** Per readback slot, the panorama that readback carries, for the lens to read what the dome shows. */
    private final int[] shownTextures = new int[READBACK_RING_SIZE];
    private int cirrusTexture;
    private int parameterBuffer;

    VolumetricCloudGenerator() {
        if (!GL.getCapabilities().OpenGL44) {
            throw new IllegalStateException("Volumetric clouds require OpenGL 4.4");
        }
        program = createProgram(loadShaderSource());
        passLocation = GL20.glGetUniformLocation(program, "Pass");
        int noiseLocation = GL20.glGetUniformLocation(program, "Noise");
        int cirrusLocation = GL20.glGetUniformLocation(program, "Cirrus");
        if (passLocation < 0 || noiseLocation < 0 || cirrusLocation < 0) {
            throw new IllegalStateException("Volumetric cloud shader is missing a uniform");
        }

        noiseTexture = createNoiseTexture();
        historyTexture = createTexture(WIDTH, HEIGHT, GL30.GL_RGBA16F, 1);
        outputTexture = createTexture(WIDTH, HEIGHT + 1, GL30.GL_RGBA16F, 1);
        for (int slot = 0; slot < READBACK_RING_SIZE; slot++) {
            shownTextures[slot] = createTexture(WIDTH, HEIGHT, GL30.GL_RGBA16F, SHOWN_LEVELS);
        }
        cirrusTexture = createCirrusTexture();
        uploadCirrus(new byte[4], 1);
        parameterBuffer = GL15.glGenBuffers();
        GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, parameterBuffer);
        GL15.glBufferData(GL31.GL_UNIFORM_BUFFER, parameters.capacity(), GL15.GL_DYNAMIC_DRAW);
        GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, 0);
        panoramaReadback = new ReadbackRing(READBACK_BYTES);

        int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        try {
            GL20.glUseProgram(program);
            GL20.glUniform1i(noiseLocation, TEXTURE_UNIT);
            GL20.glUniform1i(cirrusLocation, CIRRUS_TEXTURE_UNIT);
            GL42.glBindImageTexture(0, noiseTexture, 0, true, 0, GL15.GL_WRITE_ONLY, GL11.GL_RGBA8);
            GL20.glUniform1i(passLocation, PASS_NOISE);
            GL43.glDispatchCompute(NOISE_SIZE / 8, NOISE_SIZE / 8, NOISE_SIZE);
            GL42.glMemoryBarrier(GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
        } finally {
            GL42.glBindImageTexture(0, 0, 0, false, 0, GL15.GL_READ_WRITE, GL11.GL_RGBA8);
            GL20.glUseProgram(previousProgram);
        }
        checkGlError("initialize volumetric cloud generator");
    }

    /**
     * Marches the clouds for {@code frame} and queues their readback, unless every readback
     * buffer is still in flight, in which case the dispatch is skipped.
     *
     * @return whether the dispatch ran
     */
    boolean dispatch(Frame frame) {
        if (panoramaReadback.full()) {
            return false;
        }
        fillParameters(frame);
        // The GL backend binds its program for every draw but caches the active texture unit,
        // so both are put back, as the atmosphere generator does.
        int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int previousActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        try {
            GL20.glUseProgram(program);
            CloudField.setShapeUniforms(program, frame.shapes);
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + CIRRUS_TEXTURE_UNIT);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, cirrusTexture);
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + TEXTURE_UNIT);
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, noiseTexture);
            GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, parameterBuffer);
            GL15.glBufferSubData(GL31.GL_UNIFORM_BUFFER, 0L, parameters);
            GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, PARAMETER_BINDING, parameterBuffer);
            GL42.glBindImageTexture(1, historyTexture, 0, false, 0, GL15.GL_READ_WRITE, GL30.GL_RGBA16F);
            GL42.glBindImageTexture(2, outputTexture, 0, false, 0, GL15.GL_WRITE_ONLY, GL30.GL_RGBA16F);
            GL20.glUniform1i(passLocation, PASS_MARCH);
            GL43.glDispatchCompute(WIDTH / 8, HEIGHT / 8 + 1, 1);
            // The lens samples the panorama this frame too.
            GL42.glMemoryBarrier(GL42.GL_TEXTURE_UPDATE_BARRIER_BIT | GL42.GL_PIXEL_BUFFER_BARRIER_BIT
                    | GL42.GL_TEXTURE_FETCH_BARRIER_BIT);

            int shown = shownTextures[panoramaReadback.nextSlot()];
            GL43.glCopyImageSubData(outputTexture, GL11.GL_TEXTURE_2D, 0, 0, 0, 0,
                    shown, GL11.GL_TEXTURE_2D, 0, 0, 0, 0, WIDTH, HEIGHT, 1);
            // The runtime mips the dome's texture too.
            GL45C.glGenerateTextureMipmap(shown);
            panoramaReadback.queue(outputTexture);
        } finally {
            GL42.glBindImageTexture(1, 0, 0, false, 0, GL15.GL_READ_WRITE, GL30.GL_RGBA16F);
            GL42.glBindImageTexture(2, 0, 0, false, 0, GL15.GL_READ_WRITE, GL11.GL_RGBA8);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            GL13.glActiveTexture(previousActiveTexture);
            GL20.glUseProgram(previousProgram);
        }
        return true;
    }

    /**
     * Returns the oldest readback whose dispatch has finished, positioned over its
     * {@link #OUTPUT_BYTES}, or null when none has. The buffer stays valid until the next
     * dispatch reuses it.
     */
    ByteBuffer consumeCompletedReadback() {
        ByteBuffer mapped = panoramaReadback.consume();
        if (mapped == null) {
            return null;
        }
        cameraExtinction = Math.max(0.0f, Float.float16ToFloat(mapped.getShort(OUTPUT_BYTES)));
        mapped.limit(OUTPUT_BYTES);
        return mapped;
    }

    /**
     * The elevation in degrees a panorama row coordinate {@code v}, 0 at the top and 1 at the
     * bottom, looks along; see the shader's panoramaDirection.
     */
    static double elevationDegrees(double v) {
        double split = UPPER_ROWS / (double) HEIGHT;
        double lower = (v - split) / (1.0 - split);
        return v <= split
                ? 90.0 + (MIN_ELEVATION_DEGREES - 90.0) * (v / split)
                : MIN_ELEVATION_DEGREES + (-90.0 - MIN_ELEVATION_DEGREES) * lower * lower;
    }

    /** The panorama row coordinate an elevation in degrees falls on; the inverse of {@link #elevationDegrees}. */
    static double rowCoordinate(double elevationDegrees) {
        double split = UPPER_ROWS / (double) HEIGHT;
        return elevationDegrees >= MIN_ELEVATION_DEGREES
                ? (90.0 - elevationDegrees) / (90.0 - MIN_ELEVATION_DEGREES) * split
                : split + Math.sqrt((MIN_ELEVATION_DEGREES - elevationDegrees) / (MIN_ELEVATION_DEGREES + 90.0))
                        * (1.0 - split);
    }

    /** Blocks⁻¹ of cloud extinction where the camera stood for the last readback consumed. */
    float cameraExtinction() {
        return cameraExtinction;
    }

    /** The 3D noise the density field reads; see {@link CloudField}. */
    int noiseTexture() {
        return noiseTexture;
    }

    /**
     * The 3D noise as bytes, RGBA8, {@code NOISE_SIZE} cubed with x fastest: what the runtime's clouds read
     * (RuntimePatchLayer.upload), so they march the very noise the GL clouds do. A blocking read; once.
     */
    ByteBuffer noisePixels() {
        GL42.glMemoryBarrier(GL42.GL_TEXTURE_UPDATE_BARRIER_BIT);
        ByteBuffer pixels = BufferUtils.createByteBuffer(NOISE_SIZE * NOISE_SIZE * NOISE_SIZE * 4);
        int previous = GL11.glGetInteger(GL12.GL_TEXTURE_BINDING_3D);
        try (PixelStoreState ignored = PixelStoreState.tightPack()) {
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, noiseTexture);
            GL11.glGetTexImage(GL12.GL_TEXTURE_3D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        } finally {
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, previous);
        }
        checkGlError("read the volumetric clouds' noise");
        return pixels;
    }

    /**
     * The panorama of the last readback consumed, which is what the dome shows once it is
     * uploaded: premultiplied radiance and opacity, its rows as {@link #rowCoordinate} lays them
     * out; 0 before the first.
     */
    int shownPanoramaTexture() {
        int slot = panoramaReadback.lastSlot();
        return slot < 0 ? 0 : shownTextures[slot];
    }

    /** The cirrus layer's density; see {@link #uploadCirrus}. */
    int cirrusTexture() {
        return cirrusTexture;
    }

    void destroy() {
        if (panoramaReadback != null) {
            panoramaReadback.destroy();
        }
        for (int texture : new int[] {noiseTexture, historyTexture, outputTexture, cirrusTexture}) {
            if (texture != 0) {
                GL11.glDeleteTextures(texture);
            }
        }
        noiseTexture = historyTexture = outputTexture = cirrusTexture = 0;
        GL11.glDeleteTextures(shownTextures);
        Arrays.fill(shownTextures, 0);
        if (parameterBuffer != 0) {
            GL15.glDeleteBuffers(parameterBuffer);
            parameterBuffer = 0;
        }
        if (program != 0) {
            GL20.glDeleteProgram(program);
            program = 0;
        }
    }

    private void fillParameters(Frame frame) {
        parameters.clear();
        parameters.putFloat(frame.cameraX).putFloat(frame.cameraZ).putFloat(frame.cameraHeight).putFloat(frame.frameIndex);
        putVector(frame.lightDirection, 0.0f);
        putVector(frame.lightIrradiance, 0.0f);
        putVector(frame.ambientTop, frame.coverage[0]);
        putVector(frame.ambientBottom, frame.density);
        parameters.putFloat(frame.base).putFloat(frame.thickness).putFloat(frame.featureScale).putFloat(frame.historyWeight);
        parameters.putFloat(frame.coverage[1]).putFloat(frame.parity)
                .putFloat(frame.hazeDistance).putFloat(frame.brightness);
        parameters.putFloat(frame.detail).putFloat(frame.cirrusAngle).putFloat(frame.cirrusStrength)
                .putFloat(frame.coverage[2]);
        putVector(frame.flash, frame.flash[3]);
        parameters.flip();
    }

    private void putVector(float[] vector, float w) {
        parameters.putFloat(vector[0]).putFloat(vector[1]).putFloat(vector[2]).putFloat(w);
    }

    /**
     * Replaces the cirrus layer's density with the red channel of a {@code size}-square RGBA
     * image in the sky layers' polar layout.
     */
    void uploadCirrus(byte[] rgba, int size) {
        ByteBuffer red = BufferUtils.createByteBuffer(size * size);
        for (int i = 0; i < size * size; i++) {
            red.put(rgba[i * 4]);
        }
        red.flip();
        int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try (PixelStoreState ignored = PixelStoreState.tightUnpack()) {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, cirrusTexture);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R8, size, size, 0, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, red);
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
        }
    }

    private static int createCirrusTexture() {
        int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int texture = GL11.glGenTextures();
        try {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
        }
        return texture;
    }

    /** Persistently mapped buffers a texture is read back into without stalling, oldest consumed first. */
    private static final class ReadbackRing {
        private final int[] buffers = new int[READBACK_RING_SIZE];
        private final ByteBuffer[] mapped = new ByteBuffer[READBACK_RING_SIZE];
        private final long[] fences = new long[READBACK_RING_SIZE];
        private int writeIndex;
        private int readIndex;
        private int pending;
        private int lastSlot = -1;

        ReadbackRing(int bytes) {
            int flags = GL30.GL_MAP_READ_BIT | GL44.GL_MAP_PERSISTENT_BIT | GL44.GL_MAP_COHERENT_BIT;
            for (int index = 0; index < READBACK_RING_SIZE; index++) {
                buffers[index] = GL15.glGenBuffers();
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, buffers[index]);
                GL44.glBufferStorage(GL21.GL_PIXEL_PACK_BUFFER, bytes, flags);
                ByteBuffer buffer = GL30.glMapBufferRange(GL21.GL_PIXEL_PACK_BUFFER, 0L, bytes, flags, null);
                if (buffer == null) {
                    throw new IllegalStateException("Failed to persistently map the cloud readback");
                }
                mapped[index] = buffer;
            }
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
        }

        boolean full() {
            return pending == READBACK_RING_SIZE;
        }

        /** The slot the next {@link #queue} fills. */
        int nextSlot() {
            return writeIndex;
        }

        /** The slot the last {@link #consume} returned, or -1 before the first. */
        int lastSlot() {
            return lastSlot;
        }

        /** Reads {@code texture} back into the next buffer; leaves the pack buffer bound for the caller to clear. */
        void queue(int texture) {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, buffers[writeIndex]);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            try (PixelStoreState ignored = PixelStoreState.tightPack()) {
                GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL30.GL_HALF_FLOAT, 0L);
            }
            fences[writeIndex] = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            writeIndex = (writeIndex + 1) % READBACK_RING_SIZE;
            pending++;
        }

        /** The oldest readback whose copy has finished, positioned at its start, or null. */
        ByteBuffer consume() {
            if (pending == 0) {
                return null;
            }
            int status = GL32.glClientWaitSync(fences[readIndex], 0, 0L);
            if (status != GL32.GL_ALREADY_SIGNALED && status != GL32.GL_CONDITION_SATISFIED) {
                return null;
            }
            GL32.glDeleteSync(fences[readIndex]);
            fences[readIndex] = 0L;
            ByteBuffer buffer = mapped[readIndex];
            buffer.clear();
            lastSlot = readIndex;
            readIndex = (readIndex + 1) % READBACK_RING_SIZE;
            pending--;
            return buffer;
        }

        void destroy() {
            for (int index = 0; index < READBACK_RING_SIZE; index++) {
                if (fences[index] != 0L) {
                    GL32.glDeleteSync(fences[index]);
                    fences[index] = 0L;
                }
                if (buffers[index] != 0) {
                    GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, buffers[index]);
                    GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
                    GL15.glDeleteBuffers(buffers[index]);
                    buffers[index] = 0;
                    mapped[index] = null;
                }
            }
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            pending = 0;
        }
    }

    private static int createNoiseTexture() {
        int previous = GL11.glGetInteger(GL12.GL_TEXTURE_BINDING_3D);
        int texture = GL11.glGenTextures();
        try {
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, texture);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
            GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_R, GL11.GL_REPEAT);
            GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL11.GL_RGBA8, NOISE_SIZE, NOISE_SIZE, NOISE_SIZE,
                    0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        } finally {
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, previous);
        }
        return texture;
    }

    private static int createTexture(int width, int height, int internalFormat, int levels) {
        int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int texture = GL11.glGenTextures();
        try {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL42.glTexStorage2D(GL11.GL_TEXTURE_2D, levels, internalFormat, width, height);
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
        }
        return texture;
    }

    private static int createProgram(String source) {
        int shader = GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(shader);
            GL20.glDeleteShader(shader);
            throw new IllegalStateException("Failed to compile volumetric cloud shader: " + log);
        }
        int linked = GL20.glCreateProgram();
        try {
            GL20.glAttachShader(linked, shader);
            GL20.glLinkProgram(linked);
            if (GL20.glGetProgrami(linked, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                String log = GL20.glGetProgramInfoLog(linked);
                GL20.glDeleteProgram(linked);
                throw new IllegalStateException("Failed to link volumetric cloud shader: " + log);
            }
            return linked;
        } finally {
            GL20.glDeleteShader(shader);
        }
    }

    private static String loadShaderSource() {
        try (InputStream stream = VolumetricCloudGenerator.class.getResourceAsStream(SHADER_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("Missing volumetric cloud shader " + SHADER_RESOURCE);
            }
            String source = CloudField.expand(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            int versionEnd = source.indexOf('\n', source.indexOf("#version")) + 1;
            return source.substring(0, versionEnd) + CloudField.defines() + source.substring(versionEnd);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read volumetric cloud shader " + SHADER_RESOURCE, e);
        }
    }

    private static void checkGlError(String operation) {
        int error = GL11.glGetError();
        if (error != GL11.GL_NO_ERROR) {
            throw new IllegalStateException(operation + " failed with OpenGL error 0x" + Integer.toHexString(error));
        }
    }
}
