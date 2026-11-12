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
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.FeatureRenderer;
import net.minecraft.client.renderer.feature.FeatureRendererMap;
import net.minecraft.client.renderer.feature.FeatureRendererType;
import net.minecraft.client.renderer.feature.GizmoFeatureRenderer;
import net.minecraft.client.renderer.feature.QuadParticleFeatureRenderer;
import net.minecraft.client.renderer.feature.ShadowFeatureRenderer;
import net.minecraft.client.renderer.feature.ShapeOutlineFeatureRenderer;
import net.minecraft.client.renderer.feature.phase.FeatureRenderPhase;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.jspecify.annotations.Nullable;

/**
 * Captures what a renderer submits after vanilla's own storage and feature
 * renderers have had it, so whatever another mod hooks into either still runs.
 *
 * <p>A renderer submits to {@link #storage()}, which is vanilla's storage itself.
 * {@link #drain} then has vanilla's feature renderers build each submit, as they
 * do before a frame is drawn, and only the vertex builder they ask for is the
 * capture's ({@code RenderTypeFeatureRendererMixin}). {@link RemixSubmitCollector}
 * instead takes the storage's place and redoes what each feature renderer does,
 * and a mod that puts work into the storage or a feature renderer never ran.
 *
 * <p>Render thread only.
 */
public final class VanillaFeatureCapture implements FeatureRenderPhase.Output {

    /** Whose submits take this path. */
    public enum Scope { OFF, PLAYERS, ALL }

    /** The feature renderers' own build step, reached through {@code RenderTypeFeatureRendererMixin}. */
    public interface GroupBuilder {
        void rtxremix$buildGroup(FeatureFrameContext context, List<SubmitNode> submits);
    }

    private static volatile Scope scope = Scope.PLAYERS;
    /** Where the feature renderers' vertices go while a drain runs. */
    private static @Nullable RemixVertexConsumerProvider target;

    private final SubmitNodeStorage storage = new SubmitNodeStorage();
    private final List<SubmitNode> single = new ArrayList<>(1);
    private final Consumer<FeatureRenderPhase<?>> discard = phase -> phase.sortInto((submit, ordered) -> { });
    private FeatureFrameContext context;
    private FeatureRendererMap renderers;

    public static Scope scope() {
        return scope;
    }

    public static void setScope(Scope value) {
        scope = value;
    }

    public SubmitNodeStorage storage() {
        return storage;
    }

    /** Builds everything submitted to {@link #storage()} into {@code provider} and empties the storage. */
    public void drain(RemixVertexConsumerProvider provider) {
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
        target = provider;
        try {
            // Vanilla's phases a path tracer has a use for, in the order vanilla draws them.
            // Blob shadows, outlines, crumbling overlays, the water mask and gizmos stay behind.
            for (SubmitNodeCollection collection : storage.getSubmitsPerOrder().values()) {
                collection.solid.sortInto(this);
                collection.translucentModels.sortInto(this);
                collection.nameTags.sortInto(this);
                collection.texts.sortInto(this);
                collection.translucentCustomGeometry.sortInto(this);
                collection.translucentBlocksAndItems.sortInto(this);
                collection.afterTerrain.sortInto(this);
            }
        } finally {
            target = null;
            discard();
        }
    }

    /** Empties the storage without building anything, as after a renderer that threw. */
    public void discard() {
        storage.drainPhases(discard);
    }

    @Override
    public void accept(SubmitNode submit, boolean strictlyOrdered) {
        FeatureRendererType<?> type = submit.featureType();
        if (type == ShadowFeatureRenderer.TYPE || type == ShapeOutlineFeatureRenderer.TYPE
                || type == GizmoFeatureRenderer.TYPE || type == QuadParticleFeatureRenderer.TYPE) {
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
