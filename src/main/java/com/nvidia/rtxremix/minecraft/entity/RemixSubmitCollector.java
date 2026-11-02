/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MeshView;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadView;
import net.fabricmc.fabric.api.client.rendering.v1.SubmitRenderPhase;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.UvMapping;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.ItemQuads;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

/**
 * A {@link SubmitNodeCollector} that draws every submit straight into a
 * {@link RemixVertexConsumerProvider} instead of queueing it for the frame graph.
 *
 * <p>In 26.3 renderers no longer draw: an entity or block-entity renderer
 * submits models, items, text and custom geometry to a collector, and vanilla's
 * feature renderers draw the queued submits later, long after the entity that made
 * them is out of reach. The capture needs each owner's geometry while the owner is
 * still known, so the capture passes hand renderers this collector, which does what
 * the matching feature renderer would have done at once: a model is posed and drawn
 * through the capturing consumers, so {@code CuboidMixin} still sees every cuboid
 * with its pivot; an item's baked quads land as baked quads; text is laid out by the
 * font into glyph quads.
 *
 * <p>What a path tracer has no use for is dropped: blob shadows, outline passes,
 * crumbling overlays (the block damage pass submits its own), gizmos, and particles,
 * which the particle renderer captures separately. Shape outlines are the block
 * outline pass's concern too.
 *
 * <p>Render thread only. The scratch objects are shared across submits.
 */
public final class RemixSubmitCollector implements SubmitNodeCollector {

    private static final Direction[] DIRECTIONS = Direction.values();

    private final RemixVertexConsumerProvider provider;
    private final QuadInstance quadInstance = new QuadInstance();
    private final Matrix4f scratchPose = new Matrix4f();
    private final PoseStack scratchStack = new PoseStack();
    private final TextGlyphs glyphs = new TextGlyphs();
    private ModelBlockRenderer movingBlockRenderer;

    public RemixSubmitCollector(RemixVertexConsumerProvider provider) {
        this.provider = provider;
    }

    /** The provider every submit is drawn into. */
    public RemixVertexConsumerProvider provider() {
        return provider;
    }

    /** Ordering only matters to the frame graph; every order draws here at once. */
    @Override
    public OrderedSubmitNodeCollector order(int order) {
        return this;
    }

    // ---- Models ----

    @Override
    public <S> void submitModel(Model<? super S> model, S state, PoseStack poseStack, RenderType renderType,
                                int lightCoords, int overlayCoords, int tintedColor,
                                @Nullable UvMapping uvMapping, int outlineColor) {
        if (renderType.isOutline()) {
            return;
        }
        VertexConsumer buffer = provider.getBuffer(renderType);
        if (uvMapping != null) {
            buffer = uvMapping.wrap(buffer);
        }
        model.setupAnim(state);
        model.renderToBuffer(poseStack, buffer, lightCoords, overlayCoords, tintedColor);
    }

    @Override
    public <S> void submitCrumblingOverlay(Model<? super S> model, S state, PoseStack poseStack,
                                           RenderType renderType, int lightCoords, int overlayCoords,
                                           int tintedColor, ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) {
    }

    // ---- Items and block models ----

    @Override
    public void submitItem(PoseStack poseStack, ItemDisplayContext displayContext, int lightCoords,
                           int overlayCoords, int outlineColor, int[] tintLayers, ItemQuads quads,
                           ItemStackRenderState.FoilType foilType) {
        // The glint is a screen-space shimmer Remix has no material for, so the
        // item is captured under its plain render type whatever its foil.
        PoseStack.Pose pose = poseStack.last();
        quadInstance.setLightCoords(lightCoords);
        quadInstance.setOverlayCoords(overlayCoords);
        for (BakedQuad quad : quads.all()) {
            BakedQuad.MaterialInfo material = quad.materialInfo();
            quadInstance.setColor(material.isTinted() ? tintLayer(tintLayers, material.tintIndex()) : -1);
            provider.getBuffer(material.itemRenderType()).putBakedQuad(pose, quad, quadInstance);
        }
    }

