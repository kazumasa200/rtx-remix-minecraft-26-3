/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.lod.dh.mixin;

import com.seibel.distanthorizons.core.dataObjects.render.textures.BlockTextureRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.ArrayList;

/** The LOD mod's block-face tiles, read under the registry's own lock. */
@Mixin(BlockTextureRegistry.class)
public interface BlockTextureRegistryAccessor {
    @Accessor("tilePixelsById")
    ArrayList<byte[]> rtxremix$tilePixels();
}
