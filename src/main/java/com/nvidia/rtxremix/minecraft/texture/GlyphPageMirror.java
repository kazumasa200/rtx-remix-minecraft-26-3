/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryUtil;

/**
 * CPU copy of one font glyph-atlas page.
 *
 * <p>Minecraft assembles a {@code GlyphAtlasTexture} on the GPU: each glyph is uploaded
 * into its slot the first time it is rendered, from a source image that may be freed the
 * moment the upload returns, so the GL texture is the only complete copy of the page.
 * Remix needs the pixels to build a material for text quads, so this mirror follows
 * every glyph upload. {@code GlyphAtlasTextureMixin} opens an upload scope around the
 * glyph's {@code upload} call, {@code BitmapGlyphStagingMixin} stages bitmap glyphs, and
 * {@code CommandEncoderGlyphMirrorMixin} writes here the same rectangle that the command
 * encoder's {@code writeToTexture} and {@code copyBufferToTexture} hand to the GPU.
 *
 * <p>The mirror stores what the text shaders read, not what was uploaded. A colour page
 * is an RGBA8 texture and keeps the uploaded channels as {@code glTexSubImage2D}
 * converts them, missing colour channels zero and missing alpha opaque. An intensity
 * page is an R8 texture that {@code rendertype_text_intensity} samples as {@code .rrrr},
 * so red is spread across all four channels here.
 *
 * <p>Glyph uploads run only on the render thread ({@code NativeImage.uploadInternal}
 * asserts it) and every reader of {@link #data()} runs there too, so the page and the
 * upload scope carry no synchronisation.
 */
public final class GlyphPageMirror {

    private static final int CHANNELS = 4;

    /** Implemented by the glyph atlas texture, so the page is reachable from the texture object. */
    public interface Owner {
        GlyphPageMirror rtxremix$glyphPageMirror();
        void rtxremix$releaseGlyphPageMirror();
    }

    /**
     * Shared by every page so that a page re-created under the same texture id after a
     * font reload is newer than the page it replaces: its consumer must re-upload rather
     * than keep the predecessor's slots.
     */
    private static long nextVersion = 1L;
    private static GlyphPageMirror uploadTarget;
    private static Object stagingBuffer;
    private static long stagingPixels;
    private static int stagingWidth;
    private static int stagingChannels;

    private final boolean intensity;
    private final int width;
    private final int height;
    private final byte[] data;
    private long version;

    /**
     * @param width actual GPU page width, including atlas resizing by other mods
     * @param height actual GPU page height
     * @param intensity whether the page is an R8 texture read as coverage, which is the
     *                  case for a font whose glyphs report no colour, such as TrueType
     */
    public GlyphPageMirror(int width, int height, boolean intensity) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Invalid glyph page dimensions: " + width + "x" + height);
        }
        this.width = width;
        this.height = height;
        this.intensity = intensity;
        this.data = new byte[Math.multiplyExact(Math.multiplyExact(width, height), CHANNELS)];
    }

    public int width() { return width; }

    public int height() { return height; }

    /** Tightly packed RGBA rows. Mutated in place by {@link #blit}. */
    public byte[] data() {
        return data;
    }

    /** Advances with every glyph; 0 until the first glyph lands. */
    public long version() {
        return version;
    }

    /**
     * Copies one uploaded glyph into the page.
     *
     * @param x           slot column in the page
     * @param y           slot row in the page
     * @param width       uploaded rectangle width in pixels
     * @param height      uploaded rectangle height in pixels
     * @param channels    bytes per source pixel, in GL channel order: red, green, blue, alpha
     * @param src         source bytes, positioned at the rectangle's first pixel
     * @param srcRowPitch bytes from one source row to the next
     * @throws IllegalArgumentException if the rectangle does not fit the page; a slot always does
     */
    public void blit(int x, int y, int width, int height, int channels, ByteBuffer src, int srcRowPitch) {
        if (x < 0 || y < 0 || width < 0 || height < 0
                || width > this.width - x || height > this.height - y) {
            throw new IllegalArgumentException("glyph " + width + "x" + height + " at " + x + "," + y
                    + " does not fit a " + this.width + "x" + this.height + " page");
        }
        int srcRow = src.position();
        for (int row = 0; row < height; row++, srcRow += srcRowPitch) {
            int s = srcRow;
            int d = ((y + row) * this.width + x) * CHANNELS;
            for (int col = 0; col < width; col++, s += channels, d += CHANNELS) {
                int r = src.get(s) & 0xFF;
                int g = channels > 1 ? src.get(s + 1) & 0xFF : 0;
                int b = channels > 2 ? src.get(s + 2) & 0xFF : 0;
                int a = channels > 3 ? src.get(s + 3) & 0xFF : 0xFF;
                if (intensity) {
                    g = r;
                    b = r;
                    a = r;
                }
                data[d] = (byte) r;
                data[d + 1] = (byte) g;
                data[d + 2] = (byte) b;
                data[d + 3] = (byte) a;
            }
        }
        version = nextVersion++;
    }

    // ---- Upload scope ----

    /** Marks {@code page} as the destination of the glyph upload about to run. */
    public static void beginUpload(GlyphPageMirror page) {
        uploadTarget = page;
    }

    public static void endUpload() {
        uploadTarget = null;
        stagingBuffer = null;
        stagingPixels = 0L;
    }

    /**
     * Records the CPU image behind the GPU staging buffer a bitmap glyph is about to
     * copy from, so {@link #blitFromStaging} can read the same window. Bitmap fonts
     * stage their whole sheet once and copy each glyph out of it on the GPU, so the
     * copy itself carries no CPU pixels. Ignored outside a glyph upload.
     */
    public static void recordStaging(Object buffer, long pixels, int width, int channels) {
        if (uploadTarget == null) {
            return;
        }
        stagingBuffer = buffer;
        stagingPixels = pixels;
        stagingWidth = width;
        stagingChannels = channels;
    }

    /**
     * Mirrors a buffer-to-texture glyph copy whose source is the staging buffer
     * {@link #recordStaging} last saw; any other copy is ignored.
     */
    public static void blitFromStaging(Object buffer, int srcX, int srcY, int dstX, int dstY,
                                       int width, int height) {
        GlyphPageMirror page = uploadTarget;
        if (page == null || buffer != stagingBuffer || stagingPixels == 0L || width <= 0 || height <= 0) {
            return;
        }
        int rowPitch = stagingWidth * stagingChannels;
        long first = stagingPixels + (long) srcY * rowPitch + (long) srcX * stagingChannels;
        // The window's rows stride the whole sheet; the buffer spans only the bytes the
        // window touches, from its first pixel to the end of its last row.
        int span = (height - 1) * rowPitch + width * stagingChannels;
        page.blit(dstX, dstY, width, height, stagingChannels,
                MemoryUtil.memByteBuffer(first, span), rowPitch);
    }

    /** The page a glyph is being uploaded into, or null outside a glyph upload. */
    public static GlyphPageMirror uploadTarget() {
        return uploadTarget;
    }
}
