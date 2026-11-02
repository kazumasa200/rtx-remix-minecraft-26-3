/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.sodium.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.nvidia.rtxremix.minecraft.terrain.capture.BlockDataQueue;
import com.nvidia.rtxremix.minecraft.terrain.sodium.SodiumCapture;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildBuffers;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildContext;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderCache;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.FluidRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderTask;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.SortBehavior;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.TranslucentGeometryCollector;
import net.caffeinemc.mods.sodium.client.util.task.CancellationToken;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.caffeinemc.mods.sodium.client.world.cloned.ChunkRenderContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures once in the existing Sodium block loop, keeping its metadata and job lifecycle. */
@Mixin(ChunkBuilderMeshingTask.class)
public abstract class SodiumMeshingTaskMixin extends ChunkBuilderTask<ChunkBuildOutput> {
    protected SodiumMeshingTaskMixin(RenderSection section, int submitTime, org.joml.Vector3dc camera) {
        super(section, submitTime, camera);
    }
    @Unique private boolean rtxremix$capturing;
    @Unique private BlockDataQueue.CaptureToken rtxremix$token;
    @Unique private SodiumCapture rtxremix$capture;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void rtxremix$latchCapture(CallbackInfo ci) {
        rtxremix$capturing = SodiumCapture.enabled();
        if (rtxremix$capturing) {
            rtxremix$token = BlockDataQueue.beginCapture(section.getChunkX(), section.getChunkZ());
        }
    }

    @WrapMethod(method = "execute(Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildContext;Lnet/caffeinemc/mods/sodium/client/util/task/CancellationToken;)Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildOutput;")
    private ChunkBuildOutput rtxremix$captureBuild(ChunkBuildContext context, CancellationToken cancelled,
                                                 Operation<ChunkBuildOutput> original) {
        try {
            ChunkBuildOutput output = original.call(context, cancelled);
            if (output != null && rtxremix$capture != null) rtxremix$capture.attach(output);
            return output;
        } finally {
            if (rtxremix$capture != null) { rtxremix$capture.close(); rtxremix$capture = null; }
        }
    }

    @WrapOperation(method = "execute", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/pipeline/BlockRenderCache;init(Lnet/caffeinemc/mods/sodium/client/world/cloned/ChunkRenderContext;)V"))
    private void rtxremix$beginCapture(BlockRenderCache cache, ChunkRenderContext context, Operation<Void> original) {
        original.call(cache, context);
        if (rtxremix$capturing) rtxremix$capture = new SodiumCapture(section.getPosition(), rtxremix$token, cache);
    }

    @ModifyExpressionValue(method = "execute", at = @At(value = "FIELD", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/tasks/ChunkBuilderMeshingTask;sortBehavior:Lnet/caffeinemc/mods/sodium/client/render/chunk/translucent_sorting/SortBehavior;"))
    private SortBehavior rtxremix$skipRasterSort(SortBehavior original) {
        return rtxremix$capturing ? SortBehavior.OFF : original;
    }

    @WrapOperation(method = "execute", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;getRenderShape()Lnet/minecraft/world/level/block/RenderShape;"))
    private RenderShape rtxremix$captureBlock(BlockState state, Operation<RenderShape> original,
                                            @Local(ordinal = 0) BlockPos.MutableBlockPos pos) {
        if (rtxremix$capture == null) return original.call(state);
        rtxremix$capture.block(state, pos);
        return RenderShape.INVISIBLE;
    }

    @WrapOperation(method = "execute", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/pipeline/FluidRenderer;render(Lnet/caffeinemc/mods/sodium/client/world/LevelSlice;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;Lnet/caffeinemc/mods/sodium/client/render/chunk/translucent_sorting/TranslucentGeometryCollector;Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildBuffers;)V"))
    private void rtxremix$captureFluid(FluidRenderer renderer, LevelSlice slice, BlockState state, FluidState fluid,
                                      BlockPos pos, BlockPos offset, TranslucentGeometryCollector collector,
                                      ChunkBuildBuffers buffers, Operation<Void> original) {
        if (rtxremix$capture == null) original.call(renderer, slice, state, fluid, pos, offset, collector, buffers);
        else rtxremix$capture.fluid(fluid, pos);
    }

    @WrapOperation(method = "execute", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/data/BuiltSectionInfo$Builder;build()Lnet/caffeinemc/mods/sodium/client/render/chunk/data/BuiltSectionInfo;"))
    private BuiltSectionInfo rtxremix$finishCapture(BuiltSectionInfo.Builder info, Operation<BuiltSectionInfo> original) {
        if (rtxremix$capture != null) rtxremix$capture.finish(info);
        return original.call(info);
    }
}
