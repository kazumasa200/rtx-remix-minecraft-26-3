/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.lod;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LodSelectionTest {

    @Test
    void keysRoundTripNegativePositions() {
        for (int detail : new int[] {0, 3, LodSelection.MAX_DETAIL}) {
            for (int ix : new int[] {-70000, -1, 0, 5, 70000}) {
                for (int iz : new int[] {-3, 0, 123456}) {
                    long key = LodSelection.key(detail, ix, iz);
                    assertEquals(detail, LodSelection.detail(key));
                    assertEquals(ix, LodSelection.ix(key));
                    assertEquals(iz, LodSelection.iz(key));
                }
            }
        }
    }

    @Test
    void sectionsTileTheDiskWithoutOverlapAndCoarsenWithDistance() {
        double cx = 1234.5;
        double cz = -987.25;
        int radius = 4096;
        List<Long> sections = new ArrayList<>();
        LodSelection.select(cx, cz, radius, 0, sections::add);
        assertFalse(sections.isEmpty());
        for (int a = 0; a < sections.size(); a++) {
            for (int b = a + 1; b < sections.size(); b++) {
                assertFalse(LodSelection.overlaps(sections.get(a), sections.get(b)));
            }
        }
        // Every point of the disk lies in exactly one section.
        for (int i = 0; i < 2000; i++) {
            double angle = i * 2.399963;
            double r = radius * Math.sqrt((i + 0.5) / 2000.0);
            int x = (int) Math.floor(cx + r * Math.cos(angle));
            int z = (int) Math.floor(cz + r * Math.sin(angle));
            int covering = 0;
            for (long key : sections) {
                int w = LodSelection.width(LodSelection.detail(key));
                int mx = LodSelection.minX(key);
                int mz = LodSelection.minZ(key);
                if (x >= mx && x < mx + w && z >= mz && z < mz + w) covering++;
            }
            assertEquals(1, covering, "point " + x + ", " + z);
        }
        for (long key : sections) {
            int detail = LodSelection.detail(key);
            int w = LodSelection.width(detail);
            double distance = LodSelection.distance(LodSelection.minX(key), LodSelection.minZ(key), w, cx, cz);
            if (detail > 0) assertTrue(distance >= LodSelection.SPLIT * w, "too coarse at " + distance);
            if (detail < LodSelection.rootDetail(radius)) {
                // Its parent split, so the camera was nearer the parent than SPLIT of its widths.
                int parentWidth = 2 * w;
                double parentDistance = LodSelection.distance(
                        Math.floorDiv(LodSelection.ix(key), 2) * parentWidth,
                        Math.floorDiv(LodSelection.iz(key), 2) * parentWidth, parentWidth, cx, cz);
                assertTrue(parentDistance < LodSelection.SPLIT * parentWidth,
                        "too fine at " + distance + " for detail " + detail);
            }
        }
    }

    @Test
    void sectionsWhollyInsideMinecraftsTerrainAreLeftOut() {
        List<Long> sections = new ArrayList<>();
        LodSelection.select(0.5, 0.5, 2048, 256, sections::add);
        for (long key : sections) {
            int w = LodSelection.width(LodSelection.detail(key));
            int mx = LodSelection.minX(key);
            int mz = LodSelection.minZ(key);
            boolean inside = mx >= -255.5 && mx + w <= 256.5 && mz >= -255.5 && mz + w <= 256.5;
            assertFalse(inside, "section at " + mx + ", " + mz);
        }
        assertTrue(sections.stream().anyMatch(k -> LodSelection.minX(k) <= 300 && LodSelection.minX(k) >= 200));
    }
}
