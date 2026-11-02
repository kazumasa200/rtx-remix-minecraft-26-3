/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.compat.immediatelyfast;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.nvidia.rtxremix.minecraft.texture.DynamicTextureCache;
import com.nvidia.rtxremix.minecraft.texture.GlyphPageMirror;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.client.gui.GlyphSource;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.gui.font.glyphs.EffectGlyph;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.AbstractSignRenderer;
import net.minecraft.client.renderer.blockentity.state.SignRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.entity.SignText;
import org.joml.Matrix4fc;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import static org.junit.jupiter.api.Assertions.*;

class SignTextGeometryCacheTest {
    static {
        try {
            SharedConstants.getCurrentVersion();
        } catch (IllegalStateException unset) {
            SharedConstants.tryDetectVersion();
        }
        Bootstrap.bootStrap();
    }

    @Test
    void glyphPreparationMatchesTheActualVanillaSignSubmissions() throws Exception {
        for (boolean glowing : new boolean[] {false, true}) {
            for (boolean outline : new boolean[] {false, true}) {
                for (DyeColor color : new DyeColor[] {DyeColor.BLACK, DyeColor.RED}) {
                    FakeFont font = new FakeFont();
                    SignText text = text(Component.literal("odd"), color, glowing);
                    SignRenderState state = state();
                    state.drawOutline = outline;
                    List<SignTextGeometryCache.Glyph> expected = vanillaGlyphs(font, state, text);
                    SignTextGeometryCache.Entry actual = new SignTextGeometryCache().get(font, state, text);
                    assertEquals(expected, List.of(actual.glyphs()), color + " glowing=" + glowing + " outline=" + outline);
                    assertEquals(-2.0f, actual.glyphs()[0].renderable().left(), "vanilla integer-centers odd widths");
                }
            }
        }
    }

    @Test
    void equalSignContentsSharePreparationAndLayoutChangesInvalidateIt() {
        FakeFont font = new FakeFont();
        SignRenderState state = state();
        SignText text = text(Component.literal("cached"), DyeColor.RED, true);
        SignTextGeometryCache cache = new SignTextGeometryCache();
        var first = cache.get(font, state, text);
        int preparations = font.preparations;
        assertSame(first, cache.get(font, state, text(Component.literal("cached"), DyeColor.RED, true)));
        state.lightCoords = 123;
        assertSame(first, cache.get(font, state, text), "light is supplied at replay, not baked into the cache");
        assertEquals(preparations, font.preparations);

        state.drawOutline = true;
        var outlined = cache.get(font, state, text);
        assertNotSame(first, outlined);
        assertEquals(Font.DisplayMode.NORMAL, outlined.glyphs()[0].mode());
        state.isTextFilteringEnabled = true;
        var filtered = cache.get(font, state, text);
        assertNotSame(outlined, filtered);
        state.maxTextLineWidth++;
        assertNotSame(filtered, cache.get(font, state, text));
    }

    @Test
    void alternatingEqualTextLayoutsRemainCached() {
        FakeFont font = new FakeFont();
        SignText text = text(Component.literal("shared"), DyeColor.RED, true);
        SignRenderState a = state(), b = state();
        b.drawOutline = true;
        b.maxTextLineWidth = 60;
        b.textLineHeight = 9;
        b.isTextFilteringEnabled = true;
        SignTextGeometryCache cache = new SignTextGeometryCache();
        var first = cache.get(font, a, text);
        var second = cache.get(font, b, text(Component.literal("shared"), DyeColor.RED, true));
        int preparations = font.preparations;
        for (int frame = 0; frame < 100; frame++) {
            assertSame(first, cache.get(font, a, text));
            assertSame(second, cache.get(font, b, text));
        }
        assertEquals(preparations, font.preparations);
    }

