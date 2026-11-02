/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.texture.GlyphPageMirror;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Font sets close pages directly as well as through TextureManager. */
@Mixin(AbstractTexture.class)
public abstract class AbstractTextureMixin {
    @Inject(method = "close", at = @At("HEAD"))
    private void rtxremix$releaseGlyphPixels(CallbackInfo ci) {
        if ((Object) this instanceof GlyphPageMirror.Owner page) {
            page.rtxremix$releaseGlyphPageMirror();
        }
    }
}
