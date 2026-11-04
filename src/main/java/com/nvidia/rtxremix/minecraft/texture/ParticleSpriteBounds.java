/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import com.nvidia.rtxremix.HardcodedVertex;
import java.util.ArrayList;
import java.util.Collection;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/** Reload-scoped UV grid, constructed only for a generic extracted geometry source. */
public final class ParticleSpriteBounds {
    private static final int GRID = 32;
    private static final float EPSILON = 1e-6f;
    private final Bounds[][] cells;

    public static final class Bounds {
        final float u0, v0, u1, v1;
        final long packed;
        public Bounds(float u0, float v0, float u1, float v1) {
            this.u0 = u0; this.v0 = v0; this.u1 = u1; this.v1 = v1;
            packed = pack(u0, v0, u1, v1);
        }
    }

    public static ParticleSpriteBounds sprites(Collection<TextureAtlasSprite> sprites) {
        return new ParticleSpriteBounds(sprites.stream()
                .map(sprite -> new Bounds(sprite.getU0(), sprite.getV0(), sprite.getU1(), sprite.getV1())).toList());
    }

    @SuppressWarnings("unchecked")
    public ParticleSpriteBounds(Collection<Bounds> bounds) {
        ArrayList<Bounds>[] grid = new ArrayList[GRID * GRID];
        for (Bounds entry : bounds) {
            for (int y = cell(entry.v0); y <= cell(entry.v1); y++) {
                for (int x = cell(entry.u0); x <= cell(entry.u1); x++) {
                    int index = y * GRID + x;
                    if (grid[index] == null) grid[index] = new ArrayList<>();
                    grid[index].add(entry);
                }
            }
        }
        cells = new Bounds[grid.length][];
        for (int i = 0; i < grid.length; i++) if (grid[i] != null) cells[i] = grid[i].toArray(Bounds[]::new);
    }

    /** Finds the enclosing full sprite even when the source emits a cropped quad. */
    public long find(float u0, float v0, float u1, float v1) {
        Bounds[] candidates = cells[cell((v0 + v1) * .5f) * GRID + cell((u0 + u1) * .5f)];
        if (candidates != null) {
            for (Bounds entry : candidates) {
                if (u0 >= entry.u0 - EPSILON && v0 >= entry.v0 - EPSILON
                        && u1 <= entry.u1 + EPSILON && v1 <= entry.v1 + EPSILON) return entry.packed;
            }
        }
        return pack(u0, v0, u1, v1);
    }

    private static long pack(float u0, float v0, float u1, float v1) {
        return ParticleMaterialTextures.boundsKey(HardcodedVertex.packHalf2x16(u0, v0),
                HardcodedVertex.packHalf2x16(u1, v1));
    }

    private static int cell(float coordinate) { return Math.clamp((int) (coordinate * GRID), 0, GRID - 1); }
}
