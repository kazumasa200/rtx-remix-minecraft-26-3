/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.google.common.collect.ImmutableList;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nvidia.rtxremix.minecraft.entity.RemixCapturingConsumer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Iterator;
import java.util.List;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.SpriteCoordinateExpander;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;


/**
 * Spares the capture one allocation per single-cuboid part.
 *
 * <p>A part's own transform is vanilla's {@code translateAndRotate}, untouched. The capture
 * once replaced it with pooled arithmetic read from the part's fields, to save a quaternion
 * a part, and that dropped whatever another mod adds there: Entity Model Features' animation,
 * so a player kept vanilla's pose with RTX on (#29, #30).
 *
 * <p>{@code compile} on a single-cuboid part borrows the capturing consumer's
 * singleton iterator instead of allocating vanilla's. The consumer is found directly
 * or behind the {@link SpriteCoordinateExpander} that sprite-atlas models draw
 * through, the same one-step look-through {@code CuboidMixin} applies. Sprite-wrapped
 * parts therefore reuse the iterator too; a wrapper around anything else is left to
 * vanilla.
 */
@Mixin(ModelPart.class)
public abstract class ModelPartMixin {

    @WrapOperation(
            method = "compile(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/List;iterator()Ljava/util/Iterator;"))
    private Iterator<ModelPart.Cube> rtxremix$reuseSingletonCuboidIterator(
            List<ModelPart.Cube> cuboids,
            Operation<Iterator<ModelPart.Cube>> original,
            PoseStack.Pose entry,
            VertexConsumer vertexConsumer,
            int light,
            int overlay,
            int color) {
        VertexConsumer target = vertexConsumer instanceof SpriteCoordinateExpander wrapped
                        && wrapped.mapping() instanceof TextureAtlasSprite
                ? wrapped.delegate()
                : vertexConsumer;
        // Local reads first: the cuboid count and list type reject group and
        // multi-cuboid parts before the capture check pays for its thread-local lookup.
        if (target instanceof RemixCapturingConsumer consumer
                && cuboids.size() == 1
                && cuboids instanceof ImmutableList<?>
                && consumer.isRemixCaptureActive()) {
            Iterator<ModelPart.Cube> iterator =
                    consumer.acquireSingletonCuboidIterator(cuboids.getFirst());
            if (iterator != null) {
                return iterator;
            }
        }
        return original.call(cuboids);
    }
}
