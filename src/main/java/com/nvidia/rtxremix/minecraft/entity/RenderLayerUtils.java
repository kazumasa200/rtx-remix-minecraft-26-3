/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.blockentity.AbstractEndPortalRenderer;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

/**
 * Reads the layer-level facts the capture path needs from a {@link RenderType}.
 *
 * <p>Every submit names the {@link RenderType} its geometry is drawn with, so the
 * layer is where geometry and material meet and the one place material identity
 * is read. Its {@link RenderSetup}, reached through the access widener, gives the
 * texture bound to {@code Sampler0}, if any; its {@link RenderPipeline} gives the
 * {@link SurfaceMode} Remix shades with, whether the geometry is streamed afresh
 * every frame, the {@link Topology}, and whether an untextured layer carries
 * geometry worth submitting at all.
 */
final class RenderLayerUtils {

    /** Material behavior encoded by Minecraft's actual render phases, and clear glass. */
    enum SurfaceMode {
        OPAQUE,
        CUTOUT,
        TRANSLUCENT,
        EMISSIVE,
        /**
         * Clear glass on the block atlas: blended, through the refracting material
         * terrain gives placed clear glass. No render phase encodes it, since vanilla
         * draws clear glass as a cutout; {@code RemixEntitySubmitter.atlasSurfaceMode}
         * assigns it to geometry from a clear-glass block item.
         */
        GLASS
    }

    /** How a layer's captured vertex stream turns into Remix triangles. */
    enum Topology {
        QUADS,
        TRIANGLE_STRIP
    }

    /**
     * Material identity for layers that draw without a texture. They have no
     * {@link Identifier} of their own, but the submitter keys materials and mesh
     * pools by one, so they share this.
     */
    static final Identifier UNTEXTURED_LAYER_ID =
            Identifier.fromNamespaceAndPath("rtxremix", "untextured_entity_layer");

    private RenderLayerUtils() {}

