/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.compat.figura.mixin;

import com.nvidia.rtxremix.minecraft.compat.figura.FiguraCapture;
import com.nvidia.rtxremix.minecraft.entity.RemixSubmitCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import org.figuramc.figura.model.rendering.nodeRenderer.FiguraSubmission;
import org.figuramc.figura.utils.FiguraSubmitUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Figura submits an avatar only to vanilla's own storage and drops it for any
 * other collector, so the Remix capture drew the vanilla player and no avatar.
 */
@Mixin(value = FiguraSubmitUtils.class, remap = false)
abstract class FiguraSubmitUtilsMixin {

    @Inject(method = "submit", at = @At("HEAD"), cancellable = true)
    private static void rtxremix$drawIntoCapture(SubmitNodeCollector collector, FiguraSubmission submission,
                                                 CallbackInfo ci) {
        if (collector instanceof RemixSubmitCollector capture) {
            FiguraCapture.draw(capture.provider(), submission);
            ci.cancel();
        }
    }
}
