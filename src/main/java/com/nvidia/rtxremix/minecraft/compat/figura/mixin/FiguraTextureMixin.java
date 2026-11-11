/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.compat.figura.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import com.nvidia.rtxremix.minecraft.texture.DynamicTextureCache;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import org.figuramc.figura.model.rendering.texture.FiguraTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * An avatar's texture exists only in memory and is no {@code DynamicTexture},
 * so nothing snapshotted its pixels and its layers had no material. Figura
 * loads it here first and again whenever a script has changed it.
 */
@Mixin(value = FiguraTexture.class, remap = false)
abstract class FiguraTextureMixin {

    @Shadow public abstract Identifier getLocation();

    @Inject(method = "doLoad", at = @At("HEAD"))
    private void rtxremix$snapshotPixels(NativeImage image, CallbackInfo ci) {
        DynamicTextureCache.onRegisterTexture(getLocation(), (AbstractTexture) (Object) this, image);
    }
}
