/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.sodium;

import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.terrain.capture.BlockDataQueue;
import com.nvidia.rtxremix.minecraft.terrain.capture.BlockQuadEmitter;
import com.nvidia.rtxremix.minecraft.terrain.capture.SectionBlockData;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderCache;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/** CPU-only section capture sharing Remix's block/fluid emitter with the vanilla backend. */
public final class SodiumCapture implements AutoCloseable {
    public record Payload(SectionBlockData data, BlockDataQueue.CaptureToken token, long retainedBytes) implements AutoCloseable {
        @Override public void close() { data.meshData().release(); }
    }

    private final BlockQuadEmitter emitter = BlockQuadEmitter.threadLocal();
    private final SectionPos position;
    private final BlockDataQueue.CaptureToken token;
    private final LevelSlice slice;
    private Payload payload;

    /** Whether this renderer replaces raster terrain, independent of the world visibility toggle. */
    public static boolean enabled() {
        return RtxRemixMod.isRtxEnabled() && RtxRemixMod.renderer != null
                && RtxRemixMod.renderer.isInitialized();
    }

    public SodiumCapture(SectionPos position, BlockDataQueue.CaptureToken token, BlockRenderCache cache) {
        this.position = position;
        this.token = token;
        this.slice = cache.getWorldSlice();
        Minecraft client = Minecraft.getInstance();
        try {
            emitter.beginSection(position, RtxRemixMod.renderer.getTextureManager(), cache.getBlockModels(),
                    client.getModelManager().getFluidStateModelSet(), client.options.cutoutLeaves().get(), client.level);
        } catch (RuntimeException | Error failure) {
            emitter.abortSection();
            throw failure;
        }
    }

    public void block(BlockState state, BlockPos pos) {
        emitter.processBlock(state, slice, pos, state.isSolidRender());
        if (BlockQuadEmitter.getRenderTypeCached(state) == RenderShape.MODEL) emitter.emitBlock(state, pos, slice);
    }

    public void fluid(FluidState fluid, BlockPos pos) { emitter.emitFluid(pos, slice, fluid); }

    public void finish(BuiltSectionInfo.Builder info) {
        SectionBlockData data = emitter.finishSection(position);
        payload = new Payload(data, token, BlockDataQueue.estimateBytes(data));
        // Sodium's discovery tree needs the geometry flag even though the
        // corresponding vertex storage belongs to Remix rather than its GL arena.
        if (!data.meshData().isEmpty()) info.addRenderPass(DefaultTerrainRenderPasses.SOLID);
    }

    public void attach(ChunkBuildOutput output) {
        ((SodiumCaptureOwner) output).rtxremix$setCapture(payload);
        payload = null;
    }

    @Override public void close() {
        emitter.abortSection();
        if (payload != null) { payload.close(); payload = null; }
    }
}
