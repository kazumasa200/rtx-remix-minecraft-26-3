/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.asset;

import com.nvidia.rtxremix.Transform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SceneSpaceTest {
    private static final SceneSpace.Origin ZERO = new SceneSpace.Origin(0, 0, 0, false);

    @AfterEach
    void backToSpawn() {
        SceneSpace.setDebugOffset(false);
        SceneSpace.advance(0, 0);
    }

    @Test
    void nearSpawnTheOriginStaysAtZeroAndPositionsAreUnchanged() {
        assertSame(ZERO, SceneSpace.next(ZERO, 6000.5, -6100.25, false));
        assertEquals((float) 2830.731, ZERO.x(2830.731));
        assertEquals((float) -1829.5, ZERO.z(-1829.5));
    }

    @Test
    void farFromItTheOriginSnapsToTheCameraAndHoldsUntilItDriftsAway() {
        SceneSpace.Origin far = SceneSpace.next(ZERO, 41746.4, 41564.3, false);
        assertEquals(40960, far.x());
        assertEquals(40960, far.z());
        assertEquals(1, far.epoch());
        assertTrue(Math.abs(far.x(41746.4)) <= SceneSpace.SNAP / 2.0);

        assertSame(far, SceneSpace.next(far, 41746.4 + 5000, 41564.3 - 5000, false));
        SceneSpace.Origin further = SceneSpace.next(far, 41746.4 + 7000, 41564.3, false);
        assertEquals(49152, further.x());
        assertEquals(2, further.epoch());

        SceneSpace.Origin home = SceneSpace.next(further, 1200, -800, false);
        assertEquals(0, home.x());
        assertEquals(0, home.z());
    }

    @Test
    void movingTheOriginShiftsWholeBlocksExactly() {
        SceneSpace.Origin a = new SceneSpace.Origin(36864, 40960, 1, false);
        SceneSpace.Origin b = new SceneSpace.Origin(40960, 45056, 2, false);
        for (int cx = 2560; cx < 2620; cx++) {
            double world = cx * 16.0;
            assertEquals(b.x(world), a.x(world) + (a.x() - b.x()), "column " + cx);
        }
    }

    @Test
    void theDebugOffsetMovesTheOriginWhereverTheCameraIs() {
        SceneSpace.Origin offset = SceneSpace.next(ZERO, 10, 20, true);
        assertEquals(SceneSpace.DEBUG_OFFSET, offset.x());
        assertSame(offset, SceneSpace.next(offset, 30, 40, true));
        SceneSpace.Origin back = SceneSpace.next(offset, 30, 40, false);
        assertEquals(0, back.x());
        assertTrue(back.epoch() > offset.epoch());
    }

    @Test
    void anAnchoredInstanceFollowsTheOriginAndRefusesARawTransform() {
        RemixInstance instance = new RemixInstance(new RemixMesh(new RemixMeshSurface[0]))
                .setWorldTranslation(41744.0, 64.0, 41552.0);
        assertFalse(instance.behindOrigin(SceneSpace.epoch()));

        SceneSpace.advance(41746.4, 41564.3);
        assertTrue(instance.behindOrigin(SceneSpace.epoch()));
        assertThrows(IllegalStateException.class, () -> instance.setTransform(Transform.identity()));
    }
}