    @Override
    public void submitBlockModel(PoseStack poseStack, RenderType renderType, List<BlockStateModelPart> parts,
                                 int[] tintLayers, int lightCoords, int overlayCoords, int outlineColor) {
        if (renderType.isOutline()) {
            return;
        }
        VertexConsumer buffer = provider.getBuffer(renderType);
        PoseStack.Pose pose = poseStack.last();
        quadInstance.setLightCoords(lightCoords);
        quadInstance.setOverlayCoords(overlayCoords);
        for (BlockStateModelPart part : parts) {
            for (Direction direction : DIRECTIONS) {
                putPartQuads(part.getQuads(direction), pose, tintLayers, buffer);
            }
            putPartQuads(part.getQuads(null), pose, tintLayers, buffer);
        }
    }

    private void putPartQuads(List<BakedQuad> quads, PoseStack.Pose pose, int[] tintLayers, VertexConsumer buffer) {
        for (BakedQuad quad : quads) {
            int tintIndex = quad.materialInfo().tintIndex();
            quadInstance.setColor(tintIndex != -1 && tintIndex < tintLayers.length ? tintLayers[tintIndex] : -1);
            buffer.putBakedQuad(pose, quad, quadInstance);
        }
    }

    private static int tintLayer(int[] layers, int layer) {
        return layer >= 0 && layer < layers.length ? layers[layer] : -1;
    }

    // ---- Fabric renderer API meshes ----
    //
    // Fabric adds these overloads to the collector and defaults them to the vanilla submit,
    // which drops the mesh. Each mesh quad becomes the baked quad Fabric itself would make of
    // it, so it reaches the capture the way a vanilla quad does.

    @Override
    public void submitItem(PoseStack poseStack, ItemDisplayContext displayContext, int lightCoords,
                           int overlayCoords, int outlineColor, int[] tintLayers, ItemQuads quads,
                           MeshView mesh, ItemStackRenderState.FoilType foilType) {
        submitItem(poseStack, displayContext, lightCoords, overlayCoords, outlineColor, tintLayers, quads, foilType);
        PoseStack.Pose pose = poseStack.last();
        mesh.forEach(quad -> putMeshQuad(quad, pose, tintLayers, provider.getBuffer(quad.itemRenderType())));
    }

    @Override
    public void submitBlockModel(PoseStack poseStack, Function<ChunkSectionLayer, RenderType> renderTypeFunction,
                                 boolean translucent, List<BlockStateModelPart> parts, @Nullable Mesh mesh,
                                 int[] tintLayers, int lightCoords, int overlayCoords, int outlineColor) {
        PoseStack.Pose pose = poseStack.last();
        quadInstance.setLightCoords(lightCoords);
        quadInstance.setOverlayCoords(overlayCoords);
        for (BlockStateModelPart part : parts) {
            for (Direction direction : DIRECTIONS) {
                putLayeredPartQuads(part.getQuads(direction), pose, tintLayers, renderTypeFunction);
            }
            putLayeredPartQuads(part.getQuads(null), pose, tintLayers, renderTypeFunction);
        }
        if (mesh != null) {
            mesh.forEach(quad -> putMeshQuad(quad, pose, tintLayers,
                    provider.getBuffer(renderTypeFunction.apply(quad.chunkLayer()))));
        }
    }

    private void putLayeredPartQuads(List<BakedQuad> quads, PoseStack.Pose pose, int[] tintLayers,
                                     Function<ChunkSectionLayer, RenderType> renderTypeFunction) {
        for (BakedQuad quad : quads) {
            int tintIndex = quad.materialInfo().tintIndex();
            quadInstance.setColor(tintIndex != -1 ? tintLayer(tintLayers, tintIndex) : -1);
            provider.getBuffer(renderTypeFunction.apply(quad.materialInfo().layer()))
                    .putBakedQuad(pose, quad, quadInstance);
        }
    }

