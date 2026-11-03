/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LayerLightEventListener;

/**
 * Leaves sections of unlit rock out of distant columns' meshes while the camera is above
 * ground (#21). A section is left out when it and its six neighbours hold no sky light and no
 * fluid under the sky: light inside a solid block reads zero, so a section is judged with its
 * neighbours or the ground under open air would go, and the fluid keeps the sea floor, which
 * the light under deep water no longer reaches. Block light does not count: a cave lit by lava
 * or glow lichen is no more seen from above than a dark one. Columns within {@link #NEAR_CHUNKS} of the camera
 * keep everything, so a cave mouth beside the camera stays whole.
 *
 * <p>Render thread only: it reads client light.
 */
final class DeepCaveCull {
    static final boolean ENABLED = Boolean.parseBoolean(
            System.getProperty("rtxremix.chunk.deepCaveCull", "true"));
    static final int NEAR_CHUNKS = Math.max(1,
            Integer.getInteger("rtxremix.chunk.deepCaveNearChunks", 4));
    /** Blocks of ground over the camera that count as underground. */
    private static final int UNDERGROUND_DEPTH = 8;
    /** Frames a new underground state must hold: going down restores caves soon, coming up culls late. */
    private static final int ENTER_FRAMES = 10;
    private static final int LEAVE_FRAMES = 300;

    /** Whether one section holds no sky light and no fluid under the sky. */
    interface Darkness {
        boolean dark(int cx, int sy, int cz);
    }

    private boolean underground;
    private int heldFrames;

    /** Feeds this frame's camera state; true when the debounced state flips. */
    boolean updateUnderground(boolean cameraUnderground) {
        if (cameraUnderground == underground) {
            heldFrames = 0;
            return false;
        }
        if (++heldFrames < (cameraUnderground ? ENTER_FRAMES : LEAVE_FRAMES)) return false;
        underground = cameraUnderground;
        heldFrames = 0;
        return true;
    }

    boolean active() {
        return ENABLED && !underground;
    }

    boolean underground() {
        return underground;
    }

    static boolean near(int cx, int cz, int pcx, int pcz) {
        return Math.max(Math.abs((long) cx - pcx), Math.abs((long) cz - pcz)) <= NEAR_CHUNKS;
    }

    static boolean culled(Darkness darkness, int cx, int sy, int cz) {
        return darkness.dark(cx, sy, cz)
                && darkness.dark(cx, sy + 1, cz) && darkness.dark(cx, sy - 1, cz)
                && darkness.dark(cx + 1, sy, cz) && darkness.dark(cx - 1, sy, cz)
                && darkness.dark(cx, sy, cz + 1) && darkness.dark(cx, sy, cz - 1);
    }

    /**
     * Underground when the camera stands in no sky light, or under {@link #UNDERGROUND_DEPTH}
     * blocks of ground: near a cave's mouth the sky still reaches in.
     */
    static boolean cameraUnderground(ClientLevel level, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        if (level.getBrightness(LightLayer.SKY, pos) == 0) return true;
        return y < level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - UNDERGROUND_DEPTH;
    }

    /** Reads {@link Darkness} from the client's chunks and light. */
    static final class LevelDarkness implements Darkness {
        private final ClientLevel level;
        private final LayerLightEventListener sky;

        LevelDarkness(ClientLevel level) {
            this.level = level;
            this.sky = level.getLightEngine().getLayerListener(LightLayer.SKY);
        }

        @Override
        public boolean dark(int cx, int sy, int cz) {
            if (sy < level.getMinSectionY()) return true;
            if (sy > level.getMaxSectionY()) return false;
            LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, false);
            if (chunk == null || fluidUnderSky(chunk, cx, sy, cz)) return false;
            return skyUnlit(cx, sy, cz, sky.getDataLayerData(SectionPos.of(cx, sy, cz)));
        }

        /**
         * Whether fluid runs up from this section through sections holding fluid into one the
         * sky lights: the floor under deep water, which the sky's light no longer reaches but
         * the camera sees through the water. Water held in rock underground does not count.
         */
        private boolean fluidUnderSky(LevelChunk chunk, int cx, int sy, int cz) {
            int top = level.getMaxSectionY();
            for (int y = sy; y <= top; y++) {
                if (!chunk.getSection(chunk.getSectionIndexFromSectionY(y)).hasFluid()) return false;
                if (y > sy && !skyUnlit(cx, y, cz, sky.getDataLayerData(SectionPos.of(cx, y, cz)))) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Most sections deep in rock hold no sky layer: vanilla reads such a section as the
         * bottom row of the first layer above it, and as full sky when there is none.
         */
        private boolean skyUnlit(int cx, int sy, int cz, DataLayer layer) {
            int top = level.getMaxSectionY() + 1;
            while (layer == null) {
                if (++sy > top) return false;
                layer = sky.getDataLayerData(SectionPos.of(cx, sy, cz));
                if (layer != null) return bottomUnlit(layer);
            }
            return unlit(layer);
        }

        private static boolean bottomUnlit(DataLayer layer) {
            if (layer.isDefinitelyHomogenous()) return layer.isDefinitelyFilledWith(0);
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    if (layer.get(x, 0, z) != 0) return false;
                }
            }
            return true;
        }

        private static boolean unlit(DataLayer layer) {
            if (layer.isDefinitelyHomogenous()) return layer.isDefinitelyFilledWith(0);
            for (byte b : layer.getData()) {
                if (b != 0) return false;
            }
            return true;
        }
    }
}
