/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.compat.immediatelyfast;

import net.fabricmc.loader.api.Version;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ImmediatelyFastMixinPluginTest {
    @Test
    void supportsTheQualifiedReleaseIncludingBuildMetadata() throws Exception {
        assertTrue(ImmediatelyFastMixinPlugin.supports(Version.parse("1.17.1")));
        assertTrue(ImmediatelyFastMixinPlugin.supports(Version.parse("1.17.1+26.3")));
    }

    @Test
    void untestedAndPrereleaseVersionsDoNotApplyInternalApiHooks() throws Exception {
        for (String version : new String[] {"1.17.1-alpha", "1.17.1-alpha+26.3", "1.17.0",
                "1.17.2", "99.0.0", "development", "1.17.1.1"}) {
            assertFalse(ImmediatelyFastMixinPlugin.supports(Version.parse(version)), version);
        }
    }

    @Test
    void absenceDoesNotApplyInternalApiHooks() {
        var plugin = new ImmediatelyFastMixinPlugin();
        plugin.onLoad("com.nvidia.rtxremix.minecraft.compat.immediatelyfast.mixin");
        assertFalse(plugin.shouldApplyMixin("net.minecraft.client.renderer.MapRenderer", "MapRendererMixin"));
    }
}
