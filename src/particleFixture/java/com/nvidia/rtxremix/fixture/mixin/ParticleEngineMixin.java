/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.fixture.mixin;

import com.nvidia.rtxremix.fixture.FixtureQuads;
import com.nvidia.rtxremix.fixture.ParticleFixture;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.ParticlesRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ParticleEngine.class)
public class ParticleEngineMixin {
    private static final FixtureQuads GOOD = new FixtureQuads();
    private static final FixtureQuads BROKEN = new FixtureQuads.Broken();
    private static final FixtureQuads AFTER = new FixtureQuads.AfterFailure();
    @Inject(method = "extract", at = @At("TAIL"))
    private void add(ParticlesRenderState state, Frustum frustum, Camera camera, float delta, CallbackInfo ci) {
        if (!ParticleFixture.enabled) return;
        state.add(GOOD);
        if (Boolean.getBoolean("rtxremix.particleFixture.fail")) { state.add(BROKEN); state.add(AFTER); }
    }
}
