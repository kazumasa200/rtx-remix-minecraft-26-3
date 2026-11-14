/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.TextureBatchEntry;
import com.nvidia.rtxremix.TextureFormat;
import com.nvidia.rtxremix.TexturePatch;
import com.nvidia.rtxremix.TexturePatchInfo;
import com.nvidia.rtxremix.TexturePurpose;
import com.nvidia.rtxremix.minecraft.asset.RemixTexture;
import com.nvidia.rtxremix.minecraft.util.DirectBufferPool;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The water surface's normal map: the ocean flipbook's frames played into one texture that
 * tiles in world space, with raindrop rings added while it rains.
 *
 * <p>The runtime used to play the flipbook as a sprite sheet, where every frame borders other
 * frames instead of wrapping onto itself, so a tile's edge never met the next tile's waves.
 * One texture the hardware repeats has no such edge, and the runtime regenerates its mips
 * after every update. Rain reaches the runtime the same way, as texture content, because a
 * material's surface state cannot change after it is made.
 *
 * <p>A flipbook frame covers a quarter of the texture each way, and laid on its own it showed
 * the same waves every ten blocks, a lattice of swells that stood out across open water. So
 * the texture lays the flipbook three times over: at its own size, at twice that turned a
 * quarter, and at four times turned a half, each on its own frame and each without its longest
 * waves, which drew the lattice at whatever size a layer was laid. The larger layers bring
 * back waves of those lengths without one, and the sum repeats only once a texture.
 *
 * <p>Rain puddles get a texture of their own with the rings alone, smaller and more of them,
 * over {@link #PUDDLE_TILE_BLOCKS}: standing water in a puddle has no waves, and once the rain
 * stops it lies still.
 *
 * <p>The flipbook is not a file: the bridge computes it on a worker, about a second's work
 * (see {@code water_normals.cpp}), and its frames are used at half that size; until then the
 * water is calm. The surface is the same every time, so a session computes it once. A frame
 * is composed on a thread of its own and uploaded on the render thread once it is done.
 */
public final class WaterNormals {

    private static final Logger LOGGER = LoggerFactory.getLogger(WaterNormals.class);

    /** Texels across the texture, which covers {@link #FRAMES_ACROSS} flipbook frames each way. */
    public static final int SIZE = 1024;
    /** Texels across a flipbook frame as it is used, half the size the bridge generates it at. */
    private static final int FRAME_SIZE = 512;
    private static final int FRAMES_ACROSS = 4;
    /** Blocks the puddles' texture spans, which the puddles' tiles match. */
    public static final int PUDDLE_TILE_BLOCKS = 16;
    /** Texels across the puddles' texture: their rings, a half of it across, laid twice each way. */
    private static final int PUDDLE_SIZE = 512;
    private static final int PUDDLE_RING_PX = PUDDLE_SIZE / 2;
    /** The puddles' rings against the water's: smaller, and more of them, as fine drops on shallow water. */
    private static final float PUDDLE_RING_SIZE = 0.6f;
    private static final float PUDDLE_RING_DENSITY = 2.5f;
    private static final long PUDDLE_RIPPLE_SEED = 0x50524E44L;
    /**
     * Each layer's share of the slope, at the flipbook's own size, twice and four times it: half
     * as much again as they would need for their slopes to add up to the flipbook's, since the
     * high pass takes about a third of it.
     */
    private static final float NEAR_SHARE = 1.1f;
    private static final float MIDDLE_SHARE = 0.65f;
    private static final float FAR_SHARE = 0.5f;
    /**
     * Texels of a frame its slopes are blurred over, twice each way, before the blur is taken
     * away: the flipbook's waves longer than about a quarter of a frame, which drew the same
     * lattice of swells in every frame's span and in every layer's, go, and the larger layers
     * bring back waves of those lengths without the lattice.
     */
    private static final int HIGH_PASS_RADIUS = 32;
    /** A high-passed frame's slope per step of its signed bytes. */
    private static final float SLOPE_STEP = 1f / 254f;
    /** Frames between the layers, so no two show the same waves. */
    private static final int LAYER_FRAME_STEP = 40;
    // The flipbook's layout, as rtxremix-java/native/water_normals.cpp generates it.
    private static final int SHEET_COLS = 12;
    private static final int SHEET_ROWS = 10;
    private static final int FRAMES = SHEET_COLS * SHEET_ROWS;
    private static final int FPS = 20;
    /** Of the seeds tried, 225's longest waves share the slope most like the water this replaced. */
    private static final long OCEAN_SEED = 225L;
    private static final long RIPPLE_SEED = 0x52414E44L;
    /** Rain strength change that redraws a held frame. */
    private static final float STRENGTH_EPSILON = 0.02f;
    /** An octahedral red-green pair's slopes, x and y, by the pair as {@code red << 8 | green}. */
    private static final float[] SLOPE_X = new float[65536];
    private static final float[] SLOPE_Y = new float[65536];

    static {
        for (int red = 0; red < 256; red++) {
            for (int green = 0; green < 256; green++) {
                // An upper-hemisphere normal is proportional to (px, py, 1 - |px| - |py|), so
                // its slopes are px / pz and py / pz.
                float x = red / 127.5f - 1f;
                float y = green / 127.5f - 1f;
                float z = Math.max(1.0e-3f, 1f - Math.abs(x) - Math.abs(y));
                SLOPE_X[red << 8 | green] = x / z;
                SLOPE_Y[red << 8 | green] = y / z;
            }
        }
    }

    /** The session's ocean frames, high-passed; kept across water material rebuilds. */
    private static CompletableFuture<byte[][]> oceanFrames;

    private final RemixAPI api;
    private final RemixTexture texture;
    private final RemixTexture puddleTexture;
    /** Per frame, the high-passed slopes, x and y, as signed byte pairs; see {@link #highPass}. */
    private final CompletableFuture<byte[][]> ocean;
    private final CompletableFuture<RainRipples> ripples;
    private final CompletableFuture<RainRipples> puddleRipples;
    private final byte[] texels = new byte[SIZE * SIZE * 4];
    private final byte[] puddleTexels = new byte[PUDDLE_SIZE * PUDDLE_SIZE * 4];
    private final ByteBuffer staging;
    private final ByteBuffer puddleStaging;
    private final TexturePatch patch = new TexturePatch();
    private final TexturePatch puddlePatch = new TexturePatch();
    private final TextureBatchEntry entry = new TextureBatchEntry();
    private final TextureBatchEntry puddleEntry = new TextureBatchEntry();
    /** Both textures, or the water's alone when the puddles' rings did not change. */
    private final TexturePatchInfo bothInfo = new TexturePatchInfo();
    private final TexturePatchInfo waterInfo = new TexturePatchInfo();
    private final ExecutorService composer = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "rtx-water-normals");
        thread.setDaemon(true);
        return thread;
    });

    /** The frame being composed into {@link #texels}, or null; one at a time. */
    private CompletableFuture<Void> composing;
    private int composingOceanFrame;
    private int composingRingFrame;
    private float composingStrength;
    private boolean composingPuddle;
    private int appliedOceanFrame = -1;
    private int appliedRingFrame = -1;
    private float appliedStrength;
    private boolean released;

    private WaterNormals(RemixAPI api, RemixTexture texture, RemixTexture puddleTexture,
                         float tileBlocks, boolean rings) {
        this.api = api;
        this.texture = texture;
        this.puddleTexture = puddleTexture;
        this.staging = DirectBufferPool.acquire(texels.length);
        this.puddleStaging = DirectBufferPool.acquire(puddleTexels.length);
        patch.width = SIZE;
        patch.height = SIZE;
        patch.rowPitch = SIZE * 4;
        patch.format = TextureFormat.RGBA8_UNORM;
        entry.patches = new TexturePatch[] {patch};
        puddlePatch.width = PUDDLE_SIZE;
        puddlePatch.height = PUDDLE_SIZE;
        puddlePatch.rowPitch = PUDDLE_SIZE * 4;
        puddlePatch.format = TextureFormat.RGBA8_UNORM;
        puddleEntry.patches = new TexturePatch[] {puddlePatch};
        bothInfo.entries = new TextureBatchEntry[] {entry, puddleEntry};
        waterInfo.entries = new TextureBatchEntry[] {entry};
        ocean = oceanFrames(api);
        // The rings tile a flipbook frame's span, as the nearest layer does.
        ripples = rings
                ? CompletableFuture.supplyAsync(() -> RainRipples.generate(FRAME_SIZE, tileBlocks / FRAMES_ACROSS, RIPPLE_SEED))
                : CompletableFuture.completedFuture(null);
        puddleRipples = rings
                ? CompletableFuture.supplyAsync(() -> RainRipples.generate(PUDDLE_RING_PX, PUDDLE_TILE_BLOCKS / 2f,
                        PUDDLE_RIPPLE_SEED, PUDDLE_RING_SIZE, PUDDLE_RING_DENSITY))
                : CompletableFuture.completedFuture(null);
    }

    /**
     * Creates calm water normals over a tile {@code tileBlocks} wide, and the puddles' over
     * {@link #PUDDLE_TILE_BLOCKS}, or returns null when a texture fails; the uploader's owner
     * destroys any it made. {@code rings} adds raindrop rings in the rain.
     */
    public static WaterNormals create(RemixAPI api, Uploader uploader, float tileBlocks, boolean rings) {
        RemixTexture texture = uploader.upload("water_normals", calmNormals(SIZE), SIZE, SIZE,
                TextureFormat.RGBA8_UNORM, TexturePurpose.NORMAL_OTH);
        if (texture == null) return null;
        RemixTexture puddle = uploader.upload("puddle_normals", calmNormals(PUDDLE_SIZE), PUDDLE_SIZE, PUDDLE_SIZE,
                TextureFormat.RGBA8_UNORM, TexturePurpose.NORMAL_OTH);
        return puddle == null ? null : new WaterNormals(api, texture, puddle, tileBlocks, rings);
    }

    /** Creates a texture the owner manages and destroys. */
    public interface Uploader {
        RemixTexture upload(String name, byte[] rgba, int width, int height, int format, int purpose);
    }

    public RemixTexture texture() {
        return texture;
    }

    /** The puddles' normals: the rings alone, over {@link #PUDDLE_TILE_BLOCKS}. */
    public RemixTexture puddleTexture() {
        return puddleTexture;
    }

    /**
     * Shows the waves at {@code seconds} into their animation and rain of {@code rain}, 0 to 1:
     * uploads the frame last composed once it is done, and composes the next when a frame or the
     * rain changed, so the water trails by the frame or two a composition takes.
     */
    public void update(float rain, double seconds) {
        if (released || texture.isDeleted()) return;
        byte[][] frames = ocean.getNow(null);
        if (frames == null) return;
        if (composing != null && composing.isDone()) {
            try {
                composing.join();
                upload();
            } catch (RuntimeException e) {
                LOGGER.warn("Failed to compose the water normals: {}", e.getMessage());
            }
            composing = null;
        }
        int oceanFrame = (int) Math.floorMod((long) Math.floor(seconds * FPS), (long) FRAMES);
        RainRipples rings = rain > 0f ? ripples.getNow(null) : null;
        int ringFrame = rings != null ? RainRipples.frameAt(seconds) : -1;
        float strength = rings != null ? Math.min(1f, rain) : 0f;
        if (composing != null || (oceanFrame == appliedOceanFrame && ringFrame == appliedRingFrame
                && Math.abs(strength - appliedStrength) < STRENGTH_EPSILON)) {
            return;
        }
        boolean puddle = appliedOceanFrame < 0 || ringFrame != appliedRingFrame
                || Math.abs(strength - appliedStrength) >= STRENGTH_EPSILON;
        composingOceanFrame = oceanFrame;
        composingRingFrame = ringFrame;
        composingStrength = strength;
        composingPuddle = puddle;
        byte[] ringRg = rings != null ? rings.normals(ringFrame) : null;
        RainRipples puddleRings = rings != null ? puddleRipples.getNow(null) : null;
        byte[] puddleRg = puddleRings != null ? puddleRings.normals(ringFrame) : null;
        composing = CompletableFuture.runAsync(() -> {
            compose(frames, oceanFrame, ringRg, strength, texels);
            if (puddle) composeRings(puddleRg, strength, puddleTexels);
        }, composer);
    }

    private void upload() {
        staging.clear();
        staging.put(texels).flip();
        patch.pixelData = staging;
        entry.handle = texture.getHandle();
        boolean puddle = composingPuddle && !puddleTexture.isDeleted();
        if (puddle) {
            puddleStaging.clear();
            puddleStaging.put(puddleTexels).flip();
            puddlePatch.pixelData = puddleStaging;
            puddleEntry.handle = puddleTexture.getHandle();
        }
        try {
            api.updateTextureFromMemory(puddle ? bothInfo : waterInfo);
            appliedOceanFrame = composingOceanFrame;
            appliedRingFrame = composingRingFrame;
            appliedStrength = composingStrength;
        } catch (RuntimeException e) {
            LOGGER.warn("Failed to update the water normals: {}", e.getMessage());
        } finally {
            patch.pixelData = null;
            puddlePatch.pixelData = null;
        }
    }

    /**
     * Writes the puddles' texture: one frame of their rings' slopes, laid twice each way and
     * scaled by {@code strength}, or flat water without rings, in the same encoding as
     * {@link #compose}.
     */
    static void composeRings(byte[] ringRg, float strength, byte[] rgba) {
        int mask = PUDDLE_RING_PX - 1;
        for (int j = 0; j < PUDDLE_SIZE; j++) {
            for (int i = 0; i < PUDDLE_SIZE; i++) {
                float sx = 0f;
                float sy = 0f;
                if (ringRg != null && strength > 0f) {
                    int at = ((j & mask) * PUDDLE_RING_PX + (i & mask)) * 2;
                    int p = (ringRg[at] & 0xFF) << 8 | (ringRg[at + 1] & 0xFF);
                    sx = strength * SLOPE_X[p];
                    sy = strength * SLOPE_Y[p];
                }
                float norm = Math.abs(sx) + Math.abs(sy) + 1f;
                int out = (j * PUDDLE_SIZE + i) * 4;
                rgba[out] = encode(sx / norm);
                rgba[out + 1] = encode(sy / norm);
                rgba[out + 2] = 0;
                rgba[out + 3] = (byte) 0xFF;
            }
        }
    }

    /**
     * Writes the texture for flipbook frame {@code frame}: its three layers' slopes (see the
     * class comment) with the rings', scaled by {@code strength}, added, as octahedral normals in
     * the red and green of RGBA8 texels: the runtime's normal encoding, which an update must
     * already be in. A layer turned by a quarter or a half turns its slopes with it.
     */
    static void compose(byte[][] frames, int frame, byte[] ringRg, float strength, byte[] rgba) {
        byte[] near = frames[frame];
        byte[] middle = frames[(frame + LAYER_FRAME_STEP) % frames.length];
        byte[] far = frames[(frame + 2 * LAYER_FRAME_STEP) % frames.length];
        int mask = FRAME_SIZE - 1;
        float[] slope = new float[2];
        for (int j = 0; j < SIZE; j++) {
            int nearY = (j << 1) & mask;
            int middleX = j & mask;
            int farY = (-(j >> 1)) & mask;
            int farY2 = (j & 1) == 0 ? farY : (farY - 1) & mask;
            for (int i = 0; i < SIZE; i++) {
                // At the flipbook's own size, two texels of it to one of the texture: their mean.
                int nearX = (i << 1) & mask;
                float sx = 0f;
                float sy = 0f;
                for (int corner = 0; corner < 4; corner++) {
                    int at = texel((nearX + (corner & 1)) & mask, (nearY + (corner >> 1)) & mask);
                    sx += near[at];
                    sy += near[at + 1];
                }
                sx *= NEAR_SHARE * 0.25f * SLOPE_STEP;
                sy *= NEAR_SHARE * 0.25f * SLOPE_STEP;
                // At twice the size, turned a quarter: a texel of it to one of the texture.
                int at = texel(middleX, (-i) & mask);
                sx -= MIDDLE_SHARE * SLOPE_STEP * middle[at + 1];
                sy += MIDDLE_SHARE * SLOPE_STEP * middle[at];
                // At four times the size, turned a half: between texels of it every other texel.
                int farX = (-(i >> 1)) & mask;
                int farX2 = (i & 1) == 0 ? farX : (farX - 1) & mask;
                float fx = 0f;
                float fy = 0f;
                for (int corner = 0; corner < 4; corner++) {
                    int c = texel((corner & 1) == 0 ? farX : farX2, corner < 2 ? farY : farY2);
                    fx += far[c];
                    fy += far[c + 1];
                }
                sx -= FAR_SHARE * 0.25f * SLOPE_STEP * fx;
                sy -= FAR_SHARE * 0.25f * SLOPE_STEP * fy;
                if (ringRg != null && strength > 0f) {
                    slope[0] = 0f;
                    slope[1] = 0f;
                    addBox(ringRg, nearX, nearY, strength * 0.25f, slope);
                    sx += slope[0];
                    sy += slope[1];
                }
                // The sum projected back onto the octahedron.
                float norm = Math.abs(sx) + Math.abs(sy) + 1f;
                int out = (j * SIZE + i) * 4;
                rgba[out] = encode(sx / norm);
                rgba[out + 1] = encode(sy / norm);
                rgba[out + 2] = 0;
                rgba[out + 3] = (byte) 0xFF;
            }
        }
    }

    /**
     * Turns octahedral frames into their high-passed slopes, in place, as signed bytes of
     * {@link #SLOPE_STEP}: each frame's slopes less their blur over {@link #HIGH_PASS_RADIUS}.
     * The blur wraps as the frame does.
     */
    static byte[][] highPass(byte[][] frames) {
        int texels = FRAME_SIZE * FRAME_SIZE;
        float[] x = new float[texels];
        float[] y = new float[texels];
        float[] blur = new float[texels];
        float[] temp = new float[texels];
        for (byte[] frame : frames) {
            for (int i = 0; i < texels; i++) {
                int p = (frame[i * 2] & 0xFF) << 8 | (frame[i * 2 + 1] & 0xFF);
                x[i] = SLOPE_X[p];
                y[i] = SLOPE_Y[p];
            }
            subtractBlur(x, blur, temp);
            subtractBlur(y, blur, temp);
            for (int i = 0; i < texels; i++) {
                frame[i * 2] = (byte) Math.clamp(Math.round(x[i] / SLOPE_STEP), -127, 127);
                frame[i * 2 + 1] = (byte) Math.clamp(Math.round(y[i] / SLOPE_STEP), -127, 127);
            }
        }
        return frames;
    }

    private static void subtractBlur(float[] values, float[] blur, float[] temp) {
        boxBlur(values, temp, 1, FRAME_SIZE);
        boxBlur(temp, blur, FRAME_SIZE, 1);
        boxBlur(blur, temp, 1, FRAME_SIZE);
        boxBlur(temp, blur, FRAME_SIZE, 1);
        for (int i = 0; i < values.length; i++) {
            values[i] -= blur[i];
        }
    }

    /**
     * A wrapping box blur of {@link #HIGH_PASS_RADIUS} along one axis of a frame: texels
     * {@code step} apart along a line, lines {@code lineStep} apart.
     */
    private static void boxBlur(float[] from, float[] to, int step, int lineStep) {
        int radius = HIGH_PASS_RADIUS;
        float scale = 1f / (2 * radius + 1);
        for (int line = 0; line < FRAME_SIZE; line++) {
            int start = line * lineStep;
            float sum = 0f;
            for (int k = -radius; k <= radius; k++) {
                sum += from[start + Math.floorMod(k, FRAME_SIZE) * step];
            }
            for (int k = 0; k < FRAME_SIZE; k++) {
                to[start + k * step] = sum * scale;
                sum += from[start + ((k + radius + 1) & (FRAME_SIZE - 1)) * step]
                        - from[start + ((k - radius) & (FRAME_SIZE - 1)) * step];
            }
        }
    }

    /** Where texel x, y of a frame starts in its byte pairs. */
    private static int texel(int x, int y) {
        return (y * FRAME_SIZE + x) * 2;
    }

    /** Adds the slopes of the two-by-two texels from x, y of an octahedral frame, times {@code weight}. */
    private static void addBox(byte[] rg, int x, int y, float weight, float[] slope) {
        int mask = FRAME_SIZE - 1;
        for (int corner = 0; corner < 4; corner++) {
            int p = pair(rg, (x + (corner & 1)) & mask, (y + (corner >> 1)) & mask);
            slope[0] += weight * SLOPE_X[p];
            slope[1] += weight * SLOPE_Y[p];
        }
    }

    /** The red-green pair of a frame's texel x, y, as {@code red << 8 | green}. */
    private static int pair(byte[] rg, int x, int y) {
        int at = (y * FRAME_SIZE + x) * 2;
        return (rg[at] & 0xFF) << 8 | (rg[at + 1] & 0xFF);
    }

    /** Returns the staging buffer; the owner destroys the texture. */
    public void release() {
        if (released) return;
        released = true;
        ripples.cancel(false);
        puddleRipples.cancel(false);
        composer.shutdownNow();
        DirectBufferPool.release(staging);
        DirectBufferPool.release(puddleStaging);
    }

    /** The session's ocean frames, started on first use and again after a failure. */
    private static synchronized CompletableFuture<byte[][]> oceanFrames(RemixAPI api) {
        if (oceanFrames == null || (oceanFrames.isDone() && oceanFrames.getNow(null) == null)) {
            oceanFrames = CompletableFuture.supplyAsync(() -> {
                try {
                    return highPass(generateFrames(api));
                } catch (RuntimeException | LinkageError | OutOfMemoryError e) {
                    // A bridge without the generator (a stale development copy) is a LinkageError.
                    LOGGER.warn("Failed to generate the water normal flipbook; the water stays calm", e);
                    return null;
                }
            });
        }
        return oceanFrames;
    }

    /** Every frame of the flipbook at {@link #FRAME_SIZE}, as red-green pairs per texel. */
    private static byte[][] generateFrames(RemixAPI api) {
        // The whole sheet at the bridge's size, 240 MiB, held only until its frames are halved.
        ByteBuffer sheet = DirectBufferPool.acquire(api.waterNormalsTextureSize());
        try {
            double seconds = api.generateWaterNormals(sheet, OCEAN_SEED);
            LOGGER.info("Generated the water normal flipbook in {} s", String.format("%.2f", seconds));
            return halveFrames(sheet, FRAME_SIZE * 2);
        } finally {
            DirectBufferPool.release(sheet);
        }
    }

    /**
     * Splits a sheet of {@link #SHEET_COLS} by {@link #SHEET_ROWS} red-green frames, row by row,
     * into frames half {@code frameSize} across, each texel the mean of two by two.
     */
    static byte[][] halveFrames(ByteBuffer sheet, int frameSize) {
        int half = frameSize / 2;
        int sheetPitch = SHEET_COLS * frameSize * 2;
        byte[][] frames = new byte[FRAMES][half * half * 2];
        byte[] upper = new byte[frameSize * 2];
        byte[] lower = new byte[frameSize * 2];
        for (int f = 0; f < FRAMES; f++) {
            byte[] frame = frames[f];
            int origin = (f / SHEET_COLS) * frameSize * sheetPitch + (f % SHEET_COLS) * frameSize * 2;
            for (int y = 0; y < half; y++) {
                sheet.get(origin + 2 * y * sheetPitch, upper);
                sheet.get(origin + (2 * y + 1) * sheetPitch, lower);
                for (int i = 0; i < half * 2; i++) {
                    // Channel i & 1 of the texel pair starting at texel i / 2 * 2.
                    int at = (i >> 1) * 4 + (i & 1);
                    int sum = (upper[at] & 0xFF) + (upper[at + 2] & 0xFF) + (lower[at] & 0xFF) + (lower[at + 2] & 0xFF);
                    frame[y * half * 2 + i] = (byte) ((sum + 2) >> 2);
                }
            }
        }
        return frames;
    }

    private static byte encode(float component) {
        return (byte) Math.round((component * 0.5f + 0.5f) * 255f);
    }

    /** Straight-up normals in the runtime's octahedral encoding, {@code size} texels across. */
    private static byte[] calmNormals(int size) {
        byte[] rgba = new byte[size * size * 4];
        for (int i = 0; i < size * size; i++) {
            rgba[i * 4] = (byte) 128;
            rgba[i * 4 + 1] = (byte) 128;
            rgba[i * 4 + 3] = (byte) 255;
        }
        return rgba;
    }
}
