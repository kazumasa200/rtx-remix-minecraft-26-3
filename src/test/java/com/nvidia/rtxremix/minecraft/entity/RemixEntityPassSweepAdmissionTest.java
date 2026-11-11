/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.nvidia.rtxremix.minecraft.util.ClosestKHeap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemixEntityPassSweepAdmissionTest {

    private static final double MAX_DIST_SQ = 112.0 * 112.0;

    /** Constructed at a frame budget larger than the round, then narrowed the way the sweep does. */
    private static ClosestKHeap<String> sweepHeap(int budget) {
        ClosestKHeap<String> heap = new ClosestKHeap<>(256);
        heap.reset(budget);
        return heap;
    }

    private static Set<String> keptItems(ClosestKHeap<String> heap) {
        Set<String> kept = new HashSet<>();
        for (int i = 0; i < heap.size(); i++) {
            kept.add(heap.get(i));
        }
        return kept;
    }

    @ParameterizedTest
    @CsvSource({
            "2500.0, false, true",
            "12544.0, false, true",
            "12769.0, false, false",
            "1000000.0, true, true",
            "0.0, true, true"
    })
    void sweepAdmissionHonorsDistanceUnlessEntityOwnsLight(
            double distSq, boolean ownsLight, boolean expected) {
        assertEquals(expected, RemixEntityPass.sweepAdmits(distSq, MAX_DIST_SQ, ownsLight));
        if (!ownsLight) {
            assertEquals(distSq, RemixEntityPass.sweepPriority(distSq, false));
        }
    }

    @ParameterizedTest
    @CsvSource({
            "true, false, false, true",
            "true, true, false, false",
            "true, false, true, false",
            "false, false, false, false"
    })
    void theAttachedCameraBodyStaysOutOfTheSweep(
            boolean cameraEntity, boolean detached, boolean localPlayer, boolean skip) {
        assertEquals(skip, RemixEntityPass.sweepSkipsAttachedCameraBody(cameraEntity, detached, localPlayer));
    }

    @Test
    void lightOwnerRanksBelowEveryNonOwner() {
        // A non-owner standing at the camera is the best any non-owner can rank.
        double bestNonOwner = RemixEntityPass.sweepPriority(0.0, false);
        assertTrue(RemixEntityPass.sweepPriority(1000.0 * 1000.0, true) < bestNonOwner);
        assertTrue(RemixEntityPass.sweepPriority(0.0, true) < bestNonOwner);
    }

    @Test
    void lightOwnerSurvivesASaturatedClosestFirstHeap() {
        // The guarantee the priority exists for: a lighthouse 130 blocks out must not be
        // evicted by a crowd of nearer entities once the budget is full.
        ClosestKHeap<String> heap = sweepHeap(2);
        heap.offer("near-cow", RemixEntityPass.sweepPriority(4.0, false));
        heap.offer("nearer-cow", RemixEntityPass.sweepPriority(1.0, false));
        heap.offer("lighthouse", RemixEntityPass.sweepPriority(130.0 * 130.0, true));

        assertTrue(keptItems(heap).contains("lighthouse"),
                "a light owner must never lose the closest-first selection");
        assertEquals(1, heap.overflowCount());
    }

    @Test
    void nearerLightOwnerWinsWhenTwoOwnersCompeteForOneSlot() {
        // The farther owner iterates first, which is the order that would win on a tie.
        ClosestKHeap<String> heap = sweepHeap(1);
        heap.offer("far-lantern", RemixEntityPass.sweepPriority(130.0 * 130.0, true));
        heap.offer("near-lantern", RemixEntityPass.sweepPriority(30.0 * 30.0, true));

        assertEquals(Set.of("near-lantern"), keptItems(heap));
        assertEquals(1, heap.overflowCount());
    }
}
