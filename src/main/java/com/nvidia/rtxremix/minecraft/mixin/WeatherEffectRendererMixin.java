/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.nvidia.rtxremix.minecraft.RemixRenderer;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.weather.RainThroughLeaves;
import com.nvidia.rtxremix.minecraft.weather.RemixWeatherRenderer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.state.level.WeatherRenderState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;

/**
 * Copies the weather vertices vanilla builds each frame for {@link RemixWeatherRenderer},
 * which draws them over the Remix image; vanilla's own draw is skipped in
 * {@link WorldRendererMixin} because the Remix image covers it.
 */
@Mixin(WeatherEffectRenderer.class)
public abstract class WeatherEffectRendererMixin {

    /** Runs before vanilla's early return for a frame without weather, so no stale columns are drawn. */
    @Inject(method = "prepare", at = @At("HEAD"))
    private void rtxremix$beginWeatherCapture(Vec3 cameraPos, WeatherRenderState state, CallbackInfo ci) {
        RemixWeatherRenderer weather = rtxremix$weather();
        if (weather != null) {
            weather.beginCapture();
        }
    }

    @WrapOperation(
            method = "prepare",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/WeatherEffectRenderer;uploadVertexBuffer(Ljava/nio/ByteBuffer;)V"
            ))
    private void rtxremix$captureWeatherVertices(
            WeatherEffectRenderer self,
            ByteBuffer vertices,
            Operation<Void> original,
            @Local(argsOnly = true) WeatherRenderState state) {
        RemixWeatherRenderer weather = rtxremix$weather();
        if (weather != null) {
            weather.capture(vertices, state.rainColumns.size(), state.snowColumns.size());
        }
        original.call(self, vertices);
    }

    /** Lets some rain fall through leaves while RTX is on ({@link RainThroughLeaves}). */
    @WrapOperation(
            method = "extractRenderState",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/ClientLevel;getHeight(Lnet/minecraft/world/level/levelgen/Heightmap$Types;II)I"
            ))
    private int rtxremix$rainThroughLeaves(ClientLevel level, Heightmap.Types type, int x, int z,
                                           Operation<Integer> original) {
        int top = original.call(level, type, x, z);
        return RtxRemixMod.isRemixWorldRendering() ? RainThroughLeaves.columnTop(level, x, z, top) : top;
    }

    private static RemixWeatherRenderer rtxremix$weather() {
        RemixRenderer renderer = RtxRemixMod.renderer;
        return RtxRemixMod.isRemixWorldRendering() && renderer != null ? renderer.getWeatherRenderer() : null;
    }
}
