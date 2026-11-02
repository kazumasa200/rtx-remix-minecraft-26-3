/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.sodium.mixin;
import com.nvidia.rtxremix.minecraft.terrain.sodium.SodiumCapture;
import net.caffeinemc.mods.sodium.client.util.SodiumChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(SodiumChunkSection.class)
public abstract class SodiumChunkSectionMixin {
    @Inject(method = {"renderGroup", "renderOit"}, at = @At("HEAD"), cancellable = true)
    private void rtxremix$skipRasterTerrain(CallbackInfo ci) {
        if (SodiumCapture.enabled()) ci.cancel();
    }
}
