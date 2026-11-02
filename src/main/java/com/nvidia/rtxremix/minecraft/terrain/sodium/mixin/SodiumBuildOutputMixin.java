/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.sodium.mixin;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.nvidia.rtxremix.minecraft.terrain.sodium.SodiumCapture;
import com.nvidia.rtxremix.minecraft.terrain.sodium.SodiumCaptureOwner;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(ChunkBuildOutput.class)
public abstract class SodiumBuildOutputMixin implements SodiumCaptureOwner {
    @Unique private SodiumCapture.Payload rtxremix$capture;
    @Unique private long rtxremix$captureBytes;
    @Override public void rtxremix$setCapture(SodiumCapture.Payload capture) {
        rtxremix$capture = capture;
        rtxremix$captureBytes = capture == null ? 0 : capture.retainedBytes();
    }
    @ModifyReturnValue(method = "calculateResultSize", at = @At("RETURN"))
    private long rtxremix$accountCapture(long original) { return original + rtxremix$captureBytes; }
    @Override public SodiumCapture.Payload rtxremix$takeCapture() {
        SodiumCapture.Payload result = rtxremix$capture;
        rtxremix$capture = null;
        return result;
    }
    @Inject(method = "destroy", at = @At("HEAD"))
    private void rtxremix$releaseRejectedCapture(CallbackInfo ci) {
        SodiumCapture.Payload capture = rtxremix$takeCapture();
        if (capture != null) capture.close();
    }
}