    /**
     * Cache of RenderType -> capturable texture. Most RenderType instances are reused,
     * but weak keys avoid retaining dynamic layers created by mods or skin features.
     */
    private static final Map<RenderType, Optional<Identifier>> capturableTextureCache =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** Cache of RenderType -> streamed-per-frame verdict, weak-keyed like {@link #capturableTextureCache}. */
    private static final Map<RenderType, Boolean> streamedPerFrameCache =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Returns the texture a captured layer is submitted under, or empty when Remix has
     * nothing to draw for the layer. Results are cached by layer identity.
     *
     * <p>Empty covers two cases. Untextured layers (shadow, debug, lines) carry no
     * material identity at all. Layers that pass the depth test unconditionally, the
     * see-through text of a name tag, are drawn over the whole scene in vanilla; no
     * placement in a ray-traced scene reproduces that, and vanilla draws the
     * depth-tested copy of the same text beside them, so their quads would only double
     * it.
     *
     * @param layer the render layer (typically from an entity renderer)
     * @return the texture identifier to capture under, or empty to skip the layer
     */
    static Optional<Identifier> capturableTexture(RenderType layer) {
        return capturableTextureCache.computeIfAbsent(layer, RenderLayerUtils::capturableTextureUncached);
    }

    private static Optional<Identifier> capturableTextureUncached(RenderType layer) {
        if (layer.pipeline().getDepthStencilState() == null) {
            return Optional.empty();
        }
        return extractTexture(layer);
    }

    private static Optional<Identifier> extractTexture(RenderType layer) {
        RenderSetup.TextureBinding texture = layer.state.textures.get("Sampler0");
        return texture == null ? Optional.empty() : Optional.of(texture.location());
    }

    /**
     * How to read the layer's vertices, or empty when it draws primitives that
     * do not become triangles, such as lines.
     */
    static Optional<Topology> topology(RenderType layer) {
        PrimitiveTopology topology = layer.primitiveTopology();
        return switch (topology) {
            case QUADS -> Optional.of(Topology.QUADS);
            case TRIANGLE_STRIP -> Optional.of(Topology.TRIANGLE_STRIP);
            default -> Optional.empty();
        };
    }

    /**
     * Whether a layer that draws without a texture still carries geometry worth
     * submitting to Remix. In 26.3 those are the lead between a mob and its holder
     * and the lightning bolt.
     *
     * <p>Two conditions, both about what the layer can actually shade with.
     * Vertex colour is the only surface detail such a layer has, so a layer
     * without it carries nothing to submit; that excludes the water mask, which
     * exists to write depth. And of the untextured layers that do have colour,
     * the triangle strips are world geometry while the quads are screen
     * furniture such as the nameplate background, which should not become
     * scene geometry that lights and casts shadows. The lightning bolt's quads
     * are the exception: dropped with the rest, no bolt was ever drawn.
     */
    static boolean isCapturableUntexturedLayer(RenderType layer) {
        // Only layers with no texture at all qualify: a textured layer that
        // capturableTexture declined must not re-enter capture through here.
        return extractTexture(layer).isEmpty()
                && (topology(layer).filter(t -> t == Topology.TRIANGLE_STRIP).isPresent()
                        || layer.pipeline() == RenderPipelines.LIGHTNING)
                && layer.format().contains(DefaultVertexFormat.COLOR_SEMANTIC_NAME);
    }

    /**
     * Whether the layer's vertex content differs every frame, so no mesh built from
     * it can be shared across frames and none should be pooled.
     *
     * <p>Vanilla streams two kinds of geometry through an entity provider whose
     * vertices never repeat: triangle strips, which it computes from live positions
     * (the lead between a mob and its holder), and the beacon beam, whose texture
     * coordinate scrolls with world time. Everything else it draws is rigid: a
     * model part or baked quad whose pose changes while its local vertices do not.
     */
    static boolean isStreamedPerFrame(RenderType layer) {
        return streamedPerFrameCache.computeIfAbsent(layer, RenderLayerUtils::isStreamedPerFrameUncached);
    }

    private static boolean isStreamedPerFrameUncached(RenderType layer) {
        RenderPipeline pipeline = layer.pipeline();
        boolean beaconBeamProgram = pipeline == RenderPipelines.BEACON_BEAM_OPAQUE
                || pipeline == RenderPipelines.BEACON_BEAM_TRANSLUCENT;
        // The End portal's stars drift with the day too (EndPortalFaces).
        boolean endPortalStars = extractTexture(layer)
                .filter(AbstractEndPortalRenderer.END_PORTAL_LOCATION::equals).isPresent();
        return classifyStreamedPerFrame(topology(layer).orElse(Topology.QUADS),
                beaconBeamProgram || endPortalStars);
    }

    static boolean classifyStreamedPerFrame(Topology topology, boolean beaconBeamProgram) {
        return topology == Topology.TRIANGLE_STRIP || beaconBeamProgram;
    }

    /**
     * Converts Minecraft's render-phase state into the material behavior that
     * Remix needs. This deliberately inspects phases rather than render-layer
     * names so mod-created layers using the standard phases behave correctly.
     *
     * <p>The block-breaking crack multiplies the surface under it, a blend Remix
     * has no material for. Its texels are either fully opaque grey or fully
     * transparent, so alpha blending them over the surface is the closest surface
     * behaviour and it is classified as translucent.
     */
    static SurfaceMode surfaceMode(RenderType layer) {
        RenderPipeline pipeline = layer.pipeline();
        return classifySurfaceMode(
                pipeline == RenderPipelines.EYES
                        || pipeline == RenderPipelines.ENTITY_TRANSLUCENT_EMISSIVE
                        || pipeline == RenderPipelines.LIGHTNING,
                usesBlend(pipeline, BlendFunction.ADDITIVE),
                usesBlend(pipeline, BlendFunction.TRANSLUCENT)
                        || pipeline == RenderPipelines.CRUMBLING,
                pipeline == RenderPipelines.ENTITY_SOLID
                        || pipeline == RenderPipelines.ENTITY_SOLID_Z_OFFSET_FORWARD,
                TEXT_PIPELINES.contains(pipeline));
    }

    /** The world text programs, colour and greyscale, with and without polygon offset. */
    private static final Set<RenderPipeline> TEXT_PIPELINES = Set.of(
            RenderPipelines.TEXT,
            RenderPipelines.TEXT_GRAYSCALE,
            RenderPipelines.TEXT_POLYGON_OFFSET,
            RenderPipelines.TEXT_GRAYSCALE_POLYGON_OFFSET);

    private static boolean usesBlend(RenderPipeline pipeline, BlendFunction blend) {
        for (ColorTargetState target : pipeline.getColorTargetStates()) {
            if (target != null && target.blendFunction().filter(blend::equals).isPresent()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The text programs blend in vanilla only so glyph edges antialias; what they draw,
     * glyphs, map pixels and map icons, is coverage whose texels are either there or
     * not. Cutout keeps those quads out of the blended path, so text and maps are opaque
     * hits that cast shadows and appear in reflections instead of ghosted decals.
     */
    static SurfaceMode classifySurfaceMode(boolean emissiveProgram, boolean additiveTransparency,
                                           boolean translucentTransparency, boolean solidProgram,
                                           boolean textProgram) {
        if (emissiveProgram || additiveTransparency) {
            return SurfaceMode.EMISSIVE;
        }
        if (textProgram) {
            return SurfaceMode.CUTOUT;
        }
        if (translucentTransparency) {
            return SurfaceMode.TRANSLUCENT;
        }
        if (solidProgram) {
            return SurfaceMode.OPAQUE;
        }
        return SurfaceMode.CUTOUT;
    }
}
