/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import java.util.List;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;
import static org.junit.jupiter.api.Assertions.*;

class ParticleBuiltinEmissionTest {
    @Test void builtInAtlasEmissionDoesNotRequireAPerSpriteEmissiveTexture() throws Exception {
        var field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        var unsafe = (Unsafe) field.get(null);
        // No native texture or stitched Minecraft sprite is needed for this data contract.
        var emitting = (BlockTextureManager.SpriteEntry) unsafe.allocateInstance(BlockTextureManager.SpriteEntry.class);
        var ordinary = (BlockTextureManager.SpriteEntry) unsafe.allocateInstance(BlockTextureManager.SpriteEntry.class);
        emitting.hasEmission = true;
        assertNull(emitting.emissive);
        assertArrayEquals(new long[]{ParticleMaterialTextures.boundsKey(0, 0)},
                BlockTextureManager.particleEmissionBounds(List.of(ordinary, emitting)));
        assertArrayEquals(new long[0], BlockTextureManager.particleEmissionBounds(List.of(ordinary)));
    }
}
