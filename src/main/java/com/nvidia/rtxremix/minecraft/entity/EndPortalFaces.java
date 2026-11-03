/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import org.joml.Vector3f;
import net.minecraft.util.LightCoordsUtil;

/**
 * The faces of an End portal or gateway, drawn as vanilla's shader layers them. Vanilla sends
 * only their positions and builds the starfield in its shader from where they fall on screen,
 * which Remix cannot run, so they drew as dark planes (#19). Here the dark sky glows in vanilla's
 * first colour over an opaque black face, and the star texture glows over it in layers that
 * vanilla's later colours tint, each scaled, turned and drifting with the day as vanilla's are,
 * but laid along the face in world space. Each layer lies a hair outside the one below, since a
 * coplanar stack has no order in the runtime; there are few of them because every one is a
 * blended hit the runtime's primary rays must pass through.
 *
 * <p>Vanilla's starfield reads as deep because its shader lays it out from the screen. A portal's
 * top face therefore sinks its layers into the block, each to its own depth, so they part as
 * the camera moves; its bottom face moves to the block's floor, where it is the black those
 * layers lie over. A gateway is a cube, through whose sides sunken layers would show the world
 * beyond, so it stays flat.
 */
final class EndPortalFaces implements VertexConsumer {

    /** Vanilla's colours for the sky (first) and the star layers it adds over it. */
    private static final float[][] COLOURS = {
            {0.022087f, 0.098399f, 0.110818f},
            {0.070006f, 0.243332f, 0.235792f},
            {0.196766f, 0.142899f, 0.214696f},
            {0.047281f, 0.315338f, 0.321970f},
            {0.204675f, 0.390010f, 0.302066f},
            {0.080955f, 0.314821f, 0.661491f},
    };
    /** Vanilla's layer index for each star colour above, which sets its scale, turn and drift. */
    private static final int[] LAYER_INDEX = {0, 12, 13, 14, 15, 16};
    private static final float LAYER_LIFT = 1f / 1024f;
    /** Star texture tiles per block per unit of vanilla's layer scale. */
    private static final float WORLD_SCALE = 0.08f;
    /** Vanilla's colours scaled for the runtime's emissive brightness, which lit the sky grey at 1. */
    private static final float STAR_GLOW = 4f;
    private static final float SKY_GLOW = 0.5f;
    /** World positions wrap at this many blocks before scaling, keeping texture coordinates small. */
    private static final int WRAP = 1024;
    /** Blocks the portal's sky sinks below its top face; its star layers lie between. */
    private static final float SUNKEN_DEPTH = 0.7f;
    /** Blocks the portal's bottom face moves down, from 0.375 to just above the block's floor. */
    private static final float FLOOR_DROP = 0.36f;

    private final VertexConsumer black;
    private final VertexConsumer sky;
    private final VertexConsumer stars;
    /** Vanilla's game time: the day's fraction, which drives the drift. */
    private final float time;
    /** The block's position, wrapped, so the texture runs on across neighbouring blocks. */
    private final float[] origin;
    /** A portal, whose top face sinks its layers; a gateway stays flat. */
    private final boolean sunken;
    private final float[] positions = new float[12];
    private final Vector3f scratch = new Vector3f();
    private PoseStack.Pose pose;
    /** Local units per block along the current face's normal, for the pose's scale. */
    private float localPerBlock;
    private int count;

    EndPortalFaces(VertexConsumer black, VertexConsumer sky, VertexConsumer stars, float time,
                   BlockPos block, boolean sunken) {
        this.black = black;
        this.sky = sky;
        this.stars = stars;
        this.time = time;
        this.sunken = sunken;
        this.origin = new float[] {
                Math.floorMod(block.getX(), WRAP), Math.floorMod(block.getY(), WRAP),
                Math.floorMod(block.getZ(), WRAP)};
    }

    @Override
    public VertexConsumer addVertex(PoseStack.Pose pose, float x, float y, float z) {
        this.pose = pose;
        positions[count * 3] = x;
        positions[count * 3 + 1] = y;
        positions[count * 3 + 2] = z;
        if (++count == 4) {
            emitQuad();
            count = 0;
        }
        return this;
    }