    private void putMeshQuad(QuadView quad, PoseStack.Pose pose, int[] tintLayers, VertexConsumer buffer) {
        Minecraft client = Minecraft.getInstance();
        if (!(client.getTextureManager().getTexture(quad.atlas().getTextureLocation()) instanceof TextureAtlas atlas)) {
            return;
        }
        TextureAtlasSprite sprite = atlas.spriteFinder().find(quad);
        // A baked quad has no vertex colour, so the quad's own colour rides on the tint.
        int tint = quad.tintIndex() != -1 ? tintLayer(tintLayers, quad.tintIndex()) : -1;
        quadInstance.setColor(ARGB.multiply(tint, quad.color(0)));
        buffer.putBakedQuad(pose, quad.toBakedQuad(sprite), quadInstance);
    }

    @Override
    public void submitBreakingBlockModel(PoseStack poseStack, List<BlockStateModelPart> parts, int progress,
                                         boolean isBlockTranslucent) {
    }

    /** A block a piston is moving, tessellated as the moving-block feature renderer does. */
    @Override
    public void submitMovingBlock(PoseStack poseStack, MovingBlockRenderState movingBlockRenderState, int outlineColor) {
        Minecraft client = Minecraft.getInstance();
        if (movingBlockRenderer == null) {
            // Light is discarded by the capture, so ambient occlusion has nothing to shade.
            movingBlockRenderer = new ModelBlockRenderer(false, false, client.getBlockColors());
        }
        BlockState blockState = movingBlockRenderState.blockState;
        BlockStateModel model = client.getModelManager().getBlockStateModelSet().get(blockState);
        boolean forceTranslucent = model.hasMaterialFlag(BakedQuad.FLAG_TRANSLUCENT);
        scratchStack.setIdentity();
        scratchStack.mulPose(poseStack.last().pose());
        BlockQuadOutput output = (x, y, z, quad, instance) -> {
            ChunkSectionLayer layer = forceTranslucent ? ChunkSectionLayer.TRANSLUCENT : quad.materialInfo().layer();
            RenderType renderType = switch (layer) {
                case SOLID -> RenderTypes.solidMovingBlock();
                case CUTOUT -> RenderTypes.cutoutMovingBlock();
                case TRANSLUCENT -> RenderTypes.translucentMovingBlock();
            };
            scratchStack.pushPose();
            scratchStack.translate(x, y, z);
            provider.getBuffer(renderType).putBakedQuad(scratchStack.last(), quad, instance);
            scratchStack.popPose();
        };
        long seed = blockState.getSeed(movingBlockRenderState.randomSeedPos);
        movingBlockRenderer.tesselateBlock(output, 0.0f, 0.0f, 0.0f, movingBlockRenderState,
                movingBlockRenderState.blockPos, blockState, model, seed);
    }

    // ---- Custom geometry ----

    @Override
    public void submitCustomGeometry(PoseStack poseStack, RenderType renderType,
                                     SubmitNodeCollector.CustomGeometryRenderer customGeometryRenderer) {
        if (renderType.isOutline()) {
            return;
        }
        customGeometryRenderer.render(poseStack.last(), provider.getBuffer(renderType));
    }

    /** The fire on a burning entity, drawn as the flame feature renderer draws it. */
    @Override
    public void submitFlame(PoseStack poseStack, EntityRenderState renderState, Quaternionf rotation) {
        Minecraft client = Minecraft.getInstance();
        TextureAtlasSprite fire1 = client.getAtlasManager().get(ModelBakery.FIRE_0);
        TextureAtlasSprite fire2 = client.getAtlasManager().get(ModelBakery.FIRE_1);
        VertexConsumer buffer = provider.getBuffer(RenderTypes.entityCutoutCull(TextureAtlas.LOCATION_BLOCKS));
        poseStack.pushPose();
        PoseStack.Pose pose = poseStack.last();
        float s = renderState.boundingBoxWidth * 1.4f;
        pose.scale(s, s, s);
        float r = 0.5f;
        float h = renderState.boundingBoxHeight / s;
        float yo = 0.0f;
        pose.rotate(rotation);
        pose.translate(0.0f, 0.0f, 0.3f - (int) h * 0.02f);
        float zo = 0.0f;
        int ss = 0;
        int lightCoords = LightCoordsUtil.withBlock(renderState.lightCoords, 15);
        while (h > 0.0f) {
            TextureAtlasSprite tex = ss % 2 == 0 ? fire1 : fire2;
            float u0 = tex.getU0();
            float v0 = tex.getV0();
            float u1 = tex.getU1();
            float v1 = tex.getV1();
            if (ss / 2 % 2 == 0) {
                float tmp = u1;
                u1 = u0;
                u0 = tmp;
            }
            fireVertex(pose, buffer, -r, -yo, zo, u1, v1, lightCoords);
            fireVertex(pose, buffer, r, -yo, zo, u0, v1, lightCoords);
            fireVertex(pose, buffer, r, 1.4f - yo, zo, u0, v0, lightCoords);
            fireVertex(pose, buffer, -r, 1.4f - yo, zo, u1, v0, lightCoords);
            h -= 0.45f;
            yo -= 0.45f;
            r *= 0.9f;
            zo -= 0.03f;
            ss++;
        }
        poseStack.popPose();
    }

