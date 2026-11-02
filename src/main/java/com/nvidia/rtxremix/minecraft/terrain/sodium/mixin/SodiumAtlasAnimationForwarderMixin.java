/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.sodium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.nvidia.rtxremix.minecraft.texture.AtlasAnimationForwarder;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Raster visibility does not cover surfaces seen by reflected or indirect rays. */
@Mixin(AtlasAnimationForwarder.class)
public abstract class SodiumAtlasAnimationForwarderMixin {
    @WrapOperation(method = "addAnimatedSprite", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/texture/SpriteContents$AnimationState;needsToDraw()Z"))
    private static boolean rtxremix$forwardRayVisibleAnimation(SpriteContents.AnimationState state,
                                                               Operation<Boolean> original) {
        return state.animationInfo.interpolateFrames
                || ((SpriteAnimationStateAccessor) state).rtxremix$frameChanged();
    }
}
