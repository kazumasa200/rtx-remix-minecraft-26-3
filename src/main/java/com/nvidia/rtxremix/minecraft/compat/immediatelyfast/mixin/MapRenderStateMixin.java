/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.compat.immediatelyfast.mixin;

import com.nvidia.rtxremix.minecraft.compat.immediatelyfast.MapTextureSource;
import net.minecraft.client.renderer.state.MapRenderState;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(MapRenderState.class)
public abstract class MapRenderStateMixin implements MapTextureSource {
    @Unique private Identifier rtxremix$sourceMapTexture;

    @Override public Identifier rtxremix$sourceMapTexture() { return rtxremix$sourceMapTexture; }
    @Override public void rtxremix$sourceMapTexture(Identifier texture) { rtxremix$sourceMapTexture = texture; }
}
