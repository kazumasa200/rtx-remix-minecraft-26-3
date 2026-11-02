/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.sodium.mixin;

import com.nvidia.rtxremix.minecraft.terrain.SectionLeftRenderSetQueue;
import com.nvidia.rtxremix.minecraft.terrain.capture.BlockDataQueue;
import com.nvidia.rtxremix.minecraft.terrain.sodium.SodiumCapture;
import com.nvidia.rtxremix.minecraft.terrain.sodium.SodiumCaptureOwner;
import com.nvidia.rtxremix.minecraft.terrain.sodium.SodiumTerrainManager;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkUpdateTypes;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkJobCollector;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.estimation.UploadResourceBudget;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import net.caffeinemc.mods.sodium.client.render.chunk.storage.SectionStorage;
import net.caffeinemc.mods.sodium.client.world.cloned.ClonedChunkSectionCache;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Adopts only accepted results and reuses Sodium's deferred build budgets for recapture. */
@Mixin(RenderSectionManager.class)
public abstract class SodiumSectionManagerMixin implements SodiumTerrainManager {
    @Shadow @Final private SectionStorage renderSections;
    @Shadow @Final private ClonedChunkSectionCache sectionCache;
    @Shadow protected abstract boolean upgradePendingUpdate(RenderSection section, int updateType);
    @Shadow protected abstract void submitSectionTask(ChunkJobCollector collector, RenderSection section, UploadResourceBudget budget);
    @Unique private final LongSet rtxremix$built = new LongOpenHashSet();
    @Unique private final LongSet rtxremix$recapture = new LongOpenHashSet();

    @Override public LongSet rtxremix$builtSections() { return new LongOpenHashSet(rtxremix$built); }

    @Override public boolean rtxremix$requestRebuild(long position) {
        RenderSection section = renderSections.getConsistent(position);
        if (section == null || section.isDisposed()) return false;
        sectionCache.invalidate(position);
        upgradePendingUpdate(section, ChunkUpdateTypes.REBUILD);
        rtxremix$recapture.add(position);
        return true;
    }

    @Inject(method = "submitDeferredSectionTasks", at = @At("HEAD"))
    private void rtxremix$submitRecapture(ChunkJobCollector deferred, UploadResourceBudget budget, CallbackInfo ci) {
        if (rtxremix$recapture.isEmpty()) return;
        var iterator = rtxremix$recapture.iterator();
        while (iterator.hasNext() && deferred.hasBudgetRemaining() && budget.isAvailable()) {
            RenderSection section = renderSections.getConsistent(iterator.nextLong());
            iterator.remove();
            if (section != null && !section.isDisposed()) submitSectionTask(deferred, section, budget);
        }
    }

    @Inject(method = "updateWithResult", at = @At("HEAD"))
    private void rtxremix$adoptCapture(CallbackInfoReturnable<Integer> ci, @com.llamalad7.mixinextras.sugar.Local(argsOnly = true) ChunkBuildOutput output) {
        if (output.info == BuiltSectionInfo.EMPTY) rtxremix$built.remove(output.section.getPosition().asLong());
        else rtxremix$built.add(output.section.getPosition().asLong());
        SodiumCapture.Payload payload = ((SodiumCaptureOwner) output).rtxremix$takeCapture();
        if (payload != null) BlockDataQueue.offer(payload.data(), payload.token(), payload.retainedBytes());
        else if (SodiumCapture.enabled() && output.info == BuiltSectionInfo.EMPTY) {
            SectionLeftRenderSetQueue.enqueueEmptyCandidate(output.section.getChunkX(), output.section.getChunkY(), output.section.getChunkZ());
        }
    }

    @Inject(method = "onSectionRemoved", at = @At("HEAD"))
    private void rtxremix$removeSection(int x, int y, int z, CallbackInfo ci) {
        long position = net.minecraft.core.SectionPos.asLong(x, y, z);
        rtxremix$built.remove(position);
        rtxremix$recapture.remove(position);
        if (SodiumCapture.enabled()) SectionLeftRenderSetQueue.enqueueEmptyCandidate(x, y, z);
    }

    @Inject(method = "scheduleRebuild", at = @At("RETURN"))
    private void rtxremix$refreshRetainedSection(int x, int y, int z, boolean playerChanged, CallbackInfo ci) {
        if (!SodiumCapture.enabled()) return;
        long position = net.minecraft.core.SectionPos.asLong(x, y, z);
        // Built sections, including empty ones, can gain or lose geometry
        // outside the raster frustum while still affecting reflections/shadows.
        RenderSection section = renderSections.getConsistent(position);
        if (section != null && section.isBuilt()) rtxremix$recapture.add(position);
    }
}
