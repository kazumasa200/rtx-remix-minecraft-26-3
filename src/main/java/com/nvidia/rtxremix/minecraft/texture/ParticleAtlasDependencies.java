/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.resources.Identifier;

/** Atlas dependencies are declared by quad layers, not discovered by scanning unrelated textures. */
public final class ParticleAtlasDependencies {
    private static volatile Set<Identifier> ids = Set.of();
    private static volatile int revision;
    private ParticleAtlasDependencies() {}
    /** Call during resource registration for a layer that is constructed only while rendering. */
    public static void register(Identifier atlas) {
        if (atlas == null || ids.contains(atlas)) return;
        synchronized (ParticleAtlasDependencies.class) {
            if (ids.contains(atlas)) return;
            var next = new LinkedHashSet<>(ids);
            next.add(atlas);
            ids = Collections.unmodifiableSet(next);
            revision++;
        }
    }
    public static void uploaded(Identifier atlas) {
        if (ids.contains(atlas)) synchronized (ParticleAtlasDependencies.class) { revision++; }
    }
    public static int revision() { return revision; }
    public static Set<Identifier> atlases() { return ids; }
}
