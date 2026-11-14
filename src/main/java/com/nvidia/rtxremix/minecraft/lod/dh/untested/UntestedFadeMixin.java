/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.lod.dh.untested;

import com.nvidia.rtxremix.minecraft.lod.dh.DistantHorizonsRasterHold;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The one internal hook kept for a Distant Horizons release the capture does not support:
 * its fade of vanilla's terrain, which the public API cannot switch off. Named by string and
 * with {@code require = 0}, so a release that renamed the class or the methods loads all the
 * same and only brings the black fade back.
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.api.internal.ClientApi", remap = false)
abstract class UntestedFadeMixin {

    @Inject(method = {"renderFadeOpaque", "renderFadeTransparent"}, at = @At("HEAD"), cancellable = true,
            require = 0, remap = false)
    private void rtxremix$skipFade(CallbackInfo ci) {
        if (DistantHorizonsRasterHold.held()) ci.cancel();
    }
}