    private static void fireVertex(PoseStack.Pose pose, VertexConsumer buffer, float x, float y, float z,
                                   float u, float v, int lightCoords) {
        buffer.addVertex(pose, x, y, z).setColor(-1).setUv(u, v).setUv1(0, 10).setLight(lightCoords)
                .setNormal(pose, 0.0f, 1.0f, 0.0f);
    }

    /** The lead between a mob and its holder, drawn as the leash feature renderer draws it. */
    @Override
    public void submitLeash(PoseStack poseStack, EntityRenderState.LeashState leashState) {
        VertexConsumer builder = provider.getBuffer(RenderTypes.leash());
        Matrix4f pose = scratchPose.set(poseStack.last().pose());
        float dx = (float) (leashState.end.x - leashState.start.x);
        float dy = (float) (leashState.end.y - leashState.start.y);
        float dz = (float) (leashState.end.z - leashState.start.z);
        float offsetFactor = Mth.invSqrt(dx * dx + dz * dz) * 0.05f / 2.0f;
        float dxOff = dz * offsetFactor;
        float dzOff = dx * offsetFactor;
        pose.translate((float) leashState.offset.x, (float) leashState.offset.y, (float) leashState.offset.z);
        for (int k = 0; k <= 24; k++) {
            addLeashVertexPair(builder, pose, dx, dy, dz, 0.05f, dxOff, dzOff, k, false, leashState);
        }
        for (int k = 24; k >= 0; k--) {
            addLeashVertexPair(builder, pose, dx, dy, dz, 0.0f, dxOff, dzOff, k, true, leashState);
        }
    }

    private static void addLeashVertexPair(VertexConsumer builder, Matrix4fc pose, float dx, float dy, float dz,
                                           float fudge, float dxOff, float dzOff, int k, boolean backwards,
                                           EntityRenderState.LeashState state) {
        float progress = k / 24.0f;
        int block = (int) Mth.lerp(progress, state.startBlockLight, state.endBlockLight);
        int sky = (int) Mth.lerp(progress, state.startSkyLight, state.endSkyLight);
        int lightCoords = LightCoordsUtil.pack(block, sky);
        float colorModifier = k % 2 == (backwards ? 1 : 0) ? 0.7f : 1.0f;
        float r = 0.5f * colorModifier;
        float g = 0.4f * colorModifier;
        float b = 0.3f * colorModifier;
        float x = dx * progress;
        float y;
        if (state.slack) {
            y = dy > 0.0f ? dy * progress * progress : dy - dy * (1.0f - progress) * (1.0f - progress);
        } else {
            y = dy * progress;
        }
        float z = dz * progress;
        builder.addVertex(pose, x - dxOff, y + fudge, z + dzOff).setColor(r, g, b, 1.0f).setLight(lightCoords);
        builder.addVertex(pose, x + dxOff, y + 0.05f - fudge, z - dzOff).setColor(r, g, b, 1.0f).setLight(lightCoords);
    }

    // ---- Text ----

