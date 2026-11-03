/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.lod;

import com.nvidia.rtxremix.HardcodedVertex;
import com.nvidia.rtxremix.MeshInfoSurfaceTriangles;
import com.nvidia.rtxremix.minecraft.asset.RemixMaterial;
import com.nvidia.rtxremix.minecraft.asset.RemixMesh;
import com.nvidia.rtxremix.minecraft.asset.RemixMeshSurface;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import com.nvidia.rtxremix.minecraft.util.DirectBufferPool;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns one LOD section's quads into a Remix mesh in section-local space.
 *
 * <p>The input is Distant Horizons' 16-byte vertex, the layout Iris mirrors
 * and DH therefore keeps stable: unsigned 16-bit x, y and z in blocks from the
 * section's minimum corner (y from the level's minimum height), a light and
 * offset short, RGBA colour, a block material id, the face index (down, up,
 * north, south, west, east) and a texture tile id. Four vertices make one
 * axis-aligned quad.
 *
 * <p>Quads over chunks that Minecraft's own terrain covers are cut away, a
 * merged quad split along chunk borders, so the two never draw the same
 * ground. What the clip leaves overlapping is sunk just below Minecraft's
 * surfaces.
 */
final class LodMeshBuilder {

    static final int DH_VERTEX_BYTES = 16;
    static final int DH_QUAD_BYTES = 4 * DH_VERTEX_BYTES;
    /** {@code EDhApiBlockMaterial.WATER}. */
    static final int WATER_MATERIAL = 12;
    /** {@code EDhApiBlockMaterial.LAVA}. */
    static final int LAVA_MATERIAL = 6;
    /** {@code EDhApiBlockMaterial.ILLUMINATED}: blocks that give off light. */
    static final int ILLUMINATED_MATERIAL = 15;
    static final float SINK = 1f / 32f;
    /** Brings LOD water from the block top down to a still water surface. */
    static final float WATER_SINK = 0.125f;

    private static final int STRIDE = HardcodedVertex.STRIDE;
    private static final int INDEX_BYTES = 6 * Integer.BYTES;
    private static final int OPAQUE = 0xFF000000;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int NO_BOUNDS = 0;
    private static final int WATER_BOUNDS_MIN = HardcodedVertex.packHalf2x16(0f, 0f);
    private static final int WATER_BOUNDS_MAX = HardcodedVertex.packHalf2x16(1f, 1f);

    private static final int DOWN = 0;
    private static final int UP = 1;
    private static final int NORTH = 2;
    private static final int SOUTH = 3;
    private static final int WEST = 4;
    private static final int EAST = 5;

    /**
     * The chunks whose ground Minecraft draws: every chunk within {@code radius}
     * chunks of the centre chunk, measured between chunk positions. A negative
     * radius clips nothing.
     */
    /**
     * The chunks where Minecraft's own terrain is drawn, over a square window of them around the
     * camera. LOD terrain gives way there and nowhere else, so it neither lies over Minecraft's
     * terrain nor leaves a ring of neither where chunks have yet to load or a server sends fewer.
     */
    static final class Clip {
        static final Clip NONE = new Clip(0, 0, 0, new long[0]);

        private final int originX;
        private final int originZ;
        private final int size;
        private final long[] bits;

        private Clip(int originX, int originZ, int size, long[] bits) {
            this.originX = originX;
            this.originZ = originZ;
            this.size = size;
            this.bits = bits;
        }

        /** The chunks {@code drawn} accepts within {@code radius} chunks of a centre chunk, each way. */
        static Clip of(int centerX, int centerZ, int radius, ChunkTest drawn) {
            if (radius < 0) return NONE;
            int size = 2 * radius + 1;
            long[] bits = new long[(size * size + 63) >> 6];
            for (int z = 0; z < size; z++) {
                for (int x = 0; x < size; x++) {
                    if (drawn.test(centerX - radius + x, centerZ - radius + z)) {
                        int i = z * size + x;
                        bits[i >> 6] |= 1L << i;
                    }
                }
            }
            return new Clip(centerX - radius, centerZ - radius, size, bits);
        }

