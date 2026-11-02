/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ParticleAtlasDependenciesTest {
    @Test void declaredDependenciesInvalidatePreparationWithoutScanningUnrelatedUploads() {
        var id = Identifier.fromNamespaceAndPath("particle_fixture", "declared");
        int before = ParticleAtlasDependencies.revision();
        var previous = ParticleAtlasDependencies.atlases();
        ParticleAtlasDependencies.register(id);
        assertEquals(before + 1, ParticleAtlasDependencies.revision());
        assertFalse(previous.contains(id));
        assertTrue(ParticleAtlasDependencies.atlases().contains(id));
        ParticleAtlasDependencies.register(id);
        ParticleAtlasDependencies.register(null);
        assertEquals(before + 1, ParticleAtlasDependencies.revision());
        ParticleAtlasDependencies.uploaded(Identifier.fromNamespaceAndPath("particle_fixture", "unrelated"));
        assertEquals(before + 1, ParticleAtlasDependencies.revision());
        ParticleAtlasDependencies.uploaded(id);
        assertEquals(before + 2, ParticleAtlasDependencies.revision());
    }
}
