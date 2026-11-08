/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.lens;

import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.util.Arrays;

/**
 * Where the camera sees through stained glass, ice or water, for the lens to leave the sky seen
 * through them as the runtime draws it. The runtime keeps their tint in that sky and in its own
 * clouds there, and the lens's clouds drawn over it floated white; tinted from the image's hue,
 * whole clouds in open sky passed for seen through glass and turned cyan. The blocks say so
 * exactly.
 *
 * <p>One texel a block, {@link #SIZE} each way around the camera: a key naming the block, plus a
 * half where it is see-through. A texel holds the block whose coordinates are its own modulo
 * {@code SIZE}, so the window follows the camera without moving anything, and the key tells the
 * shader whether it still holds the block it asks for. One layer is measured each frame, so a
 * change shows within {@code SIZE} frames. Render thread only.
 */
final class GlassVolume {

    static final int SHIFT = 6;
    /** Blocks across the window each way; the lens reads half of it from the camera. */
    static final int SIZE = 1 << SHIFT;
    private static final int HALF = SIZE / 2;

    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private final FloatBuffer layer = MemoryUtil.memAllocFloat(SIZE * SIZE);
    private int buffer;
    private int texture;
    private int nextLayer;
    /** See-through blocks in each texel layer as last measured, and the height it was measured at. */
    private final int[] layerFound = new int[SIZE];
    private final int[] layerY = new int[SIZE];
    private int found;

    /** Measures the next layer of the window around the camera and uploads it. */
    void update(Level level, double cameraX, double cameraY, double cameraZ) {
        BlockTextureManager textures = BlockTextureManager.get();
        if (textures == null) {
            return;
        }
        if (texture == 0) {
            buffer = GL45C.glCreateBuffers();
            GL45C.glNamedBufferStorage(buffer, (long) SIZE * SIZE * SIZE * Float.BYTES, GL45C.GL_DYNAMIC_STORAGE_BIT);
            GL45C.glClearNamedBufferData(buffer, GL45C.GL_R32F, GL45C.GL_RED, GL45C.GL_FLOAT, (FloatBuffer) null);
            texture = GL45C.glCreateTextures(GL45C.GL_TEXTURE_BUFFER);
            GL45C.glTextureBuffer(texture, GL45C.GL_R32F, buffer);
        }
        int left = Mth.floor(cameraX) - HALF;
        int bottom = Mth.floor(cameraY) - HALF;
        int back = Mth.floor(cameraZ) - HALF;
        int texelY = nextLayer;
        nextLayer = (nextLayer + 1) % SIZE;
        int y = bottom + Math.floorMod(texelY - bottom, SIZE);
        BlockStateModelSet models = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
        boolean inWorld = y >= level.getMinY() && y <= level.getMaxY();
        layer.clear();
        int inLayer = 0;
        for (int texelZ = 0; texelZ < SIZE; texelZ++) {
            int z = back + Math.floorMod(texelZ - back, SIZE);
            for (int texelX = 0; texelX < SIZE; texelX++) {
                int x = left + Math.floorMod(texelX - left, SIZE);
                boolean seeThrough = false;
                if (inWorld) {
                    pos.set(x, y, z);
                    seeThrough = seeThrough(level.getBlockState(pos), models, textures);
                }
                inLayer += seeThrough ? 1 : 0;
                layer.put(key(x, y, z) + (seeThrough ? 0.5f : 0.0f));
            }
        }
        found += inLayer - layerFound[texelY];
        layerFound[texelY] = inLayer;
        layerY[texelY] = y;
        layer.flip();
        GL45C.glNamedBufferSubData(buffer, (long) texelY * SIZE * SIZE * Float.BYTES, layer);
    }

    /** Water, or a block terrain draws as see-through translucent, as it does stained glass and ice. */
    private static boolean seeThrough(BlockState state, BlockStateModelSet models, BlockTextureManager textures) {
        if (state.isAir()) {
            return false;
        }
        if (state.getFluidState().is(FluidTags.WATER)) {
            return true;
        }
        BlockStateModel model = models.get(state);
        return model.hasMaterialFlag(BakedQuad.FLAG_TRANSLUCENT) && textures.hasSeeThroughQuads(state, model);
    }

    /** Whether the window held any see-through block when last measured. */
    boolean any() {
        return found > 0;
    }

    /** The lowest block height holding a see-through block when last measured; valid while {@link #any()}. */
    int lowest() {
        int lowest = Integer.MAX_VALUE;
        for (int i = 0; i < SIZE; i++) {
            if (layerFound[i] > 0) {
                lowest = Math.min(lowest, layerY[i]);
            }
        }
        return lowest;
    }

    /** The highest block height holding a see-through block when last measured; valid while {@link #any()}. */
    int highest() {
        int highest = Integer.MIN_VALUE;
        for (int i = 0; i < SIZE; i++) {
            if (layerFound[i] > 0) {
                highest = Math.max(highest, layerY[i]);
            }
        }
        return highest;
    }

    /** The buffer texture of the window, layer after layer, row after row, of {@link #SIZE} texels. */
    int texture() {
        return texture;
    }

    void delete() {
        if (texture != 0) {
            GL45C.glDeleteTextures(texture);
            GL45C.glDeleteBuffers(buffer);
            texture = 0;
            buffer = 0;
        }
        nextLayer = 0;
        Arrays.fill(layerFound, 0);
        found = 0;
    }

    /** The key a texel holding block x, y, z carries; the shader computes the same. */
    static float key(int x, int y, int z) {
        return (((x >> SHIFT) & 63) << 12 | ((y >> SHIFT) & 63) << 6 | ((z >> SHIFT) & 63)) + 1;
    }
}
