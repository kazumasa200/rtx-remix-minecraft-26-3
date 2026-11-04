/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.particle;

import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.Set;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.ParticleGroupRenderState;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class ParticleGeometryAdaptersTest {
    public static class PublicQuads implements ParticleGroupRenderState {
        public Set<SingleQuadParticle.Layer> layers() { return Set.of(); }
        public void buildLayer(SingleQuadParticle.Layer layer, VertexConsumer consumer) {}
        public boolean isEmpty() { return true; }
        @Override public void submit(SubmitNodeCollector collector, CameraRenderState camera) {}
    }
    public static final class FailingQuads extends PublicQuads {
        static final IllegalStateException FAILURE = new IllegalStateException("source failure");
        @Override public boolean isEmpty() { throw FAILURE; }
    }
    private static final class NonpublicQuads extends PublicQuads {}
    public static final class Explicit extends PublicQuads implements ExtractedParticleGeometry {
        boolean captured;
        @Override public boolean isEmpty() { throw new AssertionError("Explicit source must take priority"); }
        @Override public boolean rtxremix$hasGeometry() { return true; }
        @Override public void rtxremix$captureGeometry(ParticleGeometryRenderer renderer) { captured = true; }
    }

    @Test void vanillaSlotsNeverUseGenericGeometry() {
        assertNull(ParticleGeometryAdapters.forState(new QuadParticleRenderState()));
    }
    @Test void publicShapeIsResolvedOnceAndEmptySourcesStayEmpty() {
        var state = new PublicQuads();
        var adapter = ParticleGeometryAdapters.forState(state);
        assertNotNull(adapter);
        assertSame(adapter, ParticleGeometryAdapters.forState(new PublicQuads()));
        assertFalse(adapter.hasGeometry(state));
        adapter.capture(state, null); // No layer, atlas, renderer or native allocation.
    }
    @Test void explicitSourceRetainsItsOwnAttributesAndBypassesPublicFallback() {
        var state = new Explicit();
        var adapter = ParticleGeometryAdapters.forState(state);
        assertTrue(adapter.hasGeometry(state));
        adapter.capture(state, null);
        assertTrue(state.captured);
    }
    @Test void unknownStatesDoNotGetRenderedAgain() {
        ParticleGroupRenderState state = (collector, camera) -> { throw new AssertionError("Do not submit twice"); };
        assertNull(ParticleGeometryAdapters.forState(state));
        assertNull(ParticleGeometryAdapters.forState(state));
    }
    @Test void originalSourceExceptionsAreNotSwallowed() {
        var state = new FailingQuads();
        var adapter = ParticleGeometryAdapters.forState(state);
        assertSame(FailingQuads.FAILURE, assertThrows(IllegalStateException.class, () -> adapter.hasGeometry(state)));
    }
}
