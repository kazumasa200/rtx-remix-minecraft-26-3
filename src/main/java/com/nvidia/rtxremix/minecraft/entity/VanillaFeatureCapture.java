/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nvidia.rtxremix.minecraft.mixin.FeatureRenderDispatcherAccessor;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.CustomFeatureRenderer;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.FeatureRenderer;
import net.minecraft.client.renderer.feature.FeatureRendererMap;
import net.minecraft.client.renderer.feature.FeatureRendererType;
import net.minecraft.client.renderer.feature.GizmoFeatureRenderer;
import net.minecraft.client.renderer.feature.QuadParticleFeatureRenderer;
import net.minecraft.client.renderer.feature.ShadowFeatureRenderer;
import net.minecraft.client.renderer.feature.ShapeOutlineFeatureRenderer;
import net.minecraft.client.renderer.feature.TextFeatureRenderer;
import net.minecraft.client.renderer.feature.phase.FeatureRenderPhase;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

/**
 * Captures what a renderer submits after vanilla's own storage and feature
 * renderers have had it, so whatever another mod hooks into either still runs.
 *
 * <p>A renderer submits to {@link #storage()}, which is vanilla's storage itself.
 * {@link #drain} then has vanilla's feature renderers build each submit, as they
 * do before a frame is drawn, and only the vertex builder they ask for is the
 * capture's ({@code RenderTypeFeatureRendererMixin}). The port to 26.3 first gave
 * renderers a collector of its own that redid each feature renderer, and a mod that
 * put work into the storage or a feature renderer never ran (#29, #30).
 *
 * <p>Render thread only.
 */
public final class VanillaFeatureCapture implements FeatureRenderPhase.Output {

    /** The feature renderers' own build step, reached through {@code RenderTypeFeatureRendererMixin}. */
    public interface GroupBuilder {
        void rtxremix$buildGroup(FeatureFrameContext context, List<SubmitNode> submits);
    }

    /** Where the feature renderers' vertices go while a drain runs. */
    private static @Nullable RemixVertexConsumerProvider target;
    /** The storage of the capture being submitted to, and where it is captured. */
    private static @Nullable SubmitNodeStorage submitting;
    private static @Nullable RemixVertexConsumerProvider submittingProvider;

    /**
     * What the open capture submitted and the phase each node went to, in the order it was
     * submitted. Vanilla groups a phase's submits by feature and render type before it draws
     * them, and the capture reads meaning from the order: a layer's index is its first use,
     * and an overlay is painted onto the layer before it (a sheep's wool onto its body).
     */
    private static final List<SubmitNode> ORDERED = new ArrayList<>();
    private static final List<FeatureRenderPhase<?>> ORDERED_PHASES = new ArrayList<>();

    private final SubmitNodeStorage storage = new SubmitNodeStorage();
    private final RemixVertexConsumerProvider provider;
    /** Text and the End portal, which the capture draws its own way. */
    private final SpecialSubmits special;
    private final List<FeatureRenderPhase<?>> skipped = new ArrayList<>();
    private final List<SubmitNode> single = new ArrayList<>(1);
    private final Consumer<FeatureRenderPhase<?>> discard = phase -> phase.sortInto((submit, ordered) -> { });
    private FeatureFrameContext context;
    private FeatureRendererMap renderers;

    public VanillaFeatureCapture(RemixVertexConsumerProvider provider) {
        this.provider = provider;
        this.special = new SpecialSubmits(provider);
    }

    /** The block a block entity's capture belongs to; see {@link SpecialSubmits}. */
    public void setBlockOrigin(BlockPos origin) {
        special.setBlockOrigin(origin);
    }

    /** Called by vanilla's phases for every node they take (the two phase mixins). */
    public static void submitted(FeatureRenderPhase<?> phase, SubmitNode submit) {
        if (submitting != null) {
            ORDERED.add(submit);
            ORDERED_PHASES.add(phase);
        }
    }

