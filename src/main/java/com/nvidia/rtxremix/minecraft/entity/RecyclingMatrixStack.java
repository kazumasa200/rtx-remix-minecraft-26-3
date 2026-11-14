/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.mojang.blaze3d.vertex.PoseStack;


/**
 * Matrix stack dedicated to Remix entity capture.
 *
 * <p>In 26.3 vanilla's stack keeps its popped entries for reuse, so this stack
 * only adds what the capture still needs: a reset to the identity root after a
 * renderer threw mid-traversal.
 */
public final class RecyclingMatrixStack extends PoseStack {

    /** Restores the identity root after a normal render or renderer exception. */
    void reset() {
        while (!isEmpty()) {
            popPose();
        }
        setIdentity();
    }

}
