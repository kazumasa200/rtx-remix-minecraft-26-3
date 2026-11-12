/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.lod;

import com.nvidia.rtxremix.HardcodedVertex;
import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.TextureBatchEntry;
import com.nvidia.rtxremix.TextureFormat;
import com.nvidia.rtxremix.TexturePatch;
import com.nvidia.rtxremix.TexturePatchInfo;
import com.nvidia.rtxremix.TexturePurpose;
import com.nvidia.rtxremix.minecraft.asset.RemixTexture;
import com.nvidia.rtxremix.minecraft.asset.RemixTextureStore;
import com.nvidia.rtxremix.minecraft.util.DirectBufferPool;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * The LOD mod's block-face tiles as one texture, so LOD terrain shows the textures the LOD
 * mod itself draws (#25).
 *
 * <p>A tile is 16 by 16 texels, each the ratio of the face's colour there to its average,
 * 127.5 meaning the average, with an alpha that blends between the ratio and the plain
 * average. The LOD mod draws a texel as {@code clamp(vertexColour * ratio * 2)}. The
 * runtime only multiplies a vertex colour by a texture, which holds at most 1, so each tile
 * is stored divided by its largest factor, its {@linkplain #scale scale}, and quads that use
 * it carry their colour times that scale instead.
 *
 * <p>Tile 0 is the LOD mod's untextured tile, which stores the average everywhere and is
 * white here, so untextured quads read it and keep their plain colour.
 */
public final class LodTiles {

    /** Reads the LOD mod's tiles; implemented by the LOD mod's compatibility class. */
    public interface Source {
        /** How many tiles the LOD mod holds. */
        int tileCount();

        /** Changes whenever the LOD mod drops its tiles and numbers them anew. */
        int generation();

        /** Copies references to tiles {@code first} onward into {@code out}; returns how many. */
        int tiles(int first, byte[][] out);
    }

    static final int TILE = 16;
    static final int TILES_PER_ROW = 256;
    static final int ROWS = 64;
    /** Tiles past this draw untextured; the LOD mod dedupes faces by content, so few packs come near. */
    static final int CAPACITY = TILES_PER_ROW * ROWS;
    static final int WIDTH = TILES_PER_ROW * TILE;
    static final int HEIGHT = ROWS * TILE;
    private static final int TILE_BYTES = TILE * TILE * 4;
    private static final int ROW_BYTES = WIDTH * 4;

    private final Source source;
    private final byte[][] batch = new byte[TILES_PER_ROW][];
    private ByteBuffer pixels;
    private RemixTexture texture;
    /** Tiles converted into {@link #pixels} and uploaded. Render thread. */
    private int known;
    private int knownGeneration;
    private float[] scales = {1f};
    /** What mesh builders read: the scales of every uploaded tile. */
    private volatile float[] published = {1f};

    LodTiles(Source source) {
        this.source = source;
    }

    /**
     * Render thread, before meshes are built: uploads every tile the LOD mod added since the
     * last call. The LOD mod registers a face's tile before it builds the quads that name it,
     * so every quad queued for a build reads a tile already uploaded. Returns whether the
     * LOD mod's tiles started over, which leaves built meshes naming tiles that changed.
     */
    boolean refresh(RemixAPI api) {
        if (texture == null || texture.isDeleted()) {
            if (!create()) return false;
        }
        // Read before the count: tiles numbered anew after this read are caught next time.
        int generation = source.generation();
        int count = Math.min(source.tileCount(), CAPACITY);
        // The count alone misses a restart whose tiles outnumber the old ones by the time
        // this runs, as after a reload screen, and the old pixels stayed under new numbers.
        boolean restarted = count < known || generation != knownGeneration;
        knownGeneration = generation;
        if (restarted) {
            known = 0;
            scales = new float[] {1f};
            published = scales;
        }
        if (count <= known) return restarted;

        if (scales.length < count) scales = Arrays.copyOf(scales, Math.max(count, scales.length * 2));
        int firstRow = known / TILES_PER_ROW;
        while (known < count) {
            int n = source.tiles(known, batch);
            if (n <= 0) break;
            for (int i = 0; i < n && known < count; i++, known++) {
                byte[] tile = batch[i];
                scales[known] = tile != null && tile.length == TILE_BYTES ? write(known, tile) : write(known, null);
                batch[i] = null;
            }
        }
        int lastRow = (known - 1) / TILES_PER_ROW;
        upload(api, firstRow, lastRow);
        published = Arrays.copyOf(scales, known);
        return restarted;
    }

    /** Any thread. The scales of the uploaded tiles; a tile past the end is drawn as tile 0. */
    float[] scales() {
        return published;
    }

    RemixTexture texture() {
        return texture;
    }

    /** The tile a quad reads: its own when uploaded, otherwise the untextured tile 0. */
    static int usable(int tile, float[] scales) {
        return tile > 0 && tile < scales.length ? tile : 0;
    }

    static float u0(int tile) {
        return (tile % TILES_PER_ROW) * TILE / (float) WIDTH;
    }

    static float v0(int tile) {
        return (tile / TILES_PER_ROW) * TILE / (float) HEIGHT;
    }

    static final float TILE_U = TILE / (float) WIDTH;
    static final float TILE_V = TILE / (float) HEIGHT;

    /** The tile's texture-coordinate bounds, which the runtime wraps a quad's coordinates into. */
    static int boundsMin(int tile) {
        return HardcodedVertex.packHalf2x16(u0(tile), v0(tile));
    }

    static int boundsMax(int tile) {
        return HardcodedVertex.packHalf2x16(u0(tile) + TILE_U, v0(tile) + TILE_V);
    }

    /** Converts one tile into {@link #pixels}; returns its scale. A missing tile is white. */
    private float write(int id, byte[] tile) {
        float scale = tile != null ? scale(tile) : 1f;
        int x0 = (id % TILES_PER_ROW) * TILE;
        int y0 = (id / TILES_PER_ROW) * TILE;
        for (int y = 0; y < TILE; y++) {
            int row = (y0 + y) * ROW_BYTES + x0 * 4;
            for (int x = 0; x < TILE; x++) {
                int at = row + x * 4;
                int src = (y * TILE + x) * 4;
                for (int c = 0; c < 3; c++) {
                    float factor = tile != null ? factor(tile, src, c) : 1f;
                    pixels.put(at + c, (byte) Math.round(Math.min(1f, factor / scale) * 255f));
                }
                pixels.put(at + 3, (byte) -1);
            }
        }
        return scale;
    }

    /** What the LOD mod multiplies the vertex colour by at one channel of one texel. */
    static float factor(byte[] tile, int texel, int channel) {
        float ratio = (tile[texel + channel] & 0xFF) / 127.5f;
        float alpha = (tile[texel + 3] & 0xFF) / 255f;
        return 1f + (ratio - 1f) * alpha;
    }

    /** The tile's largest factor, at least 1 so a quad's colour never darkens to make room. */
    static float scale(byte[] tile) {
        float max = 1f;
        for (int texel = 0; texel < TILE_BYTES; texel += 4) {
            for (int c = 0; c < 3; c++) max = Math.max(max, factor(tile, texel, c));
        }
        return max;
    }

    /** A quad's colour for a tile of this scale: brighter by the scale, each channel capped at 1. */
    static int scaled(int argb, float scale) {
        if (scale == 1f) return argb;
        int r = Math.min(255, Math.round((argb >> 16 & 0xFF) * scale));
        int g = Math.min(255, Math.round((argb >> 8 & 0xFF) * scale));
        int b = Math.min(255, Math.round((argb & 0xFF) * scale));
        return argb & 0xFF000000 | r << 16 | g << 8 | b;
    }

    private boolean create() {
        if (pixels == null) {
            pixels = DirectBufferPool.acquire(WIDTH * HEIGHT * 4);
            for (int i = 0; i < WIDTH * HEIGHT * 4; i++) pixels.put(i, (byte) -1);
            pixels.limit(WIDTH * HEIGHT * 4);
        }
        // The store hands the creation data back to the pool once the texture exists, so it
        // gets a copy: the converted tiles stay here for the patches that follow.
        int size = WIDTH * HEIGHT * 4;
        ByteBuffer initial = DirectBufferPool.acquire(size);
        initial.put(0, pixels, 0, size).limit(size);
        try {
            texture = RemixTextureStore.get().create(new RemixTexture(WIDTH, HEIGHT,
                    TextureFormat.RGBA8_UNORM, TexturePurpose.COLOR, initial));
        } catch (RuntimeException e) {
            texture = null;
            return false;
        }
        // The texture is made from every tile converted so far.
        return true;
    }

    private void upload(RemixAPI api, int firstRow, int lastRow) {
        TexturePatch patch = new TexturePatch();
        patch.dstX = 0;
        patch.dstY = firstRow * TILE;
        patch.width = WIDTH;
        patch.height = (lastRow - firstRow + 1) * TILE;
        patch.rowPitch = ROW_BYTES;
        patch.format = TextureFormat.RGBA8_UNORM;
        patch.pixelData = pixels.duplicate().position(patch.dstY * ROW_BYTES)
                .limit((patch.dstY + patch.height) * ROW_BYTES).slice();
        TextureBatchEntry entry = new TextureBatchEntry();
        entry.handle = texture.getHandle();
        entry.patches = new TexturePatch[] {patch};
        TexturePatchInfo info = new TexturePatchInfo();
        info.entries = new TextureBatchEntry[] {entry};
        api.updateTextureFromMemory(info);
    }

    /** Render thread. Drops the texture; the converted tiles stay for the next one. */
    void destroy() {
        if (texture != null && !texture.isDeleted()) texture.destroy();
        texture = null;
    }
}