    /**
     * Opens a capture and returns the storage its renderer submits to. {@link #drain} or
     * {@link #discard} closes it.
     */
    public SubmitNodeStorage begin() {
        submitting = storage;
        submittingProvider = provider;
        return storage;
    }

    /**
     * The provider a collector's submits are captured into, or null for a collector that is
     * not a capture's: vanilla's own frame, a GUI.
     */
    public static @Nullable RemixVertexConsumerProvider provider(SubmitNodeCollector collector) {
        return collector == submitting ? submittingProvider : null;
    }

    /** Builds everything submitted since {@link #begin()} into the provider and empties the storage. */
    public void drain() {
        Minecraft client = Minecraft.getInstance();
        GameRenderer gameRenderer = client.gameRenderer;
        renderers = ((FeatureRenderDispatcherAccessor) gameRenderer.featureRenderDispatcher()).rtxremix$featureRenderers();
        context = new FeatureFrameContext(
                gameRenderer.gameRenderState().optionsRenderState,
                client.font,
                client.getModelManager().getBlockStateModelSet(),
                client.getBlockColors(),
                client.getTextureManager(),
                client.getAtlasManager(),
                gameRenderer.lightmap(),
                gameRenderer.renderBuffers().stagedVertexBuffer());
        // The phases a path tracer has no use for: blob shadows, outlines, crumbling
        // overlays (the block damage pass submits its own), the water mask, gizmos, and
        // text drawn over the whole scene whatever its depth.
        skipped.clear();
        skipped.add(storage.seeThrough());
        for (SubmitNodeCollection collection : storage.getSubmitsPerOrder().values()) {
            skipped.add(collection.outline);
            skipped.add(collection.shadows);
            skipped.add(collection.shapeOutlines);
            skipped.add(collection.breakingOverlay);
            skipped.add(collection.waterMask);
            skipped.add(collection.translucentGizmos);
            skipped.add(collection.alwaysOnTopGizmos);
        }
        // No more recording: building a submit must not add to the list being walked.
        submitting = null;
        target = provider;
        try {
            for (int i = 0; i < ORDERED.size(); i++) {
                if (!skipped.contains(ORDERED_PHASES.get(i))) {
                    accept(ORDERED.get(i), true);
                }
            }
        } finally {
            target = null;
            discard();
        }
    }

    /** Empties the storage without building anything, as after a renderer that threw. */
    public void discard() {
        submitting = null;
        submittingProvider = null;
        ORDERED.clear();
        ORDERED_PHASES.clear();
        storage.drainPhases(discard);
    }

    @Override
    public void accept(SubmitNode submit, boolean strictlyOrdered) {
        FeatureRendererType<?> type = submit.featureType();
        if (type == ShadowFeatureRenderer.TYPE || type == ShapeOutlineFeatureRenderer.TYPE
                || type == GizmoFeatureRenderer.TYPE || type == QuadParticleFeatureRenderer.TYPE) {
            return;
        }
        if (submit instanceof TextFeatureRenderer.Submit text) {
            special.captureText(text);
            return;
        }
        if (submit instanceof CustomFeatureRenderer.Submit custom
                && special.capturePortal(custom.pose(), custom.renderType(), custom.customGeometryRenderer())) {
            return;
        }
        FeatureRenderer<?> renderer = renderers.get(type);
        if (renderer instanceof GroupBuilder builder) {
            single.clear();
            single.add(submit);
            builder.rtxremix$buildGroup(context, single);
        }
    }

    /** The capture's vertex builder for a feature renderer, or null when no drain is running. */
    public static @Nullable VertexConsumer vertexBuilder(RenderType renderType) {
        RemixVertexConsumerProvider provider = target;
        if (provider == null) {
            return null;
        }
        return renderType.isOutline() ? RemixVertexConsumerProvider.discarded() : provider.getBuffer(renderType);
    }
}
