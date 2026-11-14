/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nvidia.rtxremix.minecraft.entity.HeldItemEffectsRenderer;
import com.nvidia.rtxremix.minecraft.entity.ItemRenderSource;
import com.nvidia.rtxremix.minecraft.entity.RemixVertexConsumerProvider;
import com.nvidia.rtxremix.minecraft.entity.VanillaFeatureCapture;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.resources.model.cuboid.ItemTransform;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds the lit element of a hand-held item right after its first baked layer, while
 * the pose stack holds the item's block-space pose. Running here rather than in a
 * hand-specific renderer covers every hand-held render with one hook.
 */
@Mixin(ItemStackRenderState.LayerRenderState.class)
public abstract class ItemLayerRenderStateMixin {

    @Shadow @Final ItemStackRenderState this$0;
    @Shadow private ItemTransform itemTransform;

    @Inject(
            method = "submit",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitItem(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/item/ItemDisplayContext;III[ILnet/minecraft/client/resources/model/geometry/ItemQuads;Lnet/minecraft/client/renderer/item/ItemStackRenderState$FoilType;)V",
                    shift = At.Shift.AFTER))
    private void rtxremix$submitHeldEffects(PoseStack poseStack, SubmitNodeCollector collector, int lightCoords,
                                            int overlayCoords, int outlineColor, CallbackInfo ci) {
        RemixVertexConsumerProvider provider = VanillaFeatureCapture.provider(collector);
        if (provider == null) {
            return;
        }
        ItemStackRenderState owner = this$0;
        ItemStack stack = ((ItemRenderSource) owner).rtxremix$stack();
        if (stack == null || ((ItemStackRenderStateAccessor) owner).rtxremix$firstLayer() != (Object) this) {
            return;
        }
        HeldItemEffectsRenderer.render(stack, ((ItemStackRenderStateAccessor) owner).rtxremix$displayContext(),
                ((ItemRenderSource) owner).rtxremix$holder(), poseStack, provider, itemTransform.scale().x());
    }
}
