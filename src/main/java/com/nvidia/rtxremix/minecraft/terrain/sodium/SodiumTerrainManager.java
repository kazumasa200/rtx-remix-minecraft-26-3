/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.sodium;

import it.unimi.dsi.fastutil.longs.LongSet;

/** Rebuild access to the resident sections owned by one Sodium manager. */
public interface SodiumTerrainManager {
    LongSet rtxremix$builtSections();
    boolean rtxremix$requestRebuild(long section);
}
