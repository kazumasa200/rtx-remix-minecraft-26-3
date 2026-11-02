/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.sodium.mixin;
import com.nvidia.rtxremix.minecraft.terrain.capture.BlockQuadEmitter;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Releases pooled per-thread capture state when Sodium retires a build context. */
@Mixin(ChunkBuildContext.class)
public abstract class SodiumBuildContextMixin {
    @Inject(method = "cleanup", at = @At("TAIL"))
    private void rtxremix$releaseWorkerCapture(CallbackInfo ci) { BlockQuadEmitter.releaseCurrent(); }
}
