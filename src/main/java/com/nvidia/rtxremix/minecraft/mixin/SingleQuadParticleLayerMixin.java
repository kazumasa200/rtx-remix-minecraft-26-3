/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.texture.ParticleAtlasDependencies;
import net.minecraft.client.particle.SingleQuadParticle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SingleQuadParticle.Layer.class)
public class SingleQuadParticleLayerMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void rtxremix$declareAtlas(CallbackInfo ci) {
        ParticleAtlasDependencies.register(((SingleQuadParticle.Layer) (Object) this).textureAtlasLocation());
    }
}
