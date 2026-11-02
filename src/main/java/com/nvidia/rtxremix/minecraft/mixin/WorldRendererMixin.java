/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.terrain.capture.RegionBuilderRelease;
import com.nvidia.rtxremix.minecraft.terrain.WorldRendererTerrainAccess;
import com.nvidia.rtxremix.minecraft.terrain.TerrainBackendProvider;
import com.nvidia.rtxremix.minecraft.profiling.WorldRendererProfilingAccess;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import com.nvidia.rtxremix.minecraft.RemixRenderer;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.mojang.blaze3d.vertex.PoseStack;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayDeque;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.state.level.BlockBreakingRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.ParticlesRenderState;
import net.minecraft.client.renderer.state.level.WeatherRenderState;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.renderer.oit.OitRenderPassProvider;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.renderer.oit.OitStage;
import com.mojang.renderpearl.api.commands.RenderPass;
import net.minecraft.core.SectionPos;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Reroutes vanilla's world render into the Remix capture while a Remix world is
 * active.
 *
 * <ul>
 *   <li>Vanilla's particle submission is skipped, because Remix owns that
 *       geometry; its terrain draws are skipped by
 *       {@link ChunkSectionsToRenderMixin}.</li>
 *   <li>Section compiles are exposed to the terrain pipeline: the built set,
 *       the explicit recapture queue, and single-section re-flags.</li>
 *   <li>Every vanilla render phase and {@code compileSections} are labelled
 *       for Tracy and JFR.</li>
 * </ul>
 */
