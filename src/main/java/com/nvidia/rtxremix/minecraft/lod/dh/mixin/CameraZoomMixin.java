/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.lod.dh.mixin;

import com.nvidia.rtxremix.minecraft.lod.dh.DistantHorizonsCompat;
import com.nvidia.rtxremix.minecraft.lod.dh.ZoomHold;
import com.seibel.distanthorizons.core.render.CameraZoom;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While capturing, the zoom Distant Horizons raises its detail by passes through
 * {@link ZoomHold}: every change of it ends in this one setter.
 */
@Mixin(CameraZoom.class)
abstract class CameraZoomMixin {

    @Shadow public double magnification;
    @Shadow public double coneTanHalfAngle;
    @Shadow public double lookDirectionX;
    @Shadow public double lookDirectionZ;

    @Unique private final ZoomHold rtxremix$hold = new ZoomHold();

    @Inject(method = "set(DDDD)V", at = @At("HEAD"), cancellable = true)
    private void rtxremix$holdZoom(double magnification, double coneTanHalfAngle,
                                   double lookDirectionX, double lookDirectionZ, CallbackInfo ci) {
        if (!DistantHorizonsCompat.capturing()) return;
        rtxremix$hold.update(System.nanoTime(), magnification, coneTanHalfAngle, lookDirectionX, lookDirectionZ);
        this.magnification = rtxremix$hold.magnification();
        this.coneTanHalfAngle = rtxremix$hold.coneTanHalfAngle();
        this.lookDirectionX = rtxremix$hold.lookDirectionX();
        this.lookDirectionZ = rtxremix$hold.lookDirectionZ();
        ci.cancel();
    }
}