        /** Every chunk within a radius of a centre chunk. */
        static Clip disk(int centerX, int centerZ, int radius) {
            long squared = (long) radius * radius;
            return of(centerX, centerZ, radius, (x, z) -> {
                long dx = x - centerX;
                long dz = z - centerZ;
                return dx * dx + dz * dz <= squared;
            });
        }

        boolean clips(int chunkX, int chunkZ) {
            int x = chunkX - originX;
            int z = chunkZ - originZ;
            if (x < 0 || z < 0 || x >= size || z >= size) return false;
            int i = z * size + x;
            return (bits[i >> 6] & 1L << i) != 0;
        }

        /** Whether a range of chunks reaches into the window, where some may be clipped. */
        boolean reaches(int chunkX0, int chunkX1, int chunkZ0, int chunkZ1) {
            return chunkX1 >= originX && chunkX0 < originX + size
                    && chunkZ1 >= originZ && chunkZ0 < originZ + size;
        }

        /** Whether this clip removes every chunk of a section's footprint. */
        boolean covers(int minX, int minZ, int width) {
            int x0 = minX >> 4;
            int x1 = (minX + width - 1) >> 4;
            int z0 = minZ >> 4;
            int z1 = (minZ + width - 1) >> 4;
            if (x0 < originX || z0 < originZ || x1 >= originX + size || z1 >= originZ + size) return false;
            for (int cx = x0; cx <= x1; cx++) {
                for (int cz = z0; cz <= z1; cz++) {
                    if (!clips(cx, cz)) return false;
                }
            }
            return true;
        }

        /** Whether this clip removes any chunk of a section's footprint. */
        boolean intersects(int minX, int minZ, int width) {
            return signature(minX, minZ, width) != 0;
        }

        /**
         * Identifies which chunks of a section's footprint this clip removes;
         * 0 when it removes none, so equal signatures build equal meshes.
         */
        long signature(int minX, int minZ, int width) {
            int x0 = Math.max(minX >> 4, originX);
            int x1 = Math.min((minX + width - 1) >> 4, originX + size - 1);
            int z0 = Math.max(minZ >> 4, originZ);
            int z1 = Math.min((minZ + width - 1) >> 4, originZ + size - 1);
            long hash = 0;
            for (int cx = x0; cx <= x1; cx++) {
                for (int cz = z0; cz <= z1; cz++) {
                    if (clips(cx, cz)) {
                        hash = hash * 0x9E3779B97F4A7C15L + (((long) cx << 32) ^ (cz & 0xFFFFFFFFL)) + 1;
                    }
                }
            }
            return hash;
        }
    }

    /** Whether Minecraft's terrain draws a chunk column. */
    @FunctionalInterface
    interface ChunkTest {
        boolean test(int chunkX, int chunkZ);
    }

    private LodMeshBuilder() {}

    /**
     * Builds the mesh, or returns null when the clip removes every quad.
     * {@code water}, {@code glass}, {@code glow} and {@code lamp} may be null,
     * which draws those quads as plain ground. Lava takes {@code glow}, every
     * other block that gives off light {@code lamp}.
     */
    static RemixMesh build(LodGeometry geometry, Clip clip, int originX, int originZ,
                           RemixMaterial solid, RemixMaterial water,
                           RemixMaterial glass, RemixMaterial glow, RemixMaterial lamp) {
        Bucket ground = new Bucket(Paint.VERTEX);
        Bucket waterBucket = water != null ? new Bucket(Paint.WATER) : ground;
        Bucket glassBucket = glass != null ? new Bucket(Paint.GLASS) : ground;
        Bucket glowBucket = glow != null ? new Bucket(Paint.PALETTE) : ground;
        Bucket lampBucket = lamp != null ? new Bucket(Paint.PALETTE) : ground;
        try {
            convert(geometry, clip, originX, originZ, ground, waterBucket, glassBucket, glowBucket,
                    lampBucket);
            List<RemixMeshSurface> surfaces = new ArrayList<>(5);
            ground.addSurface(surfaces, solid);
            if (waterBucket != ground) waterBucket.addSurface(surfaces, water);
            if (glassBucket != ground) glassBucket.addSurface(surfaces, glass);
            if (glowBucket != ground) glowBucket.addSurface(surfaces, glow);
            if (lampBucket != ground) lampBucket.addSurface(surfaces, lamp);
            return surfaces.isEmpty() ? null : new RemixMesh(surfaces.toArray(new RemixMeshSurface[0]));
        } finally {
            ground.release();
            waterBucket.release();
            glassBucket.release();
            glowBucket.release();
            lampBucket.release();
        }
    }

