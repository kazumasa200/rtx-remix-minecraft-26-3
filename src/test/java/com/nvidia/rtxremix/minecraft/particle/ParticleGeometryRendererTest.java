/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.particle;

import com.nvidia.rtxremix.InstanceInfoBlendEXT;
import net.minecraft.client.particle.SingleQuadParticle;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ParticleGeometryRendererTest {
    @Test void equalLayerDescriptorsShareABatchWithoutKeepingOnePerFrame() {
        var original = SingleQuadParticle.Layer.TRANSLUCENT;
        try (var renderer = new ParticleGeometryRenderer(null)) {
            var batch = renderer.layer(original);
            for (int i = 0; i < 1000; i++) {
                var equivalent = new SingleQuadParticle.Layer(original.translucent(), original.textureAtlasLocation(),
                        original.pipeline(), original.oitPipelineSet());
                assertSame(batch, renderer.layer(equivalent));
            }
            assertNotSame(batch, renderer.layer(SingleQuadParticle.Layer.OPAQUE));
        }
    }

    @Test void bothLayerKindsPreserveParticleShaderCutoffAndVertexAlpha() throws Exception {
        // Minecraft's particle.fsh discards alpha below 0.1 in both layer kinds.
        try (var renderer = new ParticleGeometryRenderer(null)) {
            for (String name : new String[]{"cutout", "blended"}) {
                var field = ParticleGeometryRenderer.class.getDeclaredField(name);
                field.setAccessible(true);
                var blend = (InstanceInfoBlendEXT) field.get(renderer);
                assertEquals(1, blend.alphaTestEnabled);
                assertEquals(25, Byte.toUnsignedInt(blend.alphaTestReferenceValue));
                assertEquals(InstanceInfoBlendEXT.VK_COMPARE_OP_GREATER, blend.alphaTestCompareOp);
                assertEquals(InstanceInfoBlendEXT.RT_TEX_OP_MODULATE, blend.textureAlphaOperation);
                assertEquals(0, blend.isVertexColorBakedLighting);
                assertEquals(name.equals("blended") ? 1 : 0, blend.alphaBlendEnabled);
            }
        }
    }
}
