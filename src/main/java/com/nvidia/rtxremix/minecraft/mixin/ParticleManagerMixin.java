/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.nvidia.rtxremix.minecraft.particle.RemixParticleRenderer;
import com.nvidia.rtxremix.minecraft.profiling.ParticleTickProfiler;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleGroup;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.ParticleGroupRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * Coarse particle ticking markers. These intentionally avoid per-particle
 * zones because the benchmark scene can tick thousands of particles per frame.
 */
@Mixin(ParticleEngine.class)
public abstract class ParticleManagerMixin {
    @Shadow @Final private Map<ParticleRenderType, ParticleGroup<?>> particles;

    private static final ThreadLocal<ProfileScope> rtxremix$tickScope = new ThreadLocal<>();

    @Inject(method = "tick", at = @At("HEAD"), require = 0)
    private void rtxremix$tickHead(CallbackInfo ci) {
        ParticleTickProfiler.beginTick();
        int activeParticles = 0;
        int activeSheets = 0;
        for (ParticleGroup<?> group : particles.values()) {
            int size = ((ParticleGroupAccessor) group).rtxremix$particles().size();
            activeParticles += size;
            if (size > 0) activeSheets++;
        }
        rtxremix$openScope(rtxremix$tickScope, ProfileScope.beginJfrOnly("ParticleManager.tick")
                .captureCount(activeParticles)
                .layerCount(activeSheets)
                .plotOnClose("Java.ParticleManager.tick.ms"));
    }

    @Inject(method = "tick", at = @At("RETURN"), require = 0)
    private void rtxremix$tickReturn(CallbackInfo ci) {
        ParticleTickProfiler.Stats stats = ParticleTickProfiler.current();
        ProfileScope tickScope = rtxremix$tickScope.get();
        if (tickScope != null) {
            tickScope.collisionChecks(stats.collisionChecks())
                    .collisionSkips(stats.skippedCollisionChecks())
                    .collisionNanos(stats.collisionNanos());
            ProfileScope.plot("Java.ParticleManager.collisionChecks", stats.collisionChecks());
            ProfileScope.plot("Java.ParticleManager.collisionSkips", stats.skippedCollisionChecks());
            ProfileScope.plot("Java.ParticleManager.collisionMs", stats.collisionNanos() / 1_000_000.0);
        }
        rtxremix$closeScope(rtxremix$tickScope);
    }

    @Inject(method = "extract", at = @At("HEAD"))
    private void rtxremix$forgetRemixDrawnState(CallbackInfo ci) {
        RemixParticleRenderer.noteRemixDrawnState(null);
    }

    /** Remix draws the single-quad group's particles; every other group keeps its vanilla submit. */
    @WrapOperation(
            method = "extract",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/particle/ParticleGroup;extractRenderState(Lnet/minecraft/client/renderer/culling/Frustum;Lnet/minecraft/client/Camera;F)Lnet/minecraft/client/renderer/state/level/ParticleGroupRenderState;"
            ))
    private ParticleGroupRenderState rtxremix$noteRemixDrawnState(
            ParticleGroup<?> group, Frustum frustum, Camera camera, float partialTick,
            Operation<ParticleGroupRenderState> original) {
        ParticleGroupRenderState state = original.call(group, frustum, camera, partialTick);
        if (group == particles.get(ParticleRenderType.SINGLE_QUADS)) {
            RemixParticleRenderer.noteRemixDrawnState(state);
        }
        return state;
    }

    private static void rtxremix$openScope(ThreadLocal<ProfileScope> holder, ProfileScope scope) {
        rtxremix$closeScope(holder);
        holder.set(scope);
    }

    private static void rtxremix$closeScope(ThreadLocal<ProfileScope> holder) {
        ProfileScope scope = holder.get();
        if (scope != null) {
            holder.remove();
            scope.close();
        }
    }
}
