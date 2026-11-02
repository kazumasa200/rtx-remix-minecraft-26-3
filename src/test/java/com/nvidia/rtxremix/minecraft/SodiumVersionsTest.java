/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SodiumVersionsTest {
    @Test
    void metadataRejectsUntestedInternalApisWithoutRequiringOptionalMods() throws Exception {
        var metadata = JsonParser.parseString(Files.readString(Path.of("src/main/resources/fabric.mod.json"))).getAsJsonObject();
        for (String mod : new String[] {"sodium"}) {
            assertFalse(metadata.getAsJsonObject("depends").has(mod));
            String supported = "0.9.2";
            for (String version : new String[] {supported, supported + "+26.3", supported + "-alpha", "99.0.0", "0.0.1"}) {
                boolean blocked = false;
                for (var predicate : metadata.getAsJsonObject("breaks").getAsJsonArray(mod)) {
                    blocked |= VersionPredicate.parse(predicate.getAsString()).test(Version.parse(version));
                }
                assertEquals(!version.equals(supported) && !version.equals(supported + "+26.3"), blocked, mod + " " + version);
            }
        }
    }
}
