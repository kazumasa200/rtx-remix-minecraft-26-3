/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.entity.VanillaFeatureCapture;
import net.minecraft.client.renderer.feature.phase.FeatureRenderPhase;
import net.minecraft.client.renderer.feature.phase.TranslucentFeatureRenderPhase;
import net.minecraft.client.renderer.feature.submit.TranslucentSubmit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tells {@link VanillaFeatureCapture} the order a capture's submits arrive in; vanilla keeps them grouped. */
@Mixin(TranslucentFeatureRenderPhase.class)
public abstract class TranslucentFeatureRenderPhaseMixin {

    @Inject(method = "submit", at = @At("HEAD"))
    private void rtxremix$recordOrder(TranslucentSubmit submit, CallbackInfo ci) {
        VanillaFeatureCapture.submitted((FeatureRenderPhase<?>) (Object) this, submit);
    }
}
