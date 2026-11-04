/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.capture;

import com.nvidia.rtxremix.minecraft.asset.SceneSpace;
import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockQuadEmitterSharedFaceTest {

    static {
        // Before the block fields below: the block registry refuses to load unbootstrapped.
        try {
            SharedConstants.getCurrentVersion();
        } catch (IllegalStateException unset) {
            SharedConstants.tryDetectVersion();
        }
        Bootstrap.bootStrap();
    }

    @AfterEach
    void restoreCutoutLeaves() {
        LeavesBlock.setCutoutLeaves(true);
    }

    private static final BlockState LEAVES = Blocks.OAK_LEAVES.defaultBlockState();
    private static final BlockState GLASS = Blocks.GLASS.defaultBlockState();
    private static final BlockState BOTTOM_SLAB =
            Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM);

    @Test
    void leavesAndGlassMeetASlabSide() {
        assertTrue(BlockQuadEmitter.meetsNeighbourFace(LEAVES, BOTTOM_SLAB, Direction.EAST));
        assertTrue(BlockQuadEmitter.meetsNeighbourFace(GLASS, BOTTOM_SLAB, Direction.NORTH));
    }

    @Test
    void waterMeetsADirtPathSide() {
        // The water's side face lies on the path's own; it moves into the path (#20).
        assertTrue(BlockQuadEmitter.meetsNeighbourFace(
                Blocks.WATER.defaultBlockState(), Blocks.DIRT_PATH.defaultBlockState(), Direction.EAST));
    }

    @Test
    void touchingCutoutLeavesMeetEachOther() {
        assertTrue(BlockQuadEmitter.meetsNeighbourFace(LEAVES, LEAVES, Direction.UP));
    }

    @Test
    void fastLeavesCullTheFaceBetweenThem() {
        LeavesBlock.setCutoutLeaves(false);
        assertFalse(BlockQuadEmitter.meetsNeighbourFace(LEAVES, LEAVES, Direction.UP));
    }

    @Test
    void nothingMovesWhereOnlyOneFaceIsDrawn() {
        // Air draws nothing, and like glass hides the face between two of its kind.
        assertFalse(BlockQuadEmitter.meetsNeighbourFace(LEAVES, Blocks.AIR.defaultBlockState(), Direction.WEST));
        assertFalse(BlockQuadEmitter.meetsNeighbourFace(GLASS, GLASS, Direction.SOUTH));
        // A slab occludes, so its own side never moves.
        assertFalse(BlockQuadEmitter.meetsNeighbourFace(BOTTOM_SLAB, LEAVES, Direction.WEST));
    }

    @Test
    void seeThroughBlocksKeepTheirFaceAgainstASolidBlock() {
        BlockState stone = Blocks.STONE.defaultBlockState();
        assertTrue(BlockQuadEmitter.touchesSolidBlock(Blocks.STAINED_GLASS.blue().defaultBlockState(), stone));
        assertTrue(BlockQuadEmitter.touchesSolidBlock(Blocks.ICE.defaultBlockState(), stone));
        assertTrue(BlockQuadEmitter.touchesSolidBlock(GLASS, stone));
        // Glass against glass is culled as before, and a solid block never keeps a hidden face.
        assertFalse(BlockQuadEmitter.touchesSolidBlock(GLASS, GLASS));
        assertFalse(BlockQuadEmitter.touchesSolidBlock(stone, stone));
    }

    @Test
    void contactFaceInsetOutlastsTheRuntimesRayOffset() {
        // calculateRayOffset in dxvk-remix: four float steps of the largest coordinate. Scene
        // coordinates reach the rebase distance plus a 32-chunk view; past the inset, a ray
        // restarted at the stone's face would land inside the glass or beyond its kept face.
        float largestOffset = (SceneSpace.REBASE_DISTANCE + 32 * 16) * 0x1p-23f * 4;
        assertTrue(BlockQuadEmitter.CONTACT_FACE_INSET > 2 * largestOffset);
    }
}
