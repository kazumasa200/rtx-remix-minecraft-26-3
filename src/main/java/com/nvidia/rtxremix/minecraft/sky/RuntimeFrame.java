/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.sky;

import com.nvidia.rtxremix.minecraft.RuntimePatchLayer;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * The frame block the patched runtime shaders read at descriptor set 4 (runtime-patch/shaders/rrp_frame.glsl),
 * written here and handed to the layer once a frame just before the runtime presents. The offsets are the
 * compiled library's (runtime-patch/shaders/compiled/frame_layout.txt); RuntimeFrameTest holds them together.
 * Render thread only. A frame nothing wrote is all zeros, which draws no clouds.
 */
public final class RuntimeFrame {

    static final int BYTES = 256;
    static final int ENABLED = 0;
    static final int FRAME_INDEX = 4;
    static final int MARCH_CACHE = 8;
    static final int PARITY = 12;
    static final int FRAME_CAMERA_XZ = 16;
    static final int FRAME_CAMERA_HEIGHT = 24;
    static final int HISTORY_WEIGHT = 28;
    static final int SCENE_TO_CLOUD = 32;
    static final int SHADOW_STRENGTH = 40;
    static final int LIGHT_CONE_COS = 44;
    static final int LIGHT_DIRECTION = 48;
    static final int DISTANT_RAY_MIN = 60;
    static final int LIGHT_IRRADIANCE = 64;
    static final int CLOUD_BASE = 76;
    static final int AMBIENT_TOP = 80;
    static final int CLOUD_THICKNESS = 92;
    static final int AMBIENT_BOTTOM = 96;
    static final int DENSITY = 108;
    static final int LAYER_COVERAGE = 112;
    static final int FEATURE_SCALE = 124;
    static final int DETAIL = 128;
    static final int BRIGHTNESS = 132;
    static final int HAZE_DISTANCE = 136;
    static final int CIRRUS_ANGLE = 140;
    static final int CIRRUS_STRENGTH = 144;
    static final int CIRRUS_MIN_ELEVATION = 148;
    static final int SHADOW_TELLTALE = 152;
    static final int FLASH = 160;
    static final int SHAPE_COUNT = 176;
    static final int SHAPE_BOUNDS = 192;
    static final int SHAPE_FORM = 208;
    static final int SHAPE_SIZE = 224;
    /** Bytes between the elements of a shape array (std140 vec4). */
    static final int SHAPE_STRIDE = 16;
    static final int GLASS_SATURATION = 240;
    static final int GLASS_LIFT = 244;
    static final int PANE_DEPTH = 248;
    static final int WATER_DISTANCE = 252;

    /**
     * Glass Light Saturation: how saturated the light through stained glass is, as a multiple of the glass's own
     * colour (runtime-patch/shaders/rrp_glass.comp).
     */
    public static final float MIN_GLASS_SATURATION = 0.25f, DEFAULT_GLASS_SATURATION = 1.0f,
            MAX_GLASS_SATURATION = 2.0f;
    /**
     * Glass Pane Depth: glass seen directly absorbs as if at least this many of its measurement distances thick,
     * so a pane shows its colour; 0 leaves panes as the runtime draws them. Past 2.8 a whole block deepens too.
     */
    public static final float DEFAULT_PANE_DEPTH = 1.5f, MAX_PANE_DEPTH = 2.8f;
    /** What a stained glass's brightest channel is raised to, per measurement distance, by its saturation. */
    private static final float GLASS_LIFT_VALUE = 0.9f;

    /** The cone, about the clouds' light, within which a long shadow ray counts as its ray. */
    private static final float LIGHT_CONE_DEGREES = 2.5f;
    /** A shadow ray at least this long is a distant light's: the runtime places their samples 1e5 out. */
    private static final float DISTANT_RAY_MIN_BLOCKS = 5.0e4f;

    private static final MemorySegment BLOCK = Arena.global().allocate(BYTES, 16);
    private static int frameIndex;
    private static volatile float glassSaturation = DEFAULT_GLASS_SATURATION;
    private static volatile float paneDepth = DEFAULT_PANE_DEPTH;
    /** The Debug page's switch: the Unbound clouds drawn in GL (dome and lens) even where the runtime can. */
    private static volatile boolean drawnInGl;

    private RuntimeFrame() {
    }

    public static boolean isDrawnInGl() {
        return drawnInGl;
    }

    public static void setDrawnInGl(boolean gl) {
        drawnInGl = gl;
    }

    /**
     * The Debug page's Cloud Shadow Test: every shadow ray of the clouds' light carries nothing, so what the sun
     * or moon lights goes dark and a lamp's light, which the clouds never dim, stays.
     */
    private static volatile boolean shadowTelltale;

    public static boolean isShadowTelltale() {
        return shadowTelltale;
    }

    public static void setShadowTelltale(boolean on) {
        shadowTelltale = on;
    }

    public static float getGlassSaturation() {
        return glassSaturation;
    }