    private void emitQuad() {
        float ax = positions[3] - positions[0], ay = positions[4] - positions[1], az = positions[5] - positions[2];
        float bx = positions[9] - positions[0], by = positions[10] - positions[1], bz = positions[11] - positions[2];
        float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
        float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length > 0) {
            nx /= length;
            ny /= length;
            nz /= length;
        }
        // The texture runs along the two axes the face lies in.
        int normalAxis = Math.abs(nx) >= Math.abs(ny) && Math.abs(nx) >= Math.abs(nz) ? 0
                : Math.abs(ny) >= Math.abs(nz) ? 1 : 2;
        int uAxis = normalAxis == 0 ? 2 : 0;
        int vAxis = normalAxis == 1 ? 2 : 1;
        // Vanilla scales its unit cube down to the portal's slab, so a block along the normal
        // is more than one local unit.
        float blocks = pose.pose().transformDirection(nx, ny, nz, scratch).length();
        localPerBlock = blocks > 0 ? 1f / blocks : 1f;

        boolean top = sunken && ny > 0.5f;
        float base = sunken && ny < -0.5f ? FLOOR_DROP : 0f;
        int layers = COLOURS.length - 1;
        if (!top) emit(black, base, 0xFF000000, 1, 0, 0, 0, nx, ny, nz, uAxis, vAxis);
        emit(sky, top ? -SUNKEN_DEPTH : base + LAYER_LIFT, colour(0), 1, 0, 0, 0,
                nx, ny, nz, uAxis, vAxis);
        for (int i = 1; i < COLOURS.length; i++) {
            float layer = LAYER_INDEX[i];
            float scale = (4.5f - layer / 4f) * 2f * WORLD_SCALE;
            double angle = Math.toRadians((layer * layer * 4321.0 + layer * 9.0) * 2.0);
            float shiftU = 17f / layer;
            float shiftV = (2f + layer / 1.5f) * (time * 1.5f);
            // On a portal's top the brighter, later layers lie nearer the face.
            float offset = top ? -SUNKEN_DEPTH * (layers + 1 - i) / (layers + 1)
                    : base + (i + 1) * LAYER_LIFT;
            emit(stars, offset, colour(i), scale, (float) angle, shiftU, shiftV,
                    nx, ny, nz, uAxis, vAxis);
        }
    }

    private static int colour(int i) {
        float[] c = COLOURS[i];
        float glow = i == 0 ? SKY_GLOW : STAR_GLOW;
        return 0xFF000000 | channel(c[0], glow) << 16 | channel(c[1], glow) << 8 | channel(c[2], glow);
    }

    private static int channel(float value, float glow) {
        return Math.min(255, Math.round(value * glow * 255f));
    }

    /** Emits the quad {@code offset} blocks out along its normal, or in when negative. */
    private void emit(VertexConsumer out, float offset, int colour, float scale, float angle,
                      float shiftU, float shiftV, float nx, float ny, float nz, int uAxis, int vAxis) {
        float lift = offset * localPerBlock;
        float cos = (float) Math.cos(angle), sin = (float) Math.sin(angle);
        for (int v = 0; v < 4; v++) {
            float a = (positions[v * 3 + uAxis] + origin[uAxis] + shiftU) * scale;
            float b = (positions[v * 3 + vAxis] + origin[vAxis] + shiftV) * scale;
            out.addVertex(pose,
                            positions[v * 3] + nx * lift,
                            positions[v * 3 + 1] + ny * lift,
                            positions[v * 3 + 2] + nz * lift)
                    .setColor(colour)
                    .setUv(a * cos - b * sin, a * sin + b * cos)
                    .setOverlay(OverlayTexture.NO_OVERLAY)
                    .setLight(LightCoordsUtil.FULL_BRIGHT)
                    .setNormal(pose, nx, ny, nz);
        }
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        throw new UnsupportedOperationException("End portal faces arrive with their pose");
    }

    @Override public VertexConsumer setColor(int red, int green, int blue, int alpha) { return this; }
    @Override public VertexConsumer setColor(int argb) { return this; }
    @Override public VertexConsumer setUv(float u, float v) { return this; }
    @Override public VertexConsumer setUv1(int u, int v) { return this; }
    @Override public VertexConsumer setUv2(int u, int v) { return this; }
    @Override public VertexConsumer setUv3(float u, float v) { return this; }
    @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
    @Override public VertexConsumer setLineWidth(float width) { return this; }
}
