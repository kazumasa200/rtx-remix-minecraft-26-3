/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.entity.VanillaFeatureCapture;
import net.minecraft.client.renderer.feature.phase.FeatureRenderPhase;
import net.minecraft.client.renderer.feature.phase.SimpleFeatureRenderPhase;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tells {@link VanillaFeatureCapture} the order a capture's submits arrive in; vanilla keeps them grouped. */
@Mixin(SimpleFeatureRenderPhase.class)
public abstract class SimpleFeatureRenderPhaseMixin {

    @Inject(method = "submit", at = @At("HEAD"))
    private void rtxremix$recordOrder(SubmitNode submit, CallbackInfo ci) {
        VanillaFeatureCapture.submitted((FeatureRenderPhase<?>) (Object) this, submit);
    }
}
