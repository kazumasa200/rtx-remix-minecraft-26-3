/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.sodium;

/** Single-owner payload attached to a Sodium build result until adoption or rejection. */
public interface SodiumCaptureOwner {
    void rtxremix$setCapture(SodiumCapture.Payload capture);
    SodiumCapture.Payload rtxremix$takeCapture();
}
