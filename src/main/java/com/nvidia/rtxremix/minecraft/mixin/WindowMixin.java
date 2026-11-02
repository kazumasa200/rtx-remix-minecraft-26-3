/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.platform.Window;
import com.nvidia.rtxremix.minecraft.RemixRenderer;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Keeps native window destruction ahead of the renderer's deferred shutdown. */
@Mixin(Window.class)
public abstract class WindowMixin {
    @WrapOperation(method = "close", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/sdl/SDLVideo;SDL_DestroyWindow(J)V", remap = false))
    private void rtxremix$destroyBeforeNativeShutdown(long window, Operation<Void> original) {
        RemixRenderer renderer = RtxRemixMod.renderer;
        if (renderer == null) {
            original.call(window);
        } else {
            renderer.destroySdlWindow(window, () -> original.call(window));
        }
    }
}
