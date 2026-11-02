/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reuses the framebuffer already attached to the final presentation texture. */
@Mixin(targets = "com.mojang.renderpearl.backend.opengl.GlCommandEncoder")
public interface GlCommandEncoderAccessor {
    @Accessor("drawFbo")
    int rtxremix$presentationSourceFramebuffer();
}
