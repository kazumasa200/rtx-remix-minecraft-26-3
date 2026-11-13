/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.lod.dh;

import net.fabricmc.loader.api.Version;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DistantHorizonsMixinPluginTest {
    @Test
    void supportsTheReleaseTheMixinsWereWrittenAgainst() throws Exception {
        assertTrue(DistantHorizonsMixinPlugin.supports(Version.parse("3.3.4")));
        assertTrue(DistantHorizonsMixinPlugin.supports(Version.parse("3.3.4+26.3")));
    }

    @Test
    void otherReleasesDoNotTakeTheInternalMixins() throws Exception {
        for (String version : new String[] {"3.3.3", "3.3.5", "3.4.0", "3.3.4-dev", "4.0.0", "development"}) {
            assertFalse(DistantHorizonsMixinPlugin.supports(Version.parse(version)), version);
        }
    }

    @Test
    void absenceDoesNotApplyTheMixins() {
        assertFalse(DistantHorizonsMixinPlugin.SUPPORTED);
    }
}
