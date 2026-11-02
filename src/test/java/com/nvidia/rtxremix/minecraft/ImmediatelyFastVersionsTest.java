/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ImmediatelyFastVersionsTest {
    @Test
    void metadataAllowsOptionalVersionsWhileThePluginOwnsAdapterSelection() throws Exception {
        var metadata = JsonParser.parseString(Files.readString(Path.of("src/main/resources/fabric.mod.json"))).getAsJsonObject();
        assertFalse(metadata.getAsJsonObject("depends").has("immediatelyfast"));
        assertFalse(metadata.has("breaks") && metadata.getAsJsonObject("breaks").has("immediatelyfast"));
        var optional = JsonParser.parseString(Files.readString(Path.of(
                "src/main/resources/rtx-remix-renderer.immediatelyfast.mixins.json"))).getAsJsonObject();
        assertEquals("com.nvidia.rtxremix.minecraft.compat.immediatelyfast.ImmediatelyFastMixinPlugin",
                optional.get("plugin").getAsString());
        assertTrue(optional.get("required").getAsBoolean());
    }

    @Test
    void genericGlyphCaptureRemainsOutsideTheImmediatelyFastVersionGate() throws Exception {
        var shared = JsonParser.parseString(Files.readString(Path.of(
                "src/main/resources/rtx-remix-renderer.mixins.json"))).getAsJsonObject();
        assertTrue(shared.getAsJsonArray("client").asList().stream()
                .anyMatch(mixin -> mixin.getAsString().equals("GlyphAtlasTextureMixin")));
        assertTrue(shared.getAsJsonArray("client").asList().stream()
                .anyMatch(mixin -> mixin.getAsString().equals("CommandEncoderGlyphMirrorMixin")));
    }
}