    public static void setGlassSaturation(float value) {
        glassSaturation = Math.max(MIN_GLASS_SATURATION, Math.min(MAX_GLASS_SATURATION, value));
    }

    public static float getPaneDepth() {
        return paneDepth;
    }

    public static void setPaneDepth(float value) {
        paneDepth = Math.max(0.0f, Math.min(MAX_PANE_DEPTH, value));
    }

    /**
     * This frame's clouds, drawn by the runtime: the field's inputs as the GL march takes them, and the offset
     * from scene space to the clouds' frame at the camera.
     */
    static void clouds(VolumetricCloudGenerator.Frame frame, double sceneToCloudX, double sceneToCloudZ,
                       float shadowStrength, boolean marchCache) {
        BLOCK.set(ValueLayout.JAVA_INT, ENABLED, 1);
        BLOCK.set(ValueLayout.JAVA_INT, MARCH_CACHE, marchCache ? 1 : 0);
        BLOCK.set(ValueLayout.JAVA_INT, PARITY, (int) frame.parity);
        putFloats(FRAME_CAMERA_XZ, frame.cameraX, frame.cameraZ, frame.cameraHeight, frame.historyWeight);
        putFloats(SCENE_TO_CLOUD, (float) sceneToCloudX, (float) sceneToCloudZ, shadowStrength,
                (float) Math.cos(Math.toRadians(LIGHT_CONE_DEGREES)));
        putFloats(LIGHT_DIRECTION, frame.lightDirection[0], frame.lightDirection[1], frame.lightDirection[2],
                DISTANT_RAY_MIN_BLOCKS);
        putFloats(LIGHT_IRRADIANCE, frame.lightIrradiance[0], frame.lightIrradiance[1], frame.lightIrradiance[2],
                frame.base);
        putFloats(AMBIENT_TOP, frame.ambientTop[0], frame.ambientTop[1], frame.ambientTop[2], frame.thickness);
        putFloats(AMBIENT_BOTTOM, frame.ambientBottom[0], frame.ambientBottom[1], frame.ambientBottom[2],
                frame.density);
        putFloats(LAYER_COVERAGE, frame.coverage[0], frame.coverage[1], frame.coverage[2], frame.featureScale);
        putFloats(DETAIL, frame.detail, frame.brightness, frame.hazeDistance, frame.cirrusAngle);
        putFloats(CIRRUS_STRENGTH, frame.cirrusStrength, SkyLayerTextures.CIRRUS_MIN_ELEVATION_DEGREES,
                shadowTelltale ? 1.0f : 0.0f, 0.0f);
        putFloats(FLASH, frame.flash[0], frame.flash[1], frame.flash[2], frame.flash[3]);
        CloudShapes shapes = frame.shapes;
        BLOCK.set(ValueLayout.JAVA_INT, SHAPE_COUNT, shapes.count);
        for (int i = 0; i < CloudShapes.MAX_SHAPES; i++) {
            putFloats(SHAPE_BOUNDS + i * SHAPE_STRIDE, shapes.bounds, i * 4);
            putFloats(SHAPE_FORM + i * SHAPE_STRIDE, shapes.form, i * 4);
            putFloats(SHAPE_SIZE + i * SHAPE_STRIDE, shapes.size, i * 4);
        }
    }

    /**
     * Hands this frame's block to the layer, then clears it, so a frame in which nothing writes the clouds
     * draws none. The glass settings and the water's measurement distance, by which the caustics tell water, go
     * in every frame. Call once a frame, just before the runtime presents.
     */
    public static void send() {
        BLOCK.set(ValueLayout.JAVA_INT, FRAME_INDEX, frameIndex++);
        BLOCK.set(ValueLayout.JAVA_FLOAT, GLASS_SATURATION, glassSaturation);
        BLOCK.set(ValueLayout.JAVA_FLOAT, GLASS_LIFT, GLASS_LIFT_VALUE);
        BLOCK.set(ValueLayout.JAVA_FLOAT, PANE_DEPTH, paneDepth);
        BlockTextureManager textures = BlockTextureManager.get();
        BLOCK.set(ValueLayout.JAVA_FLOAT, WATER_DISTANCE, textures != null ? textures.getWaterTransmittanceDistance() : 0.0f);
        RuntimePatchLayer.publishFrame(BLOCK, BYTES);
        BLOCK.fill((byte) 0);
    }

    private static void putFloats(int offset, float a, float b, float c, float d) {
        BLOCK.set(ValueLayout.JAVA_FLOAT, offset, a);
        BLOCK.set(ValueLayout.JAVA_FLOAT, offset + 4, b);
        BLOCK.set(ValueLayout.JAVA_FLOAT, offset + 8, c);
        BLOCK.set(ValueLayout.JAVA_FLOAT, offset + 12, d);
    }

    private static void putFloats(int offset, float[] source, int from) {
        putFloats(offset, source[from], source[from + 1], source[from + 2], source[from + 3]);
    }
}
