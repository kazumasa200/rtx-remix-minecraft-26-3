/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import net.minecraft.world.entity.Entity;

/** Logical exclusions apply to world capture even outside the camera's frustum. */
public interface EntityCaptureVisibility {
    EntityCaptureVisibility VISIBLE = new EntityCaptureVisibility() {
        @Override public boolean isEntityHidden(Entity entity) { return false; }
        @Override public boolean isStateHidden(boolean avatar) { return false; }
    };

    boolean isEntityHidden(Entity entity);

    /** Whether an extracted state, or a pose recorded from that state, is excluded. */
    boolean isStateHidden(boolean avatar);
}
