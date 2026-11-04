/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import java.util.Arrays;
import com.nvidia.rtxremix.minecraft.asset.RemixTexture;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ParticleMaterialTexturesTest {
    @Test void explicitSpriteEmissionWinsWithoutSuppressingOtherSprites() {
        long[] authored = {ParticleMaterialTextures.boundsKey(0x80000000, 13),
                ParticleMaterialTextures.boundsKey(12, 19)};
        Arrays.sort(authored);
        var textures = new ParticleMaterialTextures(null, null, null, null, null, null, 40, authored);
        assertEquals(16, textures.materialBucket(15, 12, 19));
        assertEquals(16, textures.materialBucket(0, 12, 19));
        assertEquals(16, textures.materialBucket(5, 0x80000000, 13));
        assertEquals(15, textures.materialBucket(15, 12, 20));
        assertEquals(0, textures.materialBucket(0, 12, 20));
    }

    @Test void nonEmittingSpritesDoNotBindTheWholeAtlasesEmissionIntensity() {
        var texture = new RemixTexture(1, 1, 0, 0, null);
        var textures = new ParticleMaterialTextures(texture, null, null, null, null, texture, 40, new long[0]);
        assertEquals(0, textures.material(0).emissiveIntensity);
        assertEquals(40, textures.material(16).emissiveIntensity);
        assertEquals(1, textures.material(15).emissiveIntensity);
    }
}