@Mixin(LevelRenderer.class)
public abstract class WorldRendererMixin
        implements WorldRendererProfilingAccess, WorldRendererTerrainAccess {

    private static final ThreadLocal<ArrayDeque<ProfileScope>> rtxremix$renderScope = new ThreadLocal<>();
    private static final ThreadLocal<ArrayDeque<ProfileScope>> rtxremix$updateChunksScope = new ThreadLocal<>();
    private static final ThreadLocal<ArrayDeque<ProfileScope>> rtxremix$uploadScope = new ThreadLocal<>();
    private static final ThreadLocal<ArrayDeque<ProfileScope>> rtxremix$syncRebuildScope = new ThreadLocal<>();

    @Shadow
    private @Nullable SectionRenderDispatcher sectionRenderDispatcher;

    @Shadow
    private @Nullable ViewArea viewArea;

    @Shadow
    @Final
    private LevelRenderState levelRenderState;

    @Shadow
    @Final
    private ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections;

    private ProfileScope rtxremix$profilerPhaseScope;
    private String rtxremix$profilerPhaseName;
    private int rtxremix$frameEntityCount = -1;
    private int rtxremix$frameBlockEntityCount = -1;
    private int rtxremix$frameBlockBreakingCount = -1;

    /** The recapture waiting for vanilla's next view-area rebuild; see {@link #rtxremix$queueSectionsForRemix}. */
    private @Nullable LongSet rtxremix$pendingRecapture;
    private @Nullable Consumer<LongSet> rtxremix$pendingRecaptureCallback;
    private @Nullable ClientLevel rtxremix$pendingRecaptureLevel;

    @Override
    public LongSet rtxremix$builtSectionPositions() {
        if ((Object) this instanceof TerrainBackendProvider provider) {
            return provider.rtxremix$terrainBackend().rtxremix$builtSectionPositions();
        }
        LongOpenHashSet built = new LongOpenHashSet();
        if (viewArea == null) {
            return built;
        }
        for (SectionRenderDispatcher.RenderSection section : ((ViewAreaAccessor) viewArea).rtxremix$sections()) {
            if (section.sectionMesh.get() != CompiledSectionMesh.UNCOMPILED) {
                built.add(section.getSectionNode());
            }
        }
        return built;
    }

    @Override
    public void rtxremix$queueSectionsForRemix(LongSet sections, Consumer<LongSet> onQueued) {
        if ((Object) this instanceof TerrainBackendProvider provider) {
            provider.rtxremix$terrainBackend().rtxremix$queueSectionsForRemix(sections, onQueued);
            return;
        }
        Minecraft client = Minecraft.getInstance();
        rtxremix$pendingRecapture = sections;
        rtxremix$pendingRecaptureCallback = onQueued;
        rtxremix$pendingRecaptureLevel = client.level;
    }

    /**
     * Runs a waiting recapture against the view area vanilla has just rebuilt. The rebuild
     * runs at the top of the extract, before vanilla schedules this frame's section
     * compiles, and every section of the fresh area is dirty and uncompiled, so a queued
     * section is marked clean here and the frame does not compile it a second time.
     */
    @Inject(method = "invalidateCompiledGeometry", at = @At("RETURN"))
    private void rtxremix$runPendingRecapture(
            ClientLevel level, Options options, Camera camera, BlockColors blockColors, CallbackInfo ci) {
        LongSet sections = rtxremix$pendingRecapture;
        Consumer<LongSet> onQueued = rtxremix$pendingRecaptureCallback;
        ClientLevel requestedLevel = rtxremix$pendingRecaptureLevel;
        rtxremix$pendingRecapture = null;
        rtxremix$pendingRecaptureCallback = null;
        rtxremix$pendingRecaptureLevel = null;
        if (sections == null || onQueued == null) {
            return;
        }
        if (requestedLevel != level) {
            // The recapture was for a world this rebuild no longer renders.
            onQueued.accept(LongSets.emptySet());
            return;
        }
        onQueued.accept(rtxremix$queueSections(level, camera, sections));
    }

    private LongSet rtxremix$queueSections(ClientLevel level, Camera camera, LongSet sections) {
        if (viewArea == null || sectionRenderDispatcher == null || sections.isEmpty()) {
            return LongSets.emptySet();
        }
        SectionUpdateTracker tracker =
                ((LevelExtractorAccessor) Minecraft.getInstance().levelExtractor).rtxremix$sectionUpdateTracker();
        if (tracker == null) {
            return LongSets.emptySet();
        }

        // The same gate vanilla puts on a first compile: a section compiled before its
        // neighbours and its column's light are ready would bake missing faces and bad
        // light, and marking it clean below means nothing re-flags it afterwards.
        LongArrayList loadedSections = new LongArrayList();
        for (LongIterator it = sections.iterator(); it.hasNext(); ) {
            long sectionNode = it.nextLong();
            if (((ViewAreaAccessor) viewArea).rtxremix$getRenderSection(sectionNode) == null) {
                continue;
            }
            LevelChunk chunk = level.getChunkSource().getChunk(
                    SectionPos.x(sectionNode), SectionPos.z(sectionNode), false);
            if (chunk == null) {
                continue;
            }
            int sectionIndex = chunk.getSectionIndexFromSectionY(SectionPos.y(sectionNode));
            if (sectionIndex < 0 || sectionIndex >= chunk.getSectionsCount()
                    || chunk.getSection(sectionIndex).hasOnlyAir()) {
                continue;
            }
            if (!level.getLightEngine().lightOnInColumn(SectionPos.getZeroNode(sectionNode))
                    || !tracker.hasAllNeighbors(level, sectionNode)) {
                continue;
            }
            loadedSections.add(sectionNode);
        }

        // The compile queue drains nearest-first by its own measure only for what it
        // already holds; queue nearby sections first so visible holes close promptly
        // while the Remix scene repopulates.
        double cameraX = camera.position().x;
        double cameraY = camera.position().y;
        double cameraZ = camera.position().z;
        loadedSections.unstableSort((a, b) -> Double.compare(
                rtxremix$distanceSq(a, cameraX, cameraY, cameraZ),
                rtxremix$distanceSq(b, cameraX, cameraY, cameraZ)));

        RenderRegionCache regionCache = new RenderRegionCache();
        LongOpenHashSet queued = new LongOpenHashSet(loadedSections.size());
        try {
            for (int i = 0; i < loadedSections.size(); i++) {
                long sectionNode = loadedSections.getLong(i);
                SectionRenderDispatcher.RenderSection section =
                        ((ViewAreaAccessor) viewArea).rtxremix$getRenderSection(sectionNode);
                section.compileAsync(regionCache.createRegion(level, sectionNode));
                SectionUpdateTracker.SectionDirtyState dirtyState = tracker.getDirtyState(sectionNode);
                if (dirtyState != null) {
                    dirtyState.setNotDirty();
                }
                queued.add(sectionNode);
            }
        } finally {
            ((RegionBuilderRelease) regionCache).rtxremix$releaseSnapshots();
        }
        return queued;
    }

    private static double rtxremix$distanceSq(long sectionNode, double cameraX, double cameraY, double cameraZ) {
        double dx = SectionPos.sectionToBlockCoord(SectionPos.x(sectionNode)) + 8.0 - cameraX;
        double dy = SectionPos.sectionToBlockCoord(SectionPos.y(sectionNode)) + 8.0 - cameraY;
        double dz = SectionPos.sectionToBlockCoord(SectionPos.z(sectionNode)) + 8.0 - cameraZ;
        return dx * dx + dy * dy + dz * dz;
    }

    @Override
    public void rtxremix$scheduleResidentSectionRebuild(int cx, int sy, int cz) {
        if ((Object) this instanceof TerrainBackendProvider provider) {
            provider.rtxremix$terrainBackend().rtxremix$scheduleResidentSectionRebuild(cx, sy, cz);
            return;
        }
        // The extractor's tracker ignores a section outside its window.
        Minecraft.getInstance().levelExtractor.setSectionDirty(cx, sy, cz);
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void rtxremix$renderHead(CallbackInfo ci) {
        rtxremix$openJfrOnly(
                rtxremix$renderScope,
                "WorldRenderer.render",
                "Java.WorldRenderer.render.ms");
        // Submission clears these lists, so the phase annotations read the frame's
        // counts from here.
        rtxremix$frameEntityCount = levelRenderState.entityRenderStates.size();
        rtxremix$frameBlockEntityCount = levelRenderState.blockEntityRenderStates.size();
        rtxremix$frameBlockBreakingCount = levelRenderState.blockBreakingRenderStates.size();
        rtxremix$transitionProfilerPhase("preamble");
        RtxRemixMod.onLevelRenderStart();
        RtxRemixMod mod = RtxRemixMod.getInstance();
        if (mod != null && mod.getRenderer() != null) {
            mod.getRenderer().beginEntityFrame();
        }
    }

    /**
     * The main pass's body, as the frame graph runs it: before Fabric's START_MAIN and any
     * draw of the pass, so the other mods' world draws land on transparency over the last
     * Remix frame's depth.
     */
    @Inject(method = "lambda$addMainPass$0", at = @At("HEAD"))
    private void rtxremix$prepareModOverlay(CallbackInfo ci) {
        RtxRemixMod.prepareModOverlay(levelRenderState.cameraRenderState);
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void rtxremix$renderReturn(CallbackInfo ci) {
        RtxRemixMod.onLevelRenderEnd();
        rtxremix$closeProfilerPhase();
        rtxremix$close(rtxremix$renderScope);
    }

    @WrapOperation(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V"
            ))
    private void rtxremix$mirrorVanillaProfilerPhase(
            ProfilerFiller profiler,
            String name,
            Operation<Void> original) {
        original.call(profiler, name);
        rtxremix$transitionProfilerPhase(name);
    }

    /** Remix draws the particles; vanilla would only write them into the frame Remix replaces. */
    @WrapOperation(
            method = "submitFeatures",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/state/level/ParticlesRenderState;submit(Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V"
            ))
    private void rtxremix$skipVanillaParticleSubmit(
            ParticlesRenderState particles,
            SubmitNodeCollector collector,
            CameraRenderState camera,
            Operation<Void> original) {
        if (!RtxRemixMod.isRemixWorldRendering()) {
            original.call(particles, collector, camera);
        }
    }

    /**
     * Remix draws the weather over its image ({@code RemixWeatherRenderer}); vanilla's draw
     * would land in the frame that image replaces. The draw sets its pipeline, uniforms
     * and buffers on the pass and the pass's next draw sets its own, so skipping it whole
     * leaves nothing for a later draw to inherit.
     */
    @WrapOperation(
            method = "executeClassicTransparency",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/WeatherEffectRenderer;render(Lnet/minecraft/client/renderer/state/level/WeatherRenderState;Lcom/mojang/renderpearl/api/commands/RenderPass;)V"
            ))
    private void rtxremix$skipVanillaWeather(
            WeatherEffectRenderer weather,
            WeatherRenderState state,
            RenderPass pass,
            Operation<Void> original) {
        if (!RtxRemixMod.isRemixWorldRendering()) {
            original.call(weather, state, pass);
        }
    }

    /** The order-independent transparency counterpart of {@link #rtxremix$skipVanillaWeather}. */
    @WrapOperation(
            method = "executeOit",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/WeatherEffectRenderer;renderOit(Lnet/minecraft/client/renderer/oit/OitStage;Lnet/minecraft/client/renderer/state/level/WeatherRenderState;Lcom/mojang/renderpearl/api/commands/RenderPass;)V"
            ))
    private void rtxremix$skipVanillaOitWeather(
            WeatherEffectRenderer weather,
            OitStage stage,
            WeatherRenderState state,
            RenderPass pass,
            Operation<Void> original) {
        if (!RtxRemixMod.isRemixWorldRendering()) {
            original.call(weather, stage, state, pass);
        }
    }

    /**
     * Remix draws the clouds whenever its cloud mode is healthy; vanilla's draw would land
     * in the frame the Remix image replaces. Skipped whole like the weather draw.
     */
    @WrapOperation(
            method = "executeClassicTransparency",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/CloudRenderer;render(Lnet/minecraft/client/CloudStatus;Lcom/mojang/renderpearl/api/commands/RenderPass;)V"
            ))
    private void rtxremix$skipVanillaClouds(
            CloudRenderer clouds,
            CloudStatus status,
            RenderPass pass,
            Operation<Void> original) {
        if (!rtxremix$remixDrawsClouds()) {
            original.call(clouds, status, pass);
        }
    }

    /** The order-independent transparency counterpart of {@link #rtxremix$skipVanillaClouds}. */
    @WrapOperation(
            method = "executeOit",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/CloudRenderer;renderOit(Lnet/minecraft/client/CloudStatus;Lnet/minecraft/client/renderer/oit/OitStage;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Lnet/minecraft/client/renderer/oit/OitRenderPassProvider$Parameters;)V"
            ))
    private void rtxremix$skipVanillaOitClouds(
            CloudRenderer clouds,
            CloudStatus status,
            OitStage stage,
            GpuTextureView depth,
            OitRenderPassProvider.Parameters parameters,
            Operation<Void> original) {
        if (!rtxremix$remixDrawsClouds()) {
            original.call(clouds, status, stage, depth, parameters);
        }
    }

    private static boolean rtxremix$remixDrawsClouds() {
        RemixRenderer renderer = RtxRemixMod.renderer;
        return RtxRemixMod.isRemixWorldRendering()
                && renderer != null
                && renderer.getCloudRenderer() != null
                && renderer.getCloudRenderer().shouldSuppressVanillaClouds();
    }

    /**
     * Captures each entity vanilla extracted at its own submit point, in place of the
     * submit that would have gone to the frame graph; see
     * {@code RemixRenderer.renderEntityInPlace}.
     */
    @WrapOperation(
            method = "submitEntities",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;submit(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lnet/minecraft/client/renderer/state/level/CameraRenderState;DDDLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;)V"
            ))
    private void rtxremix$captureEntityInPlace(
            EntityRenderDispatcher dispatcher,
            EntityRenderState state,
            CameraRenderState camera,
            double x,
            double y,
            double z,
            PoseStack poseStack,
            SubmitNodeCollector collector,
            Operation<Void> original) {
        RemixRenderer renderer = RtxRemixMod.renderer;
        if (renderer == null || !renderer.renderEntityInPlace(state, camera, levelRenderState.worldPartialTicks)) {
            original.call(dispatcher, state, camera, x, y, z, poseStack, collector);
        }
    }

    /**
     * Captures each block entity vanilla extracted at its own submit point, in place
     * of the submit that would have gone to the frame graph; see
     * {@code RemixRenderer.renderBlockEntityInPlace}.
     */
    @WrapOperation(
            method = "submitBlockEntities",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderDispatcher;submit(Lnet/minecraft/client/renderer/blockentity/state/BlockEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V"
            ))
    private void rtxremix$captureBlockEntityInPlace(
            BlockEntityRenderDispatcher dispatcher,
            BlockEntityRenderState state,
            PoseStack poseStack,
            SubmitNodeCollector collector,
            CameraRenderState camera,
            Operation<Void> original) {
        RemixRenderer renderer = RtxRemixMod.renderer;
        if (renderer == null || !renderer.renderBlockEntityInPlace(state, camera)) {
            original.call(dispatcher, state, poseStack, collector, camera);
        }
    }

    /**
     * Captures each block's breaking crack at vanilla's own submit point, in place of
     * the submit that would have gone to the frame graph; see
     * {@code RemixRenderer.renderBlockDamageInPlace}.
     */
    @WrapOperation(
            method = "submitBlockDestroyAnimation",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitBreakingBlockModel(Lcom/mojang/blaze3d/vertex/PoseStack;Ljava/util/List;IZ)V"
            ))
    private void rtxremix$captureBlockDamageInPlace(
            SubmitNodeCollector collector,
            PoseStack poseStack,
            List<BlockStateModelPart> parts,
            int progress,
            boolean isBlockTranslucent,
            Operation<Void> original,
            @Local BlockBreakingRenderState state) {
        RemixRenderer renderer = RtxRemixMod.renderer;
        if (renderer == null || !renderer.renderBlockDamageInPlace(state, parts)) {
            original.call(collector, poseStack, parts, progress, isBlockTranslucent);
        }
    }

    @Inject(method = "compileSections", at = @At("HEAD"))
    private void rtxremix$updateChunksHead(CameraRenderState camera, CallbackInfo ci) {
        int queued = rtxremix$compileQueueSize();
        if (queued >= 0) {
            ProfileScope.plot("Java.Chunk.toBatchCount", queued);
        }
        rtxremix$open(
                rtxremix$updateChunksScope,
                "WorldRenderer.updateChunks",
                -1,
                queued);
    }

    @Inject(method = "compileSections", at = @At("RETURN"))
    private void rtxremix$updateChunksReturn(CameraRenderState camera, CallbackInfo ci) {
        rtxremix$close(rtxremix$updateChunksScope);
    }

    @Inject(
            method = "compileSections",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;compileSync(Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;)V"
            ),
            require = 0)
    private void rtxremix$beforeSyncRebuild(CameraRenderState camera, CallbackInfo ci) {
        rtxremix$open(
                rtxremix$syncRebuildScope,
                "WorldRenderer.updateChunks.build_near_sync",
                -1,
                rtxremix$compileQueueSize());
    }

    @Inject(
            method = "compileSections",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;compileSync(Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;)V",
                    shift = At.Shift.AFTER
            ),
            require = 0)
    private void rtxremix$afterSyncRebuild(CameraRenderState camera, CallbackInfo ci) {
        rtxremix$close(rtxremix$syncRebuildScope);
    }

    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher;uploadTerrainBuffersToGpu()V"
            ),
            require = 0)
    private void rtxremix$beforeChunkUpload(CallbackInfo ci) {
        rtxremix$open(
                rtxremix$uploadScope,
                "WorldRenderer.updateChunks.upload",
                -1,
                -1);
    }

    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher;uploadTerrainBuffersToGpu()V",
                    shift = At.Shift.AFTER
            ),
            require = 0)
    private void rtxremix$afterChunkUpload(CallbackInfo ci) {
        rtxremix$close(rtxremix$uploadScope);
    }

    private int rtxremix$compileQueueSize() {
        return sectionRenderDispatcher == null ? -1 : sectionRenderDispatcher.getCompileQueueSize();
    }

    private void rtxremix$transitionProfilerPhase(String vanillaPhaseName) {
        if (!ProfileScope.isDeepProfilingEnabled()) {
            rtxremix$closeProfilerPhase();
            return;
        }
        rtxremix$closeProfilerPhase();
        rtxremix$profilerPhaseName = "WorldRenderer.phase." + rtxremix$sanitizePhase(vanillaPhaseName);
        rtxremix$profilerPhaseScope = ProfileScope.begin(rtxremix$profilerPhaseName)
                .plotOnClose("Java." + rtxremix$profilerPhaseName + ".ms");
    }

    private void rtxremix$closeProfilerPhase() {
        ProfileScope scope = rtxremix$profilerPhaseScope;
        if (scope == null) {
            return;
        }
        rtxremix$profilerPhaseScope = null;
        rtxremix$annotateProfilerPhase(scope);
        scope.close();
    }

    @Override
    public void rtxremix$closeProfilerPhaseForFabricEvent() {
        rtxremix$closeProfilerPhase();
    }

    private void rtxremix$annotateProfilerPhase(ProfileScope scope) {
        if (visibleSections != null) {
            int visibleChunks = visibleSections.size();
            scope.visibleChunkCount(visibleChunks);
            scope.captureCount(visibleChunks);
        }
        scope.entityCount(rtxremix$frameEntityCount);
        scope.blockEntityCount(rtxremix$frameBlockEntityCount);
        scope.noCullBlockEntityCount(rtxremix$noCullBlockEntityCount());
        scope.blockBreakingCount(rtxremix$frameBlockBreakingCount);
    }

    private static int rtxremix$noCullBlockEntityCount() {
        ClientLevel level = Minecraft.getInstance().level;
        return level == null ? -1 : level.getGloballyRenderedBlockEntities().size();
    }

    private static String rtxremix$sanitizePhase(String phase) {
        if (phase == null || phase.isBlank()) {
            return "unknown";
        }
        return phase.replace(' ', '_');
    }

    private static void rtxremix$openJfrOnly(
            ThreadLocal<ArrayDeque<ProfileScope>> holder,
            String phaseName,
            String plotName) {
        if (!ProfileScope.isDeepProfilingEnabled()) {
            return;
        }
        rtxremix$push(holder, ProfileScope.beginJfrOnly(phaseName).plotOnClose(plotName));
    }

    private static void rtxremix$open(
            ThreadLocal<ArrayDeque<ProfileScope>> holder,
            String phaseName,
            int installCount,
            int dirtyLeaves) {
        if (!ProfileScope.isDeepProfilingEnabled()) {
            return;
        }
        ProfileScope scope = ProfileScope.begin(phaseName);
        if (installCount >= 0) {
            scope.installCount(installCount);
        }
        if (dirtyLeaves >= 0) {
            scope.dirtyLeaves(dirtyLeaves);
        }
        rtxremix$push(holder, scope);
    }

    private static void rtxremix$push(
            ThreadLocal<ArrayDeque<ProfileScope>> holder,
            ProfileScope scope) {
        ArrayDeque<ProfileScope> stack = holder.get();
        if (stack == null) {
            stack = new ArrayDeque<>();
            holder.set(stack);
        }
        stack.push(scope);
    }

    private static void rtxremix$close(ThreadLocal<ArrayDeque<ProfileScope>> holder) {
        if (!ProfileScope.isDeepProfilingEnabled()) {
            return;
        }
        ArrayDeque<ProfileScope> stack = holder.get();
        if (stack != null && !stack.isEmpty()) {
            ProfileScope scope = stack.pop();
            if (stack.isEmpty()) {
                holder.remove();
            }
            scope.close();
        } else if (stack != null) {
            holder.remove();
        }
    }
}