    static void convert(LodGeometry geometry, Clip clip, int originX, int originZ,
                        Bucket ground, Bucket water) {
        convert(geometry, clip, originX, originZ, ground, water, ground, ground, ground);
    }

    static void convert(LodGeometry geometry, Clip clip, int originX, int originZ,
                        Bucket ground, Bucket water, Bucket glass, Bucket glow, Bucket lamp) {
        ByteBuffer data = geometry.data.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        for (int quad = 0; quad < geometry.quadCount; quad++) {
            int base = quad * DH_QUAD_BYTES;
            int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE;
            int x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
            for (int v = 0; v < 4; v++) {
                int at = base + v * DH_VERTEX_BYTES;
                int x = data.getShort(at) & 0xFFFF;
                int y = data.getShort(at + 2) & 0xFFFF;
                int z = data.getShort(at + 4) & 0xFFFF;
                x0 = Math.min(x0, x); x1 = Math.max(x1, x);
                y0 = Math.min(y0, y); y1 = Math.max(y1, y);
                z0 = Math.min(z0, z); z1 = Math.max(z1, z);
            }
            int color = OPAQUE
                    | (data.get(base + 8) & 0xFF) << 16
                    | (data.get(base + 9) & 0xFF) << 8
                    | (data.get(base + 10) & 0xFF);
            int material = data.get(base + 12);
            int face = data.get(base + 13);
            if (face < DOWN || face > EAST) continue;

            Bucket bucket;
            if (material == WATER_MATERIAL) {
                bucket = water;
            } else if (material == LAVA_MATERIAL) {
                bucket = glow;
            } else if (material == ILLUMINATED_MATERIAL) {
                bucket = lamp;
            } else if (quad >= geometry.opaqueQuads) {
                bucket = glass;
            } else {
                bucket = ground;
            }
            if (bucket.paint == Paint.GLASS) color = LodPalette.glassTint(color);
            QuadRect rect = new QuadRect(face, originX + x0, originX + x1, y0, y1,
                    originZ + z0, originZ + z1);
            emitClipped(rect, clip, originX, originZ, color, bucket);
        }
    }

    /** One decoded quad in world block coordinates (y stays section-local). */
    private record QuadRect(int face, int x0, int x1, int y0, int y1, int z0, int z1) {}

