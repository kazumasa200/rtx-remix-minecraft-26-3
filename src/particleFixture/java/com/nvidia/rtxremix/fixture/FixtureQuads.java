/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.fixture;

import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.ParticleGroupRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;

/** Extracted CPU quads deliberately outside vanilla's live billboard slots. */
public class FixtureQuads implements ParticleGroupRenderState {
    public boolean isEmpty() { return !ParticleFixture.enabled; }
    public Iterable<SingleQuadParticle.Layer> layers() { return List.of(SingleQuadParticle.Layer.TRANSLUCENT); }
    public void buildLayer(SingleQuadParticle.Layer layer, VertexConsumer out) {
        var client = Minecraft.getInstance();
        var atlas = (TextureAtlas) client.getTextureManager().getTexture(layer.textureAtlasLocation());
        var center = ParticleFixture.origin.subtract(client.gameRenderer.mainCamera().position());
        var right = ParticleFixture.right;
        for (int q = 0; q < 3; q++) {
            var sprite = atlas.getSprite(Identifier.withDefaultNamespace(q == 1 ? "smoke_0" : "flame"));
            float du = sprite.getU1() - sprite.getU0(), dv = sprite.getV1() - sprite.getV0();
            float u0 = sprite.getU0() + du * .2f, u1 = sprite.getU0() + du * .8f;
            float v0 = sprite.getV0() + dv * .1f, v1 = sprite.getV0() + dv * .9f;
            double angle = .3 + q * .6, c = Math.cos(angle), s = Math.sin(angle);
            int color = q == 1 ? 0x8090c0ff : q == 0 ? 0xffffc080 : 0xc0ffc0ff;
            for (int i = 0; i < 4; i++) {
                double x = i == 0 || i == 1 ? -.45 : .45;
                double y = i == 0 || i == 3 ? -.45 : .45;
                var p = center.add(right.scale((q - 1) * 1.15 + x * c - y * s))
                        .add(0, x * s + y * c, 0).add(ParticleFixture.normal.scale(x * .25));
                out.addVertex((float) p.x, (float) p.y, (float) p.z).setColor(color)
                        .setUv(i < 2 ? u1 : u0, i == 0 || i == 3 ? v1 : v0).setUv2(240, 240);
            }
        }
    }
    @Override public void submit(SubmitNodeCollector collector, CameraRenderState camera) {}

    /** Fault injection is opt-in and exercises rollback after complete and pending quads. */
    public static final class Broken extends FixtureQuads {
        @Override public void buildLayer(SingleQuadParticle.Layer layer, VertexConsumer out) {
            super.buildLayer(layer, out);
            throw new IllegalStateException("Synthetic producer failure after geometry");
        }
    }
    public static final class AfterFailure extends FixtureQuads {}
}
