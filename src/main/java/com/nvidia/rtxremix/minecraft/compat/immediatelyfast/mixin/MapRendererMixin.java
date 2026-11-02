/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.compat.immediatelyfast.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.nvidia.rtxremix.minecraft.compat.immediatelyfast.MapTextureSource;
import com.nvidia.rtxremix.minecraft.entity.RemixSubmitCollector;
import net.minecraft.client.renderer.MapRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.MapRenderState;
import net.minecraft.resources.Identifier;
import net.raphimc.immediatelyfast.injection.interfaces.IMapRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(MapRenderer.class)
public abstract class MapRendererMixin {
    @ModifyExpressionValue(method = "extractRenderState", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/resources/MapTextureManager;prepareMapTexture(Lnet/minecraft/world/level/saveddata/maps/MapId;Lnet/minecraft/world/level/saveddata/maps/MapItemSavedData;)Lnet/minecraft/resources/Identifier;"))
    private Identifier rtxremix$rememberMapImage(Identifier texture,
            @Local(argsOnly = true) MapRenderState state) {
        ((MapTextureSource) state).rtxremix$sourceMapTexture(texture);
        return texture;
    }

    @WrapMethod(method = "render")
    private void rtxremix$captureSourceMap(MapRenderState state, PoseStack pose, SubmitNodeCollector collector,
                                         boolean showOnlyFrame, int lightCoords, Operation<Void> original) {
        Identifier source = ((MapTextureSource) state).rtxremix$sourceMapTexture();
        Identifier atlas = state.texture;
        if (!(collector instanceof RemixSubmitCollector) || source == null || source.equals(atlas)
                || !(state instanceof IMapRenderState mapped)
                || !atlas.equals(mapped.immediatelyFast$getAtlasTextureId())) {
            original.call(state, pose, collector, showOnlyFrame, lightCoords);
            return;
        }

        // The collector consumes immediately. The source id selects the original
        // pixels and vanilla UVs; the atlas and its raster batching remain intact.
        state.texture = source;
        try {
            original.call(state, pose, collector, showOnlyFrame, lightCoords);
        } finally {
            state.texture = atlas;
        }
    }
}