    private static void emitClipped(QuadRect q, Clip clip, int originX, int originZ,
                                    int color, Bucket bucket) {
        boolean horizontal = q.face == DOWN || q.face == UP;
        boolean alongX = horizontal || q.face == NORTH || q.face == SOUTH;
        // Cells run along the quad's horizontal extent; rows are the other horizontal axis.
        int runMin = alongX ? q.x0 : q.z0;
        int runMax = alongX ? q.x1 : q.z1;
        int rowMin;
        int rowMax;
        if (horizontal) {
            rowMin = q.z0;
            rowMax = q.z1;
        } else {
            // A side face belongs to the block behind it: a face looking toward
            // negative coordinates sits on its block's minimum edge.
            int plane = alongX ? q.z0 : q.x0;
            int owner = (q.face == NORTH || q.face == WEST) ? plane : plane - 1;
            rowMin = owner;
            rowMax = owner + 1;
        }
        int cellRun0 = runMin >> 4;
        int cellRun1 = (runMax - 1) >> 4;
        int cellRow0 = rowMin >> 4;
        int cellRow1 = (rowMax - 1) >> 4;

        if (alongX ? !clip.reaches(cellRun0, cellRun1, cellRow0, cellRow1)
                : !clip.reaches(cellRow0, cellRow1, cellRun0, cellRun1)) {
            emit(q, q.x0, q.x1, q.z0, q.z1, originX, originZ, color, bucket);
            return;
        }

        for (int row = cellRow0; row <= cellRow1; row++) {
            int run = cellRun0;
            while (run <= cellRun1) {
                if (clipsCell(clip, alongX, run, row)) {
                    run++;
                    continue;
                }
                int end = run;
                while (end + 1 <= cellRun1 && !clipsCell(clip, alongX, end + 1, row)) end++;
                int a0 = Math.max(runMin, run << 4);
                int a1 = Math.min(runMax, (end + 1) << 4);
                if (horizontal) {
                    int b0 = Math.max(q.z0, row << 4);
                    int b1 = Math.min(q.z1, (row + 1) << 4);
                    emit(q, a0, a1, b0, b1, originX, originZ, color, bucket);
                } else if (alongX) {
                    emit(q, a0, a1, q.z0, q.z1, originX, originZ, color, bucket);
                } else {
                    emit(q, q.x0, q.x1, a0, a1, originX, originZ, color, bucket);
                }
                run = end + 1;
            }
        }
    }

    private static boolean clipsCell(Clip clip, boolean alongX, int run, int row) {
        return alongX ? clip.clips(run, row) : clip.clips(row, run);
    }

    private static void emit(QuadRect q, int x0, int x1, int z0, int z1,
                             int originX, int originZ, int color, Bucket bucket) {
        float lx0 = x0 - originX;
        float lx1 = x1 - originX;
        float lz0 = z0 - originZ;
        float lz1 = z1 - originZ;
        float sink = bucket.water ? SINK + WATER_SINK : SINK;
        float ly0 = q.y0 - sink;
        float ly1 = q.y1 - sink;
        switch (q.face) {
            case DOWN, UP -> bucket.quad(lx0, ly0, lz0, lx1, ly0, lz0, lx1, ly0, lz1, lx0, ly0, lz1,
                    0f, q.face == UP ? 1f : -1f, 0f, color, originX, originZ);
            case NORTH, SOUTH -> bucket.quad(lx0, ly0, lz0, lx1, ly0, lz0, lx1, ly1, lz0, lx0, ly1, lz0,
                    0f, 0f, q.face == SOUTH ? 1f : -1f, color, originX, originZ);
            default -> bucket.quad(lx0, ly0, lz0, lx0, ly0, lz1, lx0, ly1, lz1, lx0, ly1, lz0,
                    q.face == EAST ? 1f : -1f, 0f, 0f, color, originX, originZ);
        }
    }

    /** Where a surface's colour comes from. */
    enum Paint {
        /** The baked vertex colour over one white texel. */
        VERTEX,
        /** The water texture at world-space coordinates. */
        WATER,
        /** The colour's {@link LodPalette} texel, which emission can read. */
        PALETTE,
        /** The {@link LodPalette} texel of the colour's glass tint, read as transmittance. */
        GLASS
    }

    /** Growable vertex and index data for one surface, from {@link DirectBufferPool}. */
    static final class Bucket {
        final Paint paint;
        final boolean water;
        ByteBuffer vertices;
        ByteBuffer indices;
        int quads;

        Bucket(Paint paint) {
            this.paint = paint;
            this.water = paint == Paint.WATER;
        }

        Bucket(boolean water) {
            this(water ? Paint.WATER : Paint.VERTEX);
        }

