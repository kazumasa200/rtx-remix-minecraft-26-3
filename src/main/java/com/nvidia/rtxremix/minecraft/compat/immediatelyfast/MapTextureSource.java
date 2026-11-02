/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.compat.immediatelyfast;

import net.minecraft.resources.Identifier;

/** The image-backed map identifier before atlas substitution. */
public interface MapTextureSource {
    Identifier rtxremix$sourceMapTexture();
    void rtxremix$sourceMapTexture(Identifier texture);
}