    @Test
    void changedWidthAndFontDoNotReuseSignTextsFormattedLineCache() {
        FakeFont font = new FakeFont();
        font.measureText = true;
        SignText text = text(Component.literal("abcdefghijkl"), DyeColor.BLACK, false);
        text.getRenderMessages(false, component -> FormattedCharSequence.EMPTY);
        SignRenderState state = state();
        state.maxTextLineWidth = 5;
        SignTextGeometryCache cache = new SignTextGeometryCache();
        var narrow = cache.get(font, state, text);
        assertEquals(-2.0f, narrow.glyphs()[0].renderable().left());
        state.maxTextLineWidth = 9;
        var wide = cache.get(font, state, text);
        assertEquals(-4.0f, wide.glyphs()[0].renderable().left());
        FakeFont replacement = new FakeFont();
        replacement.measureText = true;
        replacement.scale = 2;
        assertEquals(-9.0f, cache.get(replacement, state, text).glyphs()[0].renderable().left());
        state.maxTextLineWidth = 5;
        assertSame(narrow, cache.get(font, state, text));
    }

    @Test
    void fontPreparationCanReenterWithoutChangingTheOuterCacheKey() {
        SignTextGeometryCache cache = new SignTextGeometryCache();
        FakeFont outer = new FakeFont(), inner = new FakeFont();
        SignText a = text(Component.literal("outer"), DyeColor.BLACK, false);
        SignText b = text(Component.literal("inner"), DyeColor.RED, true);
        SignRenderState state = state();
        outer.onPrepare = () -> cache.get(inner, state, b);
        var first = cache.get(outer, state, a);
        int preparations = outer.preparations;
        assertSame(first, cache.get(outer, state, a));
        assertEquals(preparations, outer.preparations);
        int innerPreparations = inner.preparations;
        cache.get(inner, state, b);
        assertEquals(innerPreparations, inner.preparations);
    }

    @Test
    void releasedFontPagesInvalidatePreparedGlyphs() {
        FakeFont font = new FakeFont();
        SignText text = text(Component.literal("font reload"), DyeColor.BLACK, false);
        SignRenderState state = state();
        SignTextGeometryCache cache = new SignTextGeometryCache();
        var before = cache.get(font, state, text);
        AbstractTexture texture = new AbstractTexture() {};
        GlyphPageMirror page = new GlyphPageMirror(1024, 1024, true);
        DynamicTextureCache.onRegisterGlyphPage(Identifier.parse("minecraft:compat-test/sign-font"), texture, page);
        DynamicTextureCache.onReleaseGlyphPage(texture, page);
        assertNotSame(before, cache.get(font, state, text));
    }

    @Test
    void obfuscatedTextIsPreparedAgainInsteadOfFreezingItsGlyphs() {
        FakeFont font = new FakeFont();
        SignText text = text(Component.literal("animated").withStyle(Style.EMPTY.withObfuscated(true)), DyeColor.BLACK, false);
        SignTextGeometryCache cache = new SignTextGeometryCache();
        SignRenderState state = state();
        var first = cache.get(font, state, text);
        assertFalse(first.cacheable());
        assertNotSame(first, cache.get(font, state, text));
    }

    private static SignText text(Component first, DyeColor color, boolean glowing) {
        List<Component> lines = List.of(first, Component.empty(), Component.empty(), Component.empty());
        return new SignText(lines, lines, color, glowing);
    }

    private static SignRenderState state() {
        SignRenderState state = new SignRenderState();
        state.textLineHeight = 10;
        state.maxTextLineWidth = 90;
        return state;
    }

