/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.particle;

import com.mojang.blaze3d.vertex.VertexConsumer;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.state.level.ParticleGroupRenderState;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;

/** Adapts public, already extracted CPU quad streams, never a mod's storage layout. */
public final class ParticleGeometryAdapters {
    private ParticleGeometryAdapters() {}

    public interface Adapter {
        boolean hasGeometry(ParticleGroupRenderState state);
        void capture(ParticleGroupRenderState state, ParticleGeometryRenderer renderer);
    }

    private static final Adapter EXPLICIT = new Adapter() {
        @Override public boolean hasGeometry(ParticleGroupRenderState state) {
            return ((ExtractedParticleGeometry) state).rtxremix$hasGeometry();
        }
        @Override public void capture(ParticleGroupRenderState state, ParticleGeometryRenderer renderer) {
            ((ExtractedParticleGeometry) state).rtxremix$captureGeometry(renderer);
        }
    };

    private static final ClassValue<Adapter> PUBLIC_QUADS = new ClassValue<>() {
        @Override protected Adapter computeValue(Class<?> type) {
            try {
                var layers = type.getMethod("layers");
                var build = type.getMethod("buildLayer", SingleQuadParticle.Layer.class, VertexConsumer.class);
                var empty = type.getMethod("isEmpty");
                if (!Iterable.class.isAssignableFrom(layers.getReturnType())
                        || build.getReturnType() != void.class || empty.getReturnType() != boolean.class) return null;
                // Public lookup deliberately never opens private classes, fields or methods.
                var lookup = MethodHandles.publicLookup();
                return new PublicQuads(
                        lookup.unreflect(layers).asType(MethodType.methodType(Iterable.class, Object.class)),
                        lookup.unreflect(build).asType(MethodType.methodType(void.class, Object.class,
                                SingleQuadParticle.Layer.class, VertexConsumer.class)),
                        lookup.unreflect(empty).asType(MethodType.methodType(boolean.class, Object.class)));
            } catch (NoSuchMethodException | IllegalAccessException unsupported) {
                return null;
            }
        }
    };

    /** Vanilla retains its live-slot worker; no reflective lookup occurs for that state. */
    public static Adapter forState(ParticleGroupRenderState state) {
        if (state instanceof ExtractedParticleGeometry) return EXPLICIT;
        if (state.getClass() == QuadParticleRenderState.class) return null;
        return PUBLIC_QUADS.get(state.getClass());
    }

    private record PublicQuads(MethodHandle layers, MethodHandle build, MethodHandle empty) implements Adapter {
        @Override public boolean hasGeometry(ParticleGroupRenderState state) {
            try { return !(boolean) empty.invokeExact((Object) state); }
            catch (Throwable failure) { throw propagate(failure); }
        }
        @Override public void capture(ParticleGroupRenderState state, ParticleGeometryRenderer renderer) {
            try {
                for (Object value : (Iterable<?>) layers.invokeExact((Object) state)) {
                    if (!(value instanceof SingleQuadParticle.Layer layer)) {
                        throw new IllegalStateException("Extracted particle layer is not a quad layer");
                    }
                    ParticleGeometryVertexConsumer consumer = renderer.vertexConsumer(layer);
                    try {
                        build.invokeExact((Object) state, layer, (VertexConsumer) consumer);
                        consumer.finish();
                    } finally {
                        consumer.abort();
                    }
                }
            } catch (Throwable failure) { throw propagate(failure); }
        }
    }

    private static RuntimeException propagate(Throwable failure) {
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException runtime) return runtime;
        return new IllegalStateException("Could not consume extracted particle quads", failure);
    }
}
