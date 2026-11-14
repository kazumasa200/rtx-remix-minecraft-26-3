/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.weather;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import com.mojang.renderpearl.backend.opengl.GlTextureView;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * Draws rain and snow over the composited Remix colour image, occluded by the
 * path-traced scene through the interop depth texture.
 *
 * <p>The Remix image covers vanilla's weather, so the overlay redraws it: vanilla's
 * {@code WeatherEffectRenderer.prepare} builds one camera-relative vertex buffer, rain
 * columns first and snow columns after, which {@link #capture} copies, and
 * {@link #render} draws it after the composite. Rain refracts and reflects the Remix
 * image as 1.21.1 did; snow is shaded like vanilla's particle pipeline.
 *
 * <p>Render thread only. The draw uses its own GL program, vertex array, framebuffer and
 * samplers. State the OpenGL backend caches (framebuffer bindings, the active texture
 * unit and its bindings, depth, blend, cull, scissor and colour mask) is set through
 * {@link GlStateManager} so the cache stays true; the backend sets those per pipeline
 * before its own draws. Program, vertex array, sampler and viewport bindings, which it
 * does not cache, are put back.
 */
public final class RemixWeatherRenderer {
    // The planes the Remix camera renders with, so the overlay's depth matches its depth output.
    private static final float NEAR_PLANE = 0.05f;
    private static final float FAR_PLANE = 1000.0f;
    private static final Identifier RAIN_TEXTURE =
            Identifier.withDefaultNamespace("textures/environment/rain.png");
    private static final Identifier SNOW_TEXTURE =
            Identifier.withDefaultNamespace("textures/environment/snow.png");

    private static final int UNIT_WEATHER = 0;
    private static final int UNIT_REMIX_COLOR = 1;
    private static final int UNIT_LIGHTMAP = 2;
    private static final int UNIT_REMIX_DEPTH = 3;
    private static final int UNIT_COUNT = 4;
    private static final int INDICES_PER_QUAD = 6;
    private static final int VERTICES_PER_QUAD = 4;

    private static final String VERTEX_SHADER = """
            #version 330 core
            layout(location = 0) in vec3 Position;
            layout(location = 1) in vec2 UV0;
            layout(location = 2) in vec4 Color;
            layout(location = 3) in ivec2 UV2;

            uniform mat4 ModelViewMat;
            uniform mat4 ProjMat;
            uniform sampler2D Lightmap;
            uniform bool ApplyLightmap;

            out vec2 texCoord;
            out vec4 vertexColor;
            out vec3 viewPosition;

            void main() {
                viewPosition = (ModelViewMat * vec4(Position, 1.0)).xyz;
                gl_Position = ProjMat * vec4(viewPosition, 1.0);
                texCoord = UV0;
                vertexColor = Color;
                if (ApplyLightmap) {
                    // Vanilla's sample_lightmap.
                    vertexColor *= texture(Lightmap,
                            clamp(UV2 / 256.0 + 0.5 / 16.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0)));
                }
            }
            """;

    // Both fragment shaders start by discarding what the path-traced scene hides: the
    // projection matches the Remix camera's, so window depth compares directly.
    private static final String SCENE_DEPTH_TEST = """
            uniform sampler2D RemixDepth;
            uniform vec2 ViewportSize;

            vec2 screenUv() {
                return vec2(gl_FragCoord.x / ViewportSize.x, 1.0 - gl_FragCoord.y / ViewportSize.y);
            }

            bool hiddenByScene() {
                return gl_FragCoord.z > texture(RemixDepth, screenUv()).r;
            }
            """;

    private static final String SNOW_FRAGMENT_SHADER = """
            #version 330 core
            uniform sampler2D Sampler0;
            in vec2 texCoord;
            in vec4 vertexColor;
            in vec3 viewPosition;
            out vec4 fragColor;
            """ + SCENE_DEPTH_TEST + """

            void main() {
                if (hiddenByScene()) {
                    discard;
                }
                vec4 color = texture(Sampler0, texCoord) * vertexColor;
                if (color.a < 0.1) {
                    discard;
                }
                fragColor = color;
            }
            """;

    private static final String RAIN_FRAGMENT_SHADER = """
            #version 330 core
            uniform sampler2D Sampler0;
            uniform sampler2D Sampler1;
            uniform mat4 ProjMat;

            in vec2 texCoord;
            in vec4 vertexColor;
            in vec3 viewPosition;

            out vec4 fragColor;
            """ + SCENE_DEPTH_TEST + """

            vec2 projectedUv(vec3 direction) {
                vec4 clip = ProjMat * vec4(direction, 0.0);
                float safeW = clip.w < 0.0
                    ? -max(abs(clip.w), 0.0001)
                    : max(abs(clip.w), 0.0001);
                return clip.xy / safeW * 0.5 + 0.5;
            }

            // The image behind the rain, a copy of the main target as the lens left it, read in
            // the runtime's top-to-bottom screen coordinates the rest of this shader uses.
            vec3 background(vec2 uv) {
                return texture(Sampler1, vec2(uv.x, 1.0 - uv.y)).rgb;
            }

            float screenFade(vec2 uv) {
                vec2 edgeDistance = min(uv, vec2(1.0) - uv);
                return smoothstep(0.0, 0.04, min(edgeDistance.x, edgeDistance.y));
            }

            void main() {
                vec4 rainTexture = texture(Sampler0, texCoord);
                float coverage = rainTexture.a * vertexColor.a;
                if (coverage <= 0.01 || hiddenByScene()) {
                    discard;
                }

                float profileX = clamp(texCoord.x, 0.0, 1.0) * 2.0 - 1.0;
                float profileZ = sqrt(max(0.0, 1.0 - profileX * profileX));

                vec3 positionDx = dFdx(viewPosition);
                vec3 positionDy = dFdy(viewPosition);
                vec2 uvDx = dFdx(texCoord);
                vec2 uvDy = dFdy(texCoord);
                float determinant = uvDx.x * uvDy.y - uvDy.x * uvDx.y;
                float inverseDeterminant = sign(determinant)
                    / max(abs(determinant), 0.000001);
                vec3 tangent = normalize(
                    (positionDx * uvDy.y - positionDy * uvDx.y) * inverseDeterminant);
                vec3 bitangent = normalize(
                    (positionDy * uvDx.x - positionDx * uvDy.x) * inverseDeterminant);

                vec3 incident = normalize(viewPosition);
                vec3 sheetNormal = normalize(cross(tangent, bitangent));
                sheetNormal = faceforward(sheetNormal, incident, sheetNormal);

                vec2 rainTexel = 1.0 / vec2(textureSize(Sampler0, 0));
                float alphaBefore = texture(Sampler0, texCoord - vec2(0.0, rainTexel.y)).a;
                float alphaAfter = texture(Sampler0, texCoord + vec2(0.0, rainTexel.y)).a;
                float verticalSlope = clamp(
                    (alphaAfter - alphaBefore) * 2.0,
                    -0.45,
                    0.45);

                vec3 lensNormal = normalize(
                    tangent * profileX
                    + bitangent * verticalSlope
                    + sheetNormal * profileZ);
                lensNormal = faceforward(lensNormal, incident, lensNormal);

                const float AIR_TO_WATER_IOR = 1.0 / 1.333;
                vec3 refractedDirection = refract(incident, lensNormal, AIR_TO_WATER_IOR);

                vec2 framebufferSize = vec2(textureSize(Sampler1, 0));
                vec2 sourceUv = screenUv();
                vec2 angularOffset = projectedUv(refractedDirection)
                    - projectedUv(incident);
                angularOffset.y = -angularOffset.y;
                vec2 offsetPixels = clamp(
                    angularOffset * framebufferSize * 0.08,
                    vec2(-12.0),
                    vec2(12.0));
                offsetPixels *= rainTexture.a * vertexColor.a;
                vec2 refractedUv = clamp(
                    sourceUv + offsetPixels / framebufferSize,
                    vec2(0.0),
                    vec2(1.0));

                vec3 sourceColor = background(sourceUv);
                vec3 refractedColor = background(refractedUv);

                vec3 reflectedDirection = reflect(incident, lensNormal);
                vec2 reflectedUv = projectedUv(reflectedDirection);
                reflectedUv.y = 1.0 - reflectedUv.y;
                float reflectionValidity = step(reflectedDirection.z, -0.01)
                    * screenFade(reflectedUv);
                vec3 reflectedColor = background(clamp(reflectedUv, vec2(0.0), vec2(1.0)));
                reflectedColor = mix(sourceColor, reflectedColor, reflectionValidity);

                vec3 rainTint = vec3(0.76, 0.86, 1.0);
                vec3 transmissionColor = mix(sourceColor, refractedColor, vertexColor.a);
                transmissionColor = mix(
                    transmissionColor,
                    transmissionColor * rainTint,
                    0.24 * rainTexture.a * vertexColor.a);

                float normalDotView = clamp(dot(-incident, lensNormal), 0.0, 1.0);
                const float WATER_F0 = 0.02037;
                float fresnel = WATER_F0
                    + (1.0 - WATER_F0) * pow(1.0 - normalDotView, 5.0);
                float reflectionWeight = fresnel
                    * smoothstep(0.01, 0.30, rainTexture.a)
                    * vertexColor.a;
                vec3 color = mix(transmissionColor, reflectedColor, reflectionWeight);

                fragColor = vec4(color, 1.0);
            }
            """;

    private final Matrix4f projection = new Matrix4f();
    private final Matrix4f modelView = new Matrix4f();
    private float fovYDegrees;
    private float aspect;
    private boolean cameraSet;

    private int rainQuads;
    private int snowQuads;
    private int lastDrawCount;
    private int lastQuadCount;

    private Program rainProgram;
    private Program snowProgram;
    private int vertexBuffer;
    private int indexBuffer;
    private int indexBufferQuads;
    private int vertexArray;
    private int framebuffer;
    /**
     * A copy of the main target as the lens left it, which rain refracts and reflects: the
     * rain draws opaquely over the target, so reading the runtime's raw image instead would
     * take back the lens's haze, clouds and depth of field wherever it falls.
     */
    private int backgroundTexture;
    private int backgroundFramebuffer;
    private int backgroundWidth;
    private int backgroundHeight;
    private int backgroundFormat;
    private int weatherSampler;
    private int colorSampler;
    private int depthSampler;

    /** Drops the previous frame's weather; vanilla calls prepare every frame, columns or not. */
    public void beginCapture() {
        rainQuads = 0;
        snowQuads = 0;
    }

    /**
     * Copies vanilla's weather vertices for this frame: {@code rainColumns} quads of rain
     * followed by {@code snowColumns} quads of snow, in {@link DefaultVertexFormat#PARTICLE}
     * and relative to the camera. The buffer's position and limit are left as they were.
     */
    public void capture(ByteBuffer vertices, int rainColumns, int snowColumns) {
        int stride = DefaultVertexFormat.PARTICLE.getVertexSize();
        int quads = rainColumns + snowColumns;
        if (quads <= 0) {
            beginCapture();
            return;
        }
        if (vertices.remaining() != quads * VERTICES_PER_QUAD * stride) {
            throw new IllegalArgumentException("Weather vertices do not match " + quads + " columns");
        }
        ensureGeometryObjects();
        GL45C.glNamedBufferData(vertexBuffer, vertices, GL45C.GL_STREAM_DRAW);
        ensureIndexCapacity(quads);
        rainQuads = rainColumns;
        snowQuads = snowColumns;
    }

    public void updateCamera(Camera camera, float fovYDegrees, float aspect) {
        camera.getViewRotationMatrix(modelView);
        this.fovYDegrees = fovYDegrees;
        this.aspect = aspect;
        cameraSet = true;
    }

    /**
     * Draws the captured weather over the main render target, or over {@code target} when it is
     * not 0 (HDR's image, the main target's size), which the composite has just filled from
     * {@code finalColorTexture} and the lens has drawn over. A frame draws its weather once.
     */
    public void render(int finalColorTexture, int depthTexture, int target) {
        int rain = rainQuads;
        int snow = snowQuads;
        beginCapture();
        lastDrawCount = 0;
        lastQuadCount = 0;
        if (rain + snow == 0) {
            return;
        }
        if (finalColorTexture <= 0) {
            throw new IllegalStateException("Remix final-color texture is unavailable");
        }
        if (depthTexture <= 0) {
            throw new IllegalStateException("Remix depth texture is unavailable");
        }
        if (!cameraSet) {
            throw new IllegalStateException("Weather camera state was not initialized");
        }

        Minecraft client = Minecraft.getInstance();
        RenderTarget mainTarget = client.gameRenderer.mainRenderTarget();
        if (!(mainTarget.getColorTexture() instanceof GlTexture targetColor)) {
            return;
        }
        int rainTexture = glTexture(client.getTextureManager().getTexture(RAIN_TEXTURE));
        int snowTexture = glTexture(client.getTextureManager().getTexture(SNOW_TEXTURE));
        int lightmap = glTextureView(client.gameRenderer.lightmap());
        ensurePrograms();
        ensureSamplers();

        boolean zeroToOne = GL45C.glGetInteger(GL45C.GL_CLIP_DEPTH_MODE) == GL45C.GL_ZERO_TO_ONE;
        projection.setPerspective(
                (float) Math.toRadians(fovYDegrees), aspect, NEAR_PLANE, FAR_PLANE, zeroToOne);

        if (framebuffer == 0) {
            framebuffer = GL45C.glCreateFramebuffers();
            GL45C.glNamedFramebufferDrawBuffer(framebuffer, GL45C.GL_COLOR_ATTACHMENT0);
        }
        // Attached every frame: a resize recreates the target, and GL may hand a deleted
        // texture's name to its replacement.
        int targetTexture = target != 0 ? target : targetColor.glId();
        GL45C.glNamedFramebufferTexture(framebuffer, GL45C.GL_COLOR_ATTACHMENT0, targetTexture, 0);

        int previousProgram = GL45C.glGetInteger(GL45C.GL_CURRENT_PROGRAM);
        int previousVertexArray = GL45C.glGetInteger(GL45C.GL_VERTEX_ARRAY_BINDING);
        int previousDrawFramebuffer = GlStateManager.getFrameBuffer(GL45C.GL_DRAW_FRAMEBUFFER);
        int[] previousViewport = new int[4];
        int[] previousSamplers = new int[UNIT_COUNT];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer viewport = stack.mallocInt(4);
            GL45C.glGetIntegerv(GL45C.GL_VIEWPORT, viewport);
            viewport.get(previousViewport);
        }
        for (int unit = 0; unit < UNIT_COUNT; unit++) {
            GlStateManager._activeTexture(GL45C.GL_TEXTURE0 + unit);
            previousSamplers[unit] = GL45C.glGetInteger(GL45C.GL_SAMPLER_BINDING);
        }

        try {
            GlStateManager._glBindFramebuffer(GL45C.GL_DRAW_FRAMEBUFFER, framebuffer);
            GlStateManager._viewport(0, 0, mainTarget.width, mainTarget.height);
            GlStateManager._disableDepthTest();
            GlStateManager._depthMask(false);
            GlStateManager._disableCull();
            GlStateManager._disableScissorTest();
            GlStateManager._colorMask(0xF);
            GlStateManager._enableBlend(0);
            GlStateManager._blendFuncSeparate(
                    GL45C.GL_SRC_ALPHA, GL45C.GL_ONE_MINUS_SRC_ALPHA, GL45C.GL_ONE, GL45C.GL_ZERO);
            GlStateManager._blendEquationSeparate(GL45C.GL_FUNC_ADD, GL45C.GL_FUNC_ADD);

            if (rain > 0) {
                copyBackground(targetTexture, mainTarget.width, mainTarget.height);
            }
            bindTexture(UNIT_REMIX_COLOR, rain > 0 ? backgroundTexture : finalColorTexture, colorSampler);
            bindTexture(UNIT_LIGHTMAP, lightmap, colorSampler);
            bindTexture(UNIT_REMIX_DEPTH, depthTexture, depthSampler);
            GL45C.glBindVertexArray(vertexArray);

            if (rain > 0) {
                bindTexture(UNIT_WEATHER, rainTexture, weatherSampler);
                rainProgram.use(projection, modelView, mainTarget.width, mainTarget.height, false);
                GL45C.glDrawElements(GL45C.GL_TRIANGLES, rain * INDICES_PER_QUAD, GL45C.GL_UNSIGNED_INT, 0L);
                lastDrawCount++;
            }
            if (snow > 0) {
                bindTexture(UNIT_WEATHER, snowTexture, weatherSampler);
                snowProgram.use(projection, modelView, mainTarget.width, mainTarget.height, true);
                GL45C.glDrawElementsBaseVertex(GL45C.GL_TRIANGLES, snow * INDICES_PER_QUAD,
                        GL45C.GL_UNSIGNED_INT, 0L, rain * VERTICES_PER_QUAD);
                lastDrawCount++;
            }
            lastQuadCount = rain + snow;
        } finally {
            for (int unit = 0; unit < UNIT_COUNT; unit++) {
                GL45C.glBindSampler(unit, previousSamplers[unit]);
            }
            GL45C.glBindVertexArray(previousVertexArray);
            GL45C.glUseProgram(previousProgram);
            GlStateManager._glBindFramebuffer(GL45C.GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer);
            GlStateManager._viewport(
                    previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3]);
        }
    }

    /** Copies the target, attached to {@link #framebuffer}, into {@link #backgroundTexture}, in its format. */
    private void copyBackground(int target, int width, int height) {
        int format = GL45C.glGetTextureLevelParameteri(target, 0, GL45C.GL_TEXTURE_INTERNAL_FORMAT);
        if (backgroundTexture == 0 || width != backgroundWidth || height != backgroundHeight
                || format != backgroundFormat) {
            if (backgroundTexture != 0) {
                GL45C.glDeleteTextures(backgroundTexture);
            }
            if (backgroundFramebuffer == 0) {
                backgroundFramebuffer = GL45C.glCreateFramebuffers();
            }
            backgroundTexture = GL45C.glCreateTextures(GL45C.GL_TEXTURE_2D);
            GL45C.glTextureStorage2D(backgroundTexture, 1, format, width, height);
            GL45C.glNamedFramebufferTexture(backgroundFramebuffer, GL45C.GL_COLOR_ATTACHMENT0, backgroundTexture, 0);
            backgroundWidth = width;
            backgroundHeight = height;
            backgroundFormat = format;
        }
        GL45C.glBlitNamedFramebuffer(framebuffer, backgroundFramebuffer, 0, 0, width, height, 0, 0, width, height,
                GL45C.GL_COLOR_BUFFER_BIT, GL45C.GL_NEAREST);
    }

    public int getLastDrawCount() {
        return lastDrawCount;
    }

    public int getLastQuadCount() {
        return lastQuadCount;
    }

    public void cleanup() {
        beginCapture();
        if (rainProgram != null) {
            rainProgram.delete();
            rainProgram = null;
        }
        if (snowProgram != null) {
            snowProgram.delete();
            snowProgram = null;
        }
        if (vertexArray != 0) GL45C.glDeleteVertexArrays(vertexArray);
        if (vertexBuffer != 0) GL45C.glDeleteBuffers(vertexBuffer);
        if (indexBuffer != 0) GL45C.glDeleteBuffers(indexBuffer);
        if (framebuffer != 0) GL45C.glDeleteFramebuffers(framebuffer);
        if (backgroundFramebuffer != 0) GL45C.glDeleteFramebuffers(backgroundFramebuffer);
        if (backgroundTexture != 0) GL45C.glDeleteTextures(backgroundTexture);
        backgroundFramebuffer = backgroundTexture = backgroundWidth = backgroundHeight = 0;
        if (weatherSampler != 0) GL45C.glDeleteSamplers(weatherSampler);
        if (colorSampler != 0) GL45C.glDeleteSamplers(colorSampler);
        if (depthSampler != 0) GL45C.glDeleteSamplers(depthSampler);
        vertexArray = vertexBuffer = indexBuffer = indexBufferQuads = framebuffer = 0;
        weatherSampler = colorSampler = depthSampler = 0;
    }

    private static void bindTexture(int unit, int texture, int sampler) {
        GlStateManager._activeTexture(GL45C.GL_TEXTURE0 + unit);
        GlStateManager._bindTexture(texture);
        GL45C.glBindSampler(unit, sampler);
    }

    private static int glTexture(AbstractTexture texture) {
        GpuTexture gpuTexture = texture.getTexture();
        if (gpuTexture instanceof GlTexture glTexture) {
            return glTexture.glId();
        }
        throw new IllegalStateException("Weather texture is not an OpenGL texture");
    }

    private static int glTextureView(GpuTextureView view) {
        if (view instanceof GlTextureView glView) {
            return glView.texture().glId();
        }
        throw new IllegalStateException("Lightmap is not an OpenGL texture");
    }

    private void ensureGeometryObjects() {
        if (vertexArray != 0) {
            return;
        }
        VertexFormat format = DefaultVertexFormat.PARTICLE;
        vertexBuffer = GL45C.glCreateBuffers();
        indexBuffer = GL45C.glCreateBuffers();
        vertexArray = GL45C.glCreateVertexArrays();
        GL45C.glVertexArrayVertexBuffer(vertexArray, 0, vertexBuffer, 0L, format.getVertexSize());
        GL45C.glVertexArrayElementBuffer(vertexArray, indexBuffer);
        attribute(0, 3, GL45C.GL_FLOAT, false, format.getElement("Position").offset());
        attribute(1, 2, GL45C.GL_FLOAT, false, format.getElement("UV0").offset());
        attribute(2, 4, GL45C.GL_UNSIGNED_BYTE, true, format.getElement("Color").offset());
        GL45C.glEnableVertexArrayAttrib(vertexArray, 3);
        GL45C.glVertexArrayAttribIFormat(vertexArray, 3, 2, GL45C.GL_SHORT, format.getElement("UV2").offset());
        GL45C.glVertexArrayAttribBinding(vertexArray, 3, 0);
    }

    private void attribute(int index, int size, int type, boolean normalized, int offset) {
        GL45C.glEnableVertexArrayAttrib(vertexArray, index);
        GL45C.glVertexArrayAttribFormat(vertexArray, index, size, type, normalized, offset);
        GL45C.glVertexArrayAttribBinding(vertexArray, index, 0);
    }

    /** Two triangles per quad, as vanilla's sequential quad index buffer orders them. */
    private void ensureIndexCapacity(int quads) {
        if (quads <= indexBufferQuads) {
            return;
        }
        int capacity = Math.max(quads, Math.max(256, indexBufferQuads * 2));
        IntBuffer indices = MemoryUtil.memAllocInt(capacity * INDICES_PER_QUAD);
        try {
            for (int quad = 0; quad < capacity; quad++) {
                int first = quad * VERTICES_PER_QUAD;
                indices.put(first).put(first + 1).put(first + 2)
                        .put(first + 2).put(first + 3).put(first);
            }
            indices.flip();
            GL45C.glNamedBufferData(indexBuffer, indices, GL45C.GL_STATIC_DRAW);
        } finally {
            MemoryUtil.memFree(indices);
        }
        indexBufferQuads = capacity;
    }

    private void ensurePrograms() {
        if (rainProgram == null) {
            rainProgram = new Program(VERTEX_SHADER, RAIN_FRAGMENT_SHADER);
        }
        if (snowProgram == null) {
            snowProgram = new Program(VERTEX_SHADER, SNOW_FRAGMENT_SHADER);
        }
    }

    private void ensureSamplers() {
        if (weatherSampler != 0) {
            return;
        }
        // Vanilla samples the weather textures unfiltered, and their V coordinate runs past 1.
        weatherSampler = sampler(GL45C.GL_NEAREST, GL45C.GL_REPEAT);
        colorSampler = sampler(GL45C.GL_LINEAR, GL45C.GL_CLAMP_TO_EDGE);
        depthSampler = sampler(GL45C.GL_NEAREST, GL45C.GL_CLAMP_TO_EDGE);
    }

    private static int sampler(int filter, int wrap) {
        int sampler = GL45C.glCreateSamplers();
        GL45C.glSamplerParameteri(sampler, GL45C.GL_TEXTURE_MIN_FILTER, filter);
        GL45C.glSamplerParameteri(sampler, GL45C.GL_TEXTURE_MAG_FILTER, filter);
        GL45C.glSamplerParameteri(sampler, GL45C.GL_TEXTURE_WRAP_S, wrap);
        GL45C.glSamplerParameteri(sampler, GL45C.GL_TEXTURE_WRAP_T, wrap);
        return sampler;
    }

    private static final class Program {
        private final int id;
        private final int modelViewLocation;
        private final int projectionLocation;
        private final int viewportSizeLocation;
        private final int applyLightmapLocation;

        Program(String vertexSource, String fragmentSource) {
            int vertexShader = compile(GL45C.GL_VERTEX_SHADER, vertexSource);
            int fragmentShader = 0;
            int program = 0;
            try {
                fragmentShader = compile(GL45C.GL_FRAGMENT_SHADER, fragmentSource);
                program = GL45C.glCreateProgram();
                GL45C.glAttachShader(program, vertexShader);
                GL45C.glAttachShader(program, fragmentShader);
                GL45C.glLinkProgram(program);
                if (GL45C.glGetProgrami(program, GL45C.GL_LINK_STATUS) == GL45C.GL_FALSE) {
                    String log = GL45C.glGetProgramInfoLog(program);
                    GL45C.glDeleteProgram(program);
                    throw new IllegalStateException("Failed to link weather shader: " + log);
                }
            } finally {
                GL45C.glDeleteShader(vertexShader);
                if (fragmentShader != 0) {
                    GL45C.glDeleteShader(fragmentShader);
                }
            }
            id = program;
            modelViewLocation = GL45C.glGetUniformLocation(id, "ModelViewMat");
            projectionLocation = GL45C.glGetUniformLocation(id, "ProjMat");
            viewportSizeLocation = GL45C.glGetUniformLocation(id, "ViewportSize");
            applyLightmapLocation = GL45C.glGetUniformLocation(id, "ApplyLightmap");
            GL45C.glProgramUniform1i(id, GL45C.glGetUniformLocation(id, "Sampler0"), UNIT_WEATHER);
            GL45C.glProgramUniform1i(id, GL45C.glGetUniformLocation(id, "Sampler1"), UNIT_REMIX_COLOR);
            GL45C.glProgramUniform1i(id, GL45C.glGetUniformLocation(id, "Lightmap"), UNIT_LIGHTMAP);
            GL45C.glProgramUniform1i(id, GL45C.glGetUniformLocation(id, "RemixDepth"), UNIT_REMIX_DEPTH);
        }

        void use(Matrix4f projection, Matrix4f modelView, int width, int height, boolean applyLightmap) {
            GL45C.glUseProgram(id);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                FloatBuffer matrix = stack.mallocFloat(16);
                GL45C.glUniformMatrix4fv(modelViewLocation, false, modelView.get(matrix));
                GL45C.glUniformMatrix4fv(projectionLocation, false, projection.get(matrix));
            }
            GL45C.glUniform2f(viewportSizeLocation, width, height);
            GL45C.glUniform1i(applyLightmapLocation, applyLightmap ? 1 : 0);
        }

        void delete() {
            GL45C.glDeleteProgram(id);
        }

        private static int compile(int type, String source) {
            int shader = GL45C.glCreateShader(type);
            GL45C.glShaderSource(shader, source);
            GL45C.glCompileShader(shader);
            if (GL45C.glGetShaderi(shader, GL45C.GL_COMPILE_STATUS) == GL45C.GL_FALSE) {
                String log = GL45C.glGetShaderInfoLog(shader);
                GL45C.glDeleteShader(shader);
                throw new IllegalStateException("Failed to compile weather shader: " + log);
            }
            return shader;
        }
    }
}