    /**
     * A name tag laid out as vanilla lays it out, depth-tested copy only: the
     * see-through copy vanilla draws beside it has no place in a ray-traced scene.
     */
    @Override
    public void submitNameTag(PoseStack poseStack, @Nullable Vec3 nameTagAttachment, int offset, Component name,
                              boolean seeThrough, int lightCoords, CameraRenderState camera) {
        if (nameTagAttachment == null) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        poseStack.pushPose();
        poseStack.translate(nameTagAttachment.x, nameTagAttachment.y + 0.5, nameTagAttachment.z);
        poseStack.rotate(camera.orientation);
        poseStack.scale(0.025f, -0.025f, 0.025f);
        float x = -client.font.width(name) / 2.0f;
        FormattedCharSequence text = name.getVisualOrderText();
        if (seeThrough) {
            drawText(poseStack.last().pose(), Font.DisplayMode.NORMAL,
                    LightCoordsUtil.lightCoordsWithEmission(lightCoords, 2), x, offset, text, false, -1, 0, 0);
        } else {
            float backgroundAlpha = client.gameRenderer.gameRenderState().optionsRenderState.getBackgroundOpacity(0.25f);
            int backgroundColor = ARGB.color(backgroundAlpha, -16777216);
            float textAlpha = Math.max((backgroundAlpha + 0.75f) * 0.5f, 0.5f);
            drawText(poseStack.last().pose(), Font.DisplayMode.NORMAL, lightCoords, x, offset, text, false,
                    ARGB.color(textAlpha, -1), backgroundColor, 0);
        }
        poseStack.popPose();
    }

    @Override
    public void submitText(PoseStack poseStack, float x, float y, FormattedCharSequence string, boolean dropShadow,
                           Font.DisplayMode displayMode, int lightCoords, int color, int backgroundColor,
                           int outlineColor) {
        drawText(poseStack.last().pose(), displayMode, lightCoords, x, y, string, dropShadow, color,
                backgroundColor, outlineColor);
    }

    @Override
    public void submitTextBackground(PoseStack poseStack, float x0, float y0, float x1, float y1, int color,
                                     Font.DisplayMode displayMode, int lightCoords) {
        glyphs.begin(poseStack.last().pose(), lightCoords, displayMode);
        glyphs.acceptRenderable(Minecraft.getInstance().font.prepareBackground(x0, y0, x1, y1, color));
    }

    private void drawText(Matrix4fc pose, Font.DisplayMode displayMode, int lightCoords, float x, float y,
                          FormattedCharSequence string, boolean dropShadow, int color, int backgroundColor,
                          int outlineColor) {
        Font font = Minecraft.getInstance().font;
        glyphs.begin(pose, lightCoords, displayMode);
        TextGlyphCapture.prepare(font, string, x, y, dropShadow, displayMode, color,
                backgroundColor, outlineColor, glyphs);
    }

    /** Draws each laid-out glyph and effect into the capture under the text's pose. */
    private final class TextGlyphs implements TextGlyphCapture.Sink {
        private final Matrix4f pose = new Matrix4f();
        private int lightCoords;
        private Font.DisplayMode displayMode = Font.DisplayMode.NORMAL;

        void begin(Matrix4fc pose, int lightCoords, Font.DisplayMode displayMode) {
            this.pose.set(pose);
            this.lightCoords = lightCoords;
            this.displayMode = displayMode;
        }

        @Override
        public void displayMode(Font.DisplayMode mode) {
            displayMode = mode;
        }

        @Override
        public void acceptRenderable(TextRenderable renderable) {
            TextGlyphCapture.render(renderable, displayMode, pose, provider, lightCoords);
        }
    }

    // ---- Dropped submits ----

    @Override
    public void submitShadow(PoseStack poseStack, float radius, List<EntityRenderState.ShadowPiece> pieces) {
    }

    @Override
    public void submitShapeOutline(PoseStack poseStack, VoxelShape shape, RenderType renderType, int color,
                                   float width, boolean afterTerrain) {
    }

    @Override
    public void submitQuadParticleGroup(QuadParticleRenderState particles) {
    }

    @Override
    public void submitGizmoPrimitives(DrawableGizmoPrimitives.Group group, CameraRenderState camera, boolean onTop) {
    }

    @Override
    public <T extends SubmitNode> void submitCustom(SubmitRenderPhase<T> phase, T node) {
    }
}
