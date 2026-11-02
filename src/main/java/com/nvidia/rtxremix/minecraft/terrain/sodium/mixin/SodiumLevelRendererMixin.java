/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.sodium.mixin;
import com.nvidia.rtxremix.minecraft.terrain.TerrainBackendProvider;
import com.nvidia.rtxremix.minecraft.terrain.WorldRendererTerrainAccess;
import com.nvidia.rtxremix.minecraft.terrain.sodium.SodiumTerrainBackend;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
@Mixin(LevelRenderer.class)
public abstract class SodiumLevelRendererMixin implements TerrainBackendProvider {
    @Unique private final SodiumTerrainBackend rtxremix$backend = new SodiumTerrainBackend();
    @Override public WorldRendererTerrainAccess rtxremix$terrainBackend() { return rtxremix$backend; }
}