        void quad(float ax, float ay, float az, float bx, float by, float bz,
                  float cx, float cy, float cz, float dx, float dy, float dz,
                  float nx, float ny, float nz, int color, int originX, int originZ) {
            ensureCapacity();
            int vertex = quads * 4;
            vertex(vertex, ax, ay, az, nx, ny, nz, color, originX, originZ);
            vertex(vertex + 1, bx, by, bz, nx, ny, nz, color, originX, originZ);
            vertex(vertex + 2, cx, cy, cz, nx, ny, nz, color, originX, originZ);
            vertex(vertex + 3, dx, dy, dz, nx, ny, nz, color, originX, originZ);
            int at = quads * INDEX_BYTES;
            indices.putInt(at, vertex);
            indices.putInt(at + 4, vertex + 1);
            indices.putInt(at + 8, vertex + 2);
            indices.putInt(at + 12, vertex);
            indices.putInt(at + 16, vertex + 2);
            indices.putInt(at + 20, vertex + 3);
            quads++;
        }

        private void vertex(int index, float x, float y, float z, float nx, float ny, float nz,
                            int color, int originX, int originZ) {
            int at = index * STRIDE;
            vertices.putFloat(at, x);
            vertices.putFloat(at + 4, y);
            vertices.putFloat(at + 8, z);
            vertices.putFloat(at + 12, nx);
            vertices.putFloat(at + 16, ny);
            vertices.putFloat(at + 20, nz);
            switch (paint) {
                case WATER -> {
                    // World-space UVs, as Minecraft's own water gets them.
                    float tile = BlockTextureManager.getWaterTileSize();
                    vertices.putFloat(at + 24, (x + originX) / tile);
                    vertices.putFloat(at + 28, (z + originZ) / tile);
                }
                case PALETTE, GLASS -> {
                    vertices.putFloat(at + 24, LodPalette.u(color));
                    vertices.putFloat(at + 28, LodPalette.v(color));
                }
                // The ground material's texture is one white texel.
                case VERTEX -> {
                    vertices.putFloat(at + 24, 0.5f);
                    vertices.putFloat(at + 28, 0.5f);
                }
            }
            vertices.putInt(at + 32, paint == Paint.PALETTE || paint == Paint.GLASS ? WHITE : color);
            vertices.putInt(at + 36, water ? WATER_BOUNDS_MIN : NO_BOUNDS);
            vertices.putInt(at + 40, water ? WATER_BOUNDS_MAX : NO_BOUNDS);
            for (int pad = 44; pad < STRIDE; pad += 4) vertices.putInt(at + pad, 0);
        }

        private void ensureCapacity() {
            vertices = grow(vertices, (quads + 1) * 4 * STRIDE, quads * 4 * STRIDE);
            indices = grow(indices, (quads + 1) * INDEX_BYTES, quads * INDEX_BYTES);
        }

        private static ByteBuffer grow(ByteBuffer buffer, int needed, int used) {
            if (buffer != null && buffer.capacity() >= needed) return buffer;
            int capacity = Math.max(needed, buffer == null ? 64 * 1024 : buffer.capacity() * 2);
            ByteBuffer grown = DirectBufferPool.acquire(capacity).order(ByteOrder.LITTLE_ENDIAN);
            if (buffer != null) {
                grown.put(0, buffer, 0, used);
                DirectBufferPool.release(buffer);
            }
            return grown;
        }

        /** Hands the data to a new surface, which then owns it. */
        void addSurface(List<RemixMeshSurface> surfaces, RemixMaterial material) {
            if (quads == 0) return;
            RemixMeshSurface surface = new RemixMeshSurface(material)
                    .setVertexData(vertices, quads * 4)
                    .setIndexData(indices, quads * 6);
            if (water) surface.setFlags(MeshInfoSurfaceTriangles.TEXCOORD_BOUNDS);
            surfaces.add(surface);
            vertices = null;
            indices = null;
        }

        void release() {
            DirectBufferPool.release(vertices);
            DirectBufferPool.release(indices);
            vertices = null;
            indices = null;
        }
    }
}
