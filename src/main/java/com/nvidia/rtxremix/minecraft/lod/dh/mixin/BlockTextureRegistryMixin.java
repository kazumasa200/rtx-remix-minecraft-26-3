/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.lod.dh.mixin;

import com.nvidia.rtxremix.minecraft.lod.dh.DistantHorizonsCompat;
import com.seibel.distanthorizons.core.dataObjects.render.textures.BlockTextureRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tells the capture when the LOD mod drops its tiles, so its copy of them starts over too. */
@Mixin(BlockTextureRegistry.class)
abstract class BlockTextureRegistryMixin {

    @Inject(method = "clear", at = @At("HEAD"))
    private void rtxremix$tilesCleared(CallbackInfo ci) {
        DistantHorizonsCompat.tilesCleared();
    }
}
