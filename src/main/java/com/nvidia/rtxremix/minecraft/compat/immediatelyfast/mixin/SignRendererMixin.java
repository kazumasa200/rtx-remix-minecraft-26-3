/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.compat.immediatelyfast.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.nvidia.rtxremix.minecraft.compat.immediatelyfast.SignTextGeometryCache;
import com.nvidia.rtxremix.minecraft.entity.RemixSubmitCollector;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.AbstractSignRenderer;
import net.minecraft.client.renderer.blockentity.state.SignRenderState;
import net.minecraft.world.level.block.entity.SignText;
import net.raphimc.immediatelyfast.injection.interfaces.ISignText;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Cached source glyphs keep text depth and coverage in the ray-traced scene. */
@Mixin(AbstractSignRenderer.class)
public abstract class SignRendererMixin {
    @Shadow @Final private Font font;

    @WrapMethod(method = "submitSignText")
    private void rtxremix$cacheSourceSignText(SignRenderState state, PoseStack pose,
            SubmitNodeCollector collector, SignText text, Operation<Void> original) {
        if (collector instanceof RemixSubmitCollector remix
                && text instanceof ISignText cached && cached.immediatelyFast$shouldCache()) {
            SignTextGeometryCache.render(font, state, pose, remix.provider(), text);
        } else {
            original.call(state, pose, collector, text);
        }
    }
}
