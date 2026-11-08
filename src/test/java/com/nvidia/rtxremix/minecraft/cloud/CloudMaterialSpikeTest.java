/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.cloud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CloudMaterialSpikeTest {

    @Test
    void shellIsClosedAndWoundClockwiseFromOutside() {
        CloudMaterialSpike.Shell shell = CloudMaterialSpike.Shell.cumulus(12.0f);
        float[] p = shell.positions();
        int[] idx = shell.indices();
        // The absorption patch counts entries and exits by facing: every face must face out. With every
        // directed edge matched by its opposite the winding is consistent, and a negative signed volume
        // (taken with counter-clockwise cross products) means the faces run clockwise seen from outside.
        double volume = 0.0;
        Map<Long, Integer> edges = new HashMap<>();
        for (int f = 0; f < idx.length; f += 3) {
            int a = idx[f] * 3;
            int b = idx[f + 1] * 3;
            int c = idx[f + 2] * 3;
            double ux = p[b] - p[a], uy = p[b + 1] - p[a + 1], uz = p[b + 2] - p[a + 2];
            double vx = p[c] - p[a], vy = p[c + 1] - p[a + 1], vz = p[c + 2] - p[a + 2];
            double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
            volume += (p[a] * nx + p[a + 1] * ny + p[a + 2] * nz) / 6.0;
            int[] v = {idx[f], idx[f + 1], idx[f + 2]};
            for (int e = 0; e < 3; e++) {
                edges.merge(((long) v[e] << 32) | v[(e + 1) % 3], 1, Integer::sum);
            }
        }
        assertTrue(volume < 0.0, "signed volume " + volume);
        // Closed: every directed edge has exactly one opposite.
        for (long edge : edges.keySet()) {
            long opposite = ((edge & 0xffff_ffffL) << 32) | (edge >>> 32);
            assertEquals(1, edges.getOrDefault(opposite, 0));
        }
    }
}
