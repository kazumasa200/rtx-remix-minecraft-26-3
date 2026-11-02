/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.RemixRenderer;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.opengl.GlSurface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces the OpenGL surface's buffer swap with Remix's present, so Vulkan owns the
 * swapchain whenever the renderer is initialized. The final texture's framebuffer is
 * retained for the existing copy to Remix; the parent window's default framebuffer is
 * not a reliable source beneath the Vulkan child. Before initialization, the SDL swap
 * runs unchanged.
 */
@Mixin(GlSurface.class)
public abstract class GlSurfaceMixin {

    @Inject(method = "blitFromTexture", at = @At("TAIL"))
    private void rtxremix$recordPresentationSource(CommandEncoderBackend encoder,
            GpuTextureView source, CallbackInfo ci) {
        RemixRenderer renderer = RtxRemixMod.renderer;
        if (renderer != null && renderer.isInitialized()) {
            renderer.setPresentationSource(
                    ((GlCommandEncoderAccessor) encoder).rtxremix$presentationSourceFramebuffer(),
                    source.getWidth(0), source.getHeight(0));
        }
    }

    @Inject(method = "present", at = @At("HEAD"), cancellable = true)
    private void rtxremix$presentThroughRemix(CallbackInfo ci) {
        RemixRenderer renderer = RtxRemixMod.renderer;
        if (renderer != null && renderer.isInitialized()) {
            renderer.presentFrameVk();
            ci.cancel();
        }
    }
}
