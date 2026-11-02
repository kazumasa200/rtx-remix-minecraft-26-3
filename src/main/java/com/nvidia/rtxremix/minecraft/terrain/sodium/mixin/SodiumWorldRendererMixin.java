/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.sodium.mixin;
import com.nvidia.rtxremix.minecraft.terrain.TerrainBackendProvider;
import com.nvidia.rtxremix.minecraft.terrain.sodium.SodiumCapture;
import com.nvidia.rtxremix.minecraft.terrain.sodium.SodiumTerrainBackend;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(SodiumWorldRenderer.class)
public abstract class SodiumWorldRendererMixin {
    @Shadow private RenderSectionManager renderSectionManager;
    @Inject(method = "reload", at = @At("RETURN"))
    private void rtxremix$applyRecapture(CallbackInfo ci) {
        var provider = (TerrainBackendProvider) Minecraft.getInstance().levelRenderer;
        ((SodiumTerrainBackend) provider.rtxremix$terrainBackend()).applyPending(renderSectionManager);
    }
    @Inject(method = "prepareChunkRendering", at = @At("HEAD"), cancellable = true)
    private void rtxremix$skipRasterPreparation(CallbackInfo ci) {
        if (SodiumCapture.enabled()) ci.cancel();
    }
}