    /** Compare against Minecraft's own method, with only font rasterization replaced. */
    private static List<SignTextGeometryCache.Glyph> vanillaGlyphs(FakeFont font, SignRenderState state, SignText text) throws Exception {
        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Unsafe unsafe = (Unsafe) unsafeField.get(null);
        TestRenderer renderer = (TestRenderer) unsafe.allocateInstance(TestRenderer.class);
        Field fontField = AbstractSignRenderer.class.getDeclaredField("font");
        fontField.setAccessible(true);
        fontField.set(renderer, font);
        List<SignTextGeometryCache.Glyph> result = new ArrayList<>();
        SubmitNodeCollector collector = (SubmitNodeCollector) Proxy.newProxyInstance(
                SubmitNodeCollector.class.getClassLoader(), new Class<?>[] {SubmitNodeCollector.class},
                (proxy, method, args) -> {
                    assertEquals("submitText", method.getName());
                    FormattedCharSequence message = (FormattedCharSequence) args[3];
                    float x = (float) args[1], y = (float) args[2];
                    int color = (int) args[7], outline = (int) args[9];
                    if (outline != 0) {
                        font.prepare8xTextOutline(message, x, y, outline).visit(visitor(result, Font.DisplayMode.NORMAL));
                    }
                    font.prepareText(message, x, y, color, false, false, 0)
                            .visit(visitor(result, (Font.DisplayMode) args[5]));
                    return null;
                });
        Method submit = AbstractSignRenderer.class.getDeclaredMethod("submitSignText", SignRenderState.class,
                PoseStack.class, SubmitNodeCollector.class, SignText.class);
        submit.setAccessible(true);
        submit.invoke(renderer, state, new PoseStack(), collector, text);
        return result;
    }

    private static Font.GlyphVisitor visitor(List<SignTextGeometryCache.Glyph> result, Font.DisplayMode mode) {
        return new Font.GlyphVisitor() {
            @Override public void acceptRenderable(TextRenderable glyph) {
                result.add(new SignTextGeometryCache.Glyph(glyph, mode));
            }
        };
    }

    private static final class TestRenderer extends AbstractSignRenderer<SignRenderState> {
        private TestRenderer() { super(null); }
        @Override public SignRenderState createRenderState() { return state(); }
    }

    private static final class FakeFont extends Font {
        int preparations;
        boolean measureText;
        int scale = 1;
        Runnable onPrepare;
        FakeFont() {
            super(new Provider() {
                @Override public GlyphSource glyphs(FontDescription description) { return null; }
                @Override public EffectGlyph effect() { return null; }
            });
        }
        @Override public int width(FormattedCharSequence message) {
            if (!measureText) return 5;
            int[] count = {0};
            message.accept((index, style, codePoint) -> { count[0]++; return true; });
            return count[0] * scale;
        }
        @Override public List<FormattedCharSequence> split(FormattedText text, int width) {
            if (measureText) {
                String content = ((Component) text).getString();
                return List.of(Component.literal(content.substring(0, Math.min(width, content.length()))).getVisualOrderText());
            }
            return List.of(((Component) text).getVisualOrderText());
        }
        @Override public PreparedText prepareText(FormattedCharSequence text, float x, float y, int color,
                boolean shadow, boolean includeEmpty, int background) {
            if (onPrepare != null) {
                Runnable callback = onPrepare;
                onPrepare = null;
                callback.run();
            }
            return prepared(new FakeGlyph(x, y, color, false));
        }
        @Override public PreparedText prepare8xTextOutline(FormattedCharSequence text, float x, float y, int color) {
            return prepared(new FakeGlyph(x, y, color, true));
        }
        private PreparedText prepared(FakeGlyph glyph) {
            preparations++;
            return new PreparedText() {
                @Override public void visit(GlyphVisitor visitor) { visitor.acceptRenderable(glyph); }
                @Override public ScreenRectangle bounds() { return null; }
            };
        }
    }

    private record FakeGlyph(float left, float top, int color, boolean outline) implements TextRenderable {
        @Override public void render(Matrix4fc pose, VertexConsumer consumer, int light, boolean inverseDepth) {}
        @Override public RenderType renderType(Font.DisplayMode mode) { return null; }
        @Override public GpuTextureView textureView() { return null; }
        @Override public RenderPipeline guiPipeline() { return null; }
        @Override public float right() { return left + 5; }
        @Override public float bottom() { return top + 8; }
    }
}
