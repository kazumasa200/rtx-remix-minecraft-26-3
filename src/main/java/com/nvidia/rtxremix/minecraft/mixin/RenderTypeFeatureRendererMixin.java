/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nvidia.rtxremix.minecraft.entity.VanillaFeatureCapture;
import java.util.List;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The one place a feature renderer takes its vertex builder from. While
 * {@link VanillaFeatureCapture} drains a capture, the builder is the capture's,
 * so vanilla's and other mods' feature renderers write into it unchanged.
 */
@Mixin(RenderTypeFeatureRenderer.class)
public abstract class RenderTypeFeatureRendererMixin<Submit extends SubmitNode> implements VanillaFeatureCapture.GroupBuilder {

    @Shadow
    protected abstract void buildGroup(FeatureFrameContext context, List<Submit> submits);

    @Inject(method = "getVertexBuilder", at = @At("HEAD"), cancellable = true)
    private void rtxremix$captureVertices(RenderType renderType, CallbackInfoReturnable<VertexConsumer> cir) {
        VertexConsumer capture = VanillaFeatureCapture.vertexBuilder(renderType);
        if (capture != null) {
            cir.setReturnValue(capture);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void rtxremix$buildGroup(FeatureFrameContext context, List<SubmitNode> submits) {
        buildGroup(context, (List<Submit>) submits);
    }
}
