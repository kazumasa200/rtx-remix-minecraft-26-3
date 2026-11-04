/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import com.nvidia.rtxremix.HardcodedVertex;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ParticleSpriteBoundsTest {
    private static long packed(float u0, float v0, float u1, float v1) {
        return ParticleMaterialTextures.boundsKey(HardcodedVertex.packHalf2x16(u0, v0), HardcodedVertex.packHalf2x16(u1, v1));
    }
    @Test void croppedQuadsUseTheFullEnclosingSpriteAtGridEdges() {
        var bounds = new ParticleSpriteBounds(List.of(new ParticleSpriteBounds.Bounds(0, 0, .5f, .5f),
                new ParticleSpriteBounds.Bounds(.5f, .5f, 1, 1)));
        assertEquals(packed(0, 0, .5f, .5f), bounds.find(.1f, .1f, .2f, .2f));
        assertEquals(packed(.5f, .5f, 1, 1), bounds.find(.98f, .99f, 1, 1));
    }
    @Test void unsupportedCrossSpriteQuadsRetainTheirOwnUvExtent() {
        var bounds = new ParticleSpriteBounds(List.of(new ParticleSpriteBounds.Bounds(0, 0, .5f, .5f)));
        assertEquals(packed(.4f, .4f, .8f, .8f), bounds.find(.4f, .4f, .8f, .8f));
    }
}
