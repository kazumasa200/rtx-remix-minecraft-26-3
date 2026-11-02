/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.compat.immediatelyfast;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.nvidia.rtxremix.minecraft.entity.RemixVertexConsumerProvider;
import com.nvidia.rtxremix.minecraft.entity.TextGlyphCapture;
import com.nvidia.rtxremix.minecraft.texture.DynamicTextureCache;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.renderer.blockentity.AbstractSignRenderer;
import net.minecraft.client.renderer.blockentity.state.SignRenderState;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.entity.SignText;

/** Render-thread cache of local glyphs; poses and light remain per submission. */
public final class SignTextGeometryCache {
    private static SignTextGeometryCache active;

    private final Cache<Key, Entry> entries = CacheBuilder.newBuilder()
            .expireAfterAccess(Duration.ofSeconds(5))
            .maximumWeight(65_536)
            .weigher((Key key, Entry entry) -> Math.max(1, entry.glyphs.length))
            .build();
    private long generation = DynamicTextureCache.glyphGeneration();
    private final Key lookup = new Key();

    record Glyph(TextRenderable renderable, Font.DisplayMode mode) {}
    record Layout(Font font, boolean filtering, int maxWidth, int lineHeight, boolean outline) {}
    /** Only the lookup probe is mutated; keys stored in the cache are snapshots. */
    private static final class Key {
        SignText text;
        Font font;
        boolean filtering, outline;
        int maxWidth, lineHeight;

        void set(SignText text, Font font, SignRenderState state, boolean outline) {
            this.text = text;
            this.font = font;
            filtering = state.isTextFilteringEnabled;
            maxWidth = state.maxTextLineWidth;
            lineHeight = state.textLineHeight;
            this.outline = outline;
        }

        Key snapshot() {
            Key copy = new Key();
            copy.text = text;
            copy.font = font;
            copy.filtering = filtering;
            copy.outline = outline;
            copy.maxWidth = maxWidth;
            copy.lineHeight = lineHeight;
            return copy;
        }

        @Override public int hashCode() {
            int hash = 31 * text.hashCode() + System.identityHashCode(font);
            hash = 31 * hash + maxWidth;
            hash = 31 * hash + lineHeight;
            return 31 * hash + (filtering ? 2 : 0) + (outline ? 1 : 0);
        }

        @Override public boolean equals(Object other) {
            return other instanceof Key key && font == key.font && filtering == key.filtering
                    && outline == key.outline && maxWidth == key.maxWidth && lineHeight == key.lineHeight
                    && text.equals(key.text);
        }
    }
    record Entry(Layout layout, Glyph[] glyphs, boolean cacheable) {}

    SignTextGeometryCache() {}

    public static void render(Font font, SignRenderState state, PoseStack pose,
                              RemixVertexConsumerProvider provider, SignText text) {
        if (active == null) active = new SignTextGeometryCache();
        Entry entry = active.get(font, state, text);
        int light = text.hasGlowingText() ? 15728880 : state.lightCoords;
        for (Glyph glyph : entry.glyphs) {
            TextGlyphCapture.render(glyph.renderable, glyph.mode, pose.last().pose(), provider, light);
        }
    }

    /** Font/world teardown must not retain prepared glyphs until another sign is drawn. */
    public static void clear() {
        active = null;
    }

    Entry get(Font font, SignRenderState state, SignText text) {
        if (generation != DynamicTextureCache.glyphGeneration()) {
            entries.invalidateAll();
            generation = DynamicTextureCache.glyphGeneration();
        }
        boolean outline = text.hasGlowingText()
                && (text.getColor() == DyeColor.BLACK || state.drawOutline);
        lookup.set(text, font, state, outline);
        try {
            Entry cached = entries.getIfPresent(lookup);
            if (cached != null) return cached;
            // Font providers may reenter capture while preparing missing glyphs.
            Key key = lookup.snapshot();
            Layout layout = new Layout(font, state.isTextFilteringEnabled, state.maxTextLineWidth,
                    state.textLineHeight, outline);
            Entry prepared = prepare(layout, text);
            if (generation != DynamicTextureCache.glyphGeneration()) {
                entries.invalidateAll();
                generation = DynamicTextureCache.glyphGeneration();
            }
            if (prepared.cacheable) entries.put(key, prepared);
            return prepared;
        } finally {
            lookup.text = null;
            lookup.font = null;
        }
    }

    static Entry prepare(Layout layout, SignText text) {
        Font font = layout.font;
        // SignText's formatted-line cache does not include the font or width.
        // This cache owns both, so layout starts from the unformatted messages.
        var lines = text.getMessages(layout.filtering);
        int darkColor = AbstractSignRenderer.getDarkColor(text);
        int color = text.hasGlowingText() ? text.getColor().getTextColor() : darkColor;
        int midpoint = 4 * layout.lineHeight / 2;
        List<Glyph> glyphs = new ArrayList<>();
        RecordingSink sink = new RecordingSink(glyphs);
        boolean[] obfuscated = {false};
        for (int line = 0; line < lines.size(); line++) {
            List<FormattedCharSequence> split = font.split(lines.get(line), layout.maxWidth);
            FormattedCharSequence message = split.isEmpty() ? FormattedCharSequence.EMPTY : split.getFirst();
            message.accept((index, style, codePoint) -> {
                obfuscated[0] |= style.isObfuscated();
                return !obfuscated[0];
            });
            // Vanilla centers sign lines with integer division, including odd widths.
            float x = -font.width(message) / 2;
            float y = line * layout.lineHeight - midpoint;
            TextGlyphCapture.prepare(font, message, x, y, false, Font.DisplayMode.POLYGON_OFFSET,
                    color, 0, layout.outline ? darkColor : 0, sink);
        }
        return new Entry(layout, glyphs.toArray(Glyph[]::new), !obfuscated[0]);
    }

    private static final class RecordingSink implements TextGlyphCapture.Sink {
        private final List<Glyph> glyphs;
        private Font.DisplayMode mode;
        RecordingSink(List<Glyph> glyphs) { this.glyphs = glyphs; }
        @Override public void displayMode(Font.DisplayMode mode) { this.mode = mode; }
        @Override public void acceptRenderable(TextRenderable glyph) { glyphs.add(new Glyph(glyph, mode)); }
    }
}
