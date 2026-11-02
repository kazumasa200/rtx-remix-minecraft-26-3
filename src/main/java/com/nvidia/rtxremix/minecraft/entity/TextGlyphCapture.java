/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix4fc;

/** Shared preparation and emission for live text and retained local glyphs. */
public final class TextGlyphCapture {
    private TextGlyphCapture() {}

    public interface Sink extends Font.GlyphVisitor {
        void displayMode(Font.DisplayMode mode);
    }

    public static void prepare(Font font, FormattedCharSequence text, float x, float y,
                               boolean shadow, Font.DisplayMode mode, int color,
                               int backgroundColor, int outlineColor, Sink sink) {
        if (outlineColor == 0) {
            sink.displayMode(mode);
            font.prepareText(text, x, y, color, shadow, false, backgroundColor).visit(sink);
        } else {
            sink.displayMode(Font.DisplayMode.NORMAL);
            font.prepare8xTextOutline(text, x, y, outlineColor).visit(sink);
            sink.displayMode(Font.DisplayMode.POLYGON_OFFSET);
            font.prepareText(text, x, y, color, false, false, 0).visit(sink);
        }
    }

    public static void render(TextRenderable glyph, Font.DisplayMode mode, Matrix4fc pose,
                              RemixVertexConsumerProvider provider, int light) {
        glyph.render(pose, provider.getBuffer(glyph.renderType(mode)), light, false);
    }
}
