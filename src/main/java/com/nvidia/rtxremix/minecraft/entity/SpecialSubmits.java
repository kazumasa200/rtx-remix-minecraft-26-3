/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.AbstractEndPortalRenderer;
import net.minecraft.client.renderer.feature.TextFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * The two vanilla submits a path tracer cannot take as vanilla's feature renderers build
 * them, drawn the capture's own way instead. {@link VanillaFeatureCapture} hands them here
 * and lets vanilla build every other submit.
 *
 * <p>Text: a name tag's glyphs lie in the plane of its background plate, and Remix orders
 * coplanar surfaces by primitive, not by depth, so the capture lays text out itself and
 * turns draw order into depth ({@link TextGlyphCapture}).
 *
 * <p>The End portal and gateway: vanilla draws their starfield in a shader from screen
 * position, with position-only faces, which leaves nothing to trace ({@link EndPortalFaces}).
 *
 * <p>Render thread only. The scratch objects are shared across submits.
 */
final class SpecialSubmits {

    private final RemixVertexConsumerProvider provider;
    private final TextGlyphs glyphs = new TextGlyphs();
    /**
     * The block a block entity's capture belongs to. Its geometry arrives on an identity
     * stack, block-local, and the End portal's stars are laid out in world space.
     */
    private BlockPos blockOrigin = BlockPos.ZERO;

    SpecialSubmits(RemixVertexConsumerProvider provider) {
        this.provider = provider;
    }

    void setBlockOrigin(BlockPos origin) {
        blockOrigin = origin;
    }

    /** Draws a text submit as vanilla's storage holds it. */
    void captureText(TextFeatureRenderer.Submit submit) {
        Font font = Minecraft.getInstance().font;
        glyphs.begin(submit.pose(), submit.lightCoords(), submit.displayMode());
        switch (submit.content()) {
            case TextFeatureRenderer.Content.Text text -> {
                FormattedCharSequence string = text.string();
                TextGlyphCapture.prepare(font, string, text.x(), text.y(), text.dropShadow(),
                        submit.displayMode(), text.color(), text.backgroundColor(), text.outlineColor(), glyphs);
            }
            case TextFeatureRenderer.Content.StandaloneBackground background ->
                glyphs.acceptRenderable(font.prepareBackground(
                        background.x0(), background.y0(), background.x1(), background.y1(), background.color()));
        }
    }

    /**
     * Draws the End portal's or gateway's faces and returns true, or returns false for any
     * other custom geometry.
     */
    boolean capturePortal(PoseStack.Pose pose, RenderType renderType,
                          SubmitNodeCollector.CustomGeometryRenderer customGeometryRenderer) {
        if (renderType.pipeline() != RenderPipelines.END_PORTAL
                && renderType.pipeline() != RenderPipelines.END_GATEWAY) {
            return false;
        }
        Minecraft client = Minecraft.getInstance();
        float time = client.level == null ? 0f
                : (client.level.getGameTime() % 24000L
                        + client.getDeltaTracker().getGameTimeDeltaPartialTick(false)) / 24000f;
        customGeometryRenderer.render(pose, new EndPortalFaces(
                provider.getBuffer(RenderTypes.entitySolid(AbstractEndPortalRenderer.END_SKY_LOCATION)),
                provider.getBuffer(RenderTypes.eyes(AbstractEndPortalRenderer.END_SKY_LOCATION)),
                provider.getBuffer(RenderTypes.eyes(AbstractEndPortalRenderer.END_PORTAL_LOCATION)),
                time, blockOrigin, renderType.pipeline() == RenderPipelines.END_PORTAL));
        return true;
    }

    /** Draws each laid-out glyph and effect into the capture under the text's pose. */
    private final class TextGlyphs implements TextGlyphCapture.Sink {
        private final Matrix4f pose = new Matrix4f();
        private int lightCoords;
        private Font.DisplayMode displayMode = Font.DisplayMode.NORMAL;

        void begin(Matrix4fc pose, int lightCoords, Font.DisplayMode displayMode) {
            this.pose.set(pose);
            this.lightCoords = lightCoords;
            this.displayMode = displayMode;
        }

        @Override
        public void displayMode(Font.DisplayMode mode) {
            displayMode = mode;
        }

        @Override
        public void acceptRenderable(TextRenderable renderable) {
            TextGlyphCapture.render(renderable, displayMode, pose, provider, lightCoords);
        }
    }
}
