/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.nvidia.rtxremix.InstanceCategoryBit;
import com.nvidia.rtxremix.Transform;
import com.nvidia.rtxremix.minecraft.asset.SceneSpace;
import com.nvidia.rtxremix.minecraft.lens.FlareSources;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.phys.Vec3;

/**
 * Captures Minecraft's real first-person arm/item render and submits it as
 * Remix view-model geometry.
 *
 * <p>Minecraft remains responsible for hand selection, swing/equip/use
 * animations, maps, and item-specific transforms. The captured model is
 * attached to the camera in world space and submitted through Remix's
 * view-model camera for scene isolation. The hand-to-world FOV adjustment and
 * camera-relative view-model scale are folded into the captured rigid-part
 * transforms so Remix's view-model correction can remain an identity transform
 * and preserve precise motion history.
 */
public final class RemixFirstPersonPass implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(RemixFirstPersonPass.class);
    private static final String CAPTURE_TYPE_KEY = "rtx-remix:first_person_model";
    private static final float[] EMPTY_BONES = new float[0];

    /**
     * Camera-relative scale used to pull first-person geometry in front of
     * nearby world surfaces. This is baked into captured pivots instead of
     * {@code rtx.viewModel.scale}, keeping Remix's current/previous perspective
     * correction at identity for stable motion vectors.
     */
    public static final float VIEW_MODEL_SCALE = 0.1f;

    private final RemixVertexConsumerProvider provider = new RemixVertexConsumerProvider();
    private final RemixSubmitCollector collector = new RemixSubmitCollector(provider);
    private final Transform cameraToWorld = new Transform();
    private final Transform heldLightTransform = new Transform();
    private final Matrix4f correctedBone = new Matrix4f();
    private final Matrix4f viewCorrection = new Matrix4f();
    private final Matrix4f inverseModelView = new Matrix4f();
    private final Vector3f eye = new Vector3f();

    private RemixEntityPass.EntityCaptureResult pendingResult;
    private Vec3 pendingMainHandLightPosition;
    private Vec3 pendingOffHandLightPosition;
    private float[] reusableBoneData = EMPTY_BONES;

    /**
     * Submits the hands vanilla extracted through its own first-person renderer into
     * a Remix-only collector.
     */
    public void capture(FirstPersonHandsAndItemsRenderer handsRenderer,
                        float tickDelta,
                        PoseStack matrices,
                        PlayerRenderState playerState,
                        FirstPersonHandsAndItemsRenderState handsState,
                        CameraRenderState cameraState) {
        pendingResult = null;
        pendingMainHandLightPosition = null;
        pendingOffHandLightPosition = null;

        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client == null ? null : client.player;
        if (player == null || client.gameRenderer == null) {
            return;
        }

        provider.beginEntity();
        EntityRenderContext context = EntityRenderContext.get();
        context.begin(true);

        try {
            // Vanilla roots the hand stack at the inverse camera rotation and carries
            // the camera rotation in the model-view, so the model-view's origin is
            // where the eye sits in the capture's space.
            Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewStack());
            modelView.invert(inverseModelView).transformPosition(eye.set(0.0f, 0.0f, 0.0f));
            context.setEye(eye.x, eye.y, eye.z);

            handsRenderer.submitHandsWithItems(tickDelta, matrices, collector, playerState, handsState);

            List<RemixVertexConsumerProvider.CapturedLayer> layers = provider.endEntity();
            if (layers.isEmpty()) {
                return;
            }

            Camera camera = client.gameRenderer.mainCamera();
            float fovScale = calculateFovScale(camera.getFov(), cameraState.hudFov);
            setViewCorrection(viewCorrection, modelView, fovScale);

            int boneCount = context.getBoneCount();
            reusableBoneData = snapshotCorrectedBones(context, boneCount, reusableBoneData);
            setCameraToWorld(camera, cameraToWorld);
            pendingMainHandLightPosition = findHeldBlockLightPosition(
                    layers, context, reusableBoneData, boneCount, InteractionHand.MAIN_HAND);
            pendingOffHandLightPosition = findHeldBlockLightPosition(
                    layers, context, reusableBoneData, boneCount, InteractionHand.OFF_HAND);
            offerHeldFlare(layers, context, reusableBoneData, boneCount, InteractionHand.MAIN_HAND);
            offerHeldFlare(layers, context, reusableBoneData, boneCount, InteractionHand.OFF_HAND);

            pendingResult = new RemixEntityPass.EntityCaptureResult(
                    player,
                    Vec3.ZERO,
                    0.0f,
                    layers,
                    reusableBoneData,
                    boneCount,
                    CAPTURE_TYPE_KEY,
                    false);
        } catch (Exception e) {
            pendingResult = null;
            pendingMainHandLightPosition = null;
            pendingOffHandLightPosition = null;
            LOGGER.error("Error capturing first-person model", e);
        } finally {
            context.reset();
        }
    }

    public boolean hasPendingCapture() {
        return pendingResult != null;
    }

    /** Whether {@code result} is this pass's first-person model rather than an entity in the world. */
    static boolean isViewModelCapture(RemixEntityPass.EntityCaptureResult result) {
        return CAPTURE_TYPE_KEY.equals(result.entityTypeKey());
    }

    /** World-space emitter anchor used by the shaped held-beacon light path. */
    public Vec3 getPendingHeldLightPosition(EquipmentSlot slot) {
        if (pendingResult == null) return null;
        return switch (slot) {
            case MAINHAND -> pendingMainHandLightPosition;
            case OFFHAND -> pendingOffHandLightPosition;
            default -> null;
        };
    }

    /**
     * Submits the most recently captured first-person model. The result must be
     * consumed before the next {@link #capture} call because captured vertex
     * arrays are pooled by {@link RemixVertexConsumerProvider}.
     */
    public void submitPending(RemixEntitySubmitter submitter) {
        RemixEntityPass.EntityCaptureResult result = pendingResult;
        pendingResult = null;
        if (result == null) {
            return;
        }
        submitter.submitEntity(result, cameraToWorld, InstanceCategoryBit.VIEW_MODEL,
                RemixEntitySubmitter.NO_EXTRA_CATEGORIES);
    }

    public void clearPending() {
        pendingResult = null;
        pendingMainHandLightPosition = null;
        pendingOffHandLightPosition = null;
    }

    private Vec3 findHeldBlockLightPosition(
            List<RemixVertexConsumerProvider.CapturedLayer> layers,
            EntityRenderContext context,
            float[] transformData,
            int transformCount,
            InteractionHand hand) {
        RemixCapturingConsumer bestConsumer = null;
        int bestTransform = -1;
        int bestVertexCount = 0;

        // A consumer may be reused for both hands. Pick the transform carrying
        // the most block-atlas vertices for this hand; that is the baked item,
        // while skin/arm cuboids use the player texture instead.
        for (RemixVertexConsumerProvider.CapturedLayer layer : layers) {
            if (!BlockTextureManager.BLOCK_ATLAS_ID.equals(layer.textureId())) continue;
            RemixCapturingConsumer consumer = layer.consumer();
            int[] indices = consumer.getBoneIndices();
            for (int candidate = 0; candidate < transformCount; candidate++) {
                if (context.handForTransform(candidate) != hand) continue;
                int count = 0;
                for (int i = 0; i < layer.vertexCount(); i++) {
                    if (indices[i] == candidate) count++;
                }
                if (count > bestVertexCount) {
                    bestVertexCount = count;
                    bestTransform = candidate;
                    bestConsumer = consumer;
                }
            }
        }
        if (bestConsumer == null || bestVertexCount == 0) return null;

        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        int[] indices = bestConsumer.getBoneIndices();
        float[] px = bestConsumer.getPx(), py = bestConsumer.getPy(), pz = bestConsumer.getPz();
        for (int i = 0; i < bestConsumer.getVertexCount(); i++) {
            if (indices[i] != bestTransform) continue;
            minX = Math.min(minX, px[i]); maxX = Math.max(maxX, px[i]);
            minY = Math.min(minY, py[i]); maxY = Math.max(maxY, py[i]);
            minZ = Math.min(minZ, pz[i]); maxZ = Math.max(maxZ, pz[i]);
        }

        RemixEntitySubmitter.composeRigidTransform(cameraToWorld, transformData,
                transformCount, bestTransform, heldLightTransform);
        return transformPoint(heldLightTransform,
                (minX + maxX) * 0.5f,
                (minY + maxY) * 0.5f,
                (minZ + maxZ) * 0.5f);
    }

    /**
     * Offers the flame in this hand to the lens where it is drawn. The light that lights the
     * scene sits on the camera's centreline instead, nowhere near the flame on screen, so the
     * flare follows the flame's own quad: the first {@link HeldItemEffectsRenderer} emits on
     * the particle atlas for the hand.
     */
    private void offerHeldFlare(List<RemixVertexConsumerProvider.CapturedLayer> layers, EntityRenderContext context,
                                float[] transformData, int transformCount, InteractionHand hand) {
        for (RemixVertexConsumerProvider.CapturedLayer layer : layers) {
            if (!TextureAtlas.LOCATION_PARTICLES.equals(layer.textureId())) continue;
            RemixCapturingConsumer consumer = layer.consumer();
            int[] indices = consumer.getBoneIndices();
            for (int i = 0; i + 3 < layer.vertexCount(); i++) {
                int transform = indices[i];
                if (transform < 0 || transform >= transformCount || context.handForTransform(transform) != hand) {
                    continue;
                }
                float[] px = consumer.getPx(), py = consumer.getPy(), pz = consumer.getPz();
                RemixEntitySubmitter.composeRigidTransform(cameraToWorld, transformData,
                        transformCount, transform, heldLightTransform);
                Vec3 flame = transformPoint(heldLightTransform,
                        (px[i] + px[i + 2]) * 0.5f, (py[i] + py[i + 2]) * 0.5f, (pz[i] + pz[i + 2]) * 0.5f);
                FlareSources.offerHeld(flame.x, flame.y, flame.z);
                return;
            }
        }
    }

    /** A point of a scene-space transform, back in world space for the light and the lens. */
    static Vec3 transformPoint(Transform transform, float x, float y, float z) {
        float[] m = transform.matrix;
        SceneSpace.Origin origin = SceneSpace.current();
        return new Vec3(
                origin.worldX(m[0] * x + m[1] * y + m[2] * z + m[3]),
                m[4] * x + m[5] * y + m[6] * z + m[7],
                origin.worldZ(m[8] * x + m[9] * y + m[10] * z + m[11]));
    }

    private float[] snapshotCorrectedBones(EntityRenderContext context,
                                           int boneCount,
                                           float[] reuse) {
        if (boneCount == 0) {
            return EMPTY_BONES;
        }

        int required = boneCount * 12;
        float[] data = reuse.length >= required ? reuse : new float[required];
        List<Matrix4f> bones = context.getBoneTransforms();
        for (int i = 0; i < boneCount; i++) {
            viewCorrection.mul(bones.get(i), correctedBone);
            int off = i * 12;
            data[off] = correctedBone.m00();
            data[off + 1] = correctedBone.m10();
            data[off + 2] = correctedBone.m20();
            data[off + 3] = correctedBone.m30();
            data[off + 4] = correctedBone.m01();
            data[off + 5] = correctedBone.m11();
            data[off + 6] = correctedBone.m21();
            data[off + 7] = correctedBone.m31();
            data[off + 8] = correctedBone.m02();
            data[off + 9] = correctedBone.m12();
            data[off + 10] = correctedBone.m22();
            data[off + 11] = correctedBone.m32();
        }
        return data;
    }

    private static float calculateFovScale(double worldFov, double handFov) {
        double worldTangent = Math.tan(Math.toRadians(worldFov) * 0.5);
        double handTangent = Math.tan(Math.toRadians(handFov) * 0.5);
        if (!Double.isFinite(worldTangent) || !Double.isFinite(handTangent)
                || Math.abs(handTangent) < 1.0e-6) {
            return 1.0f;
        }
        return (float) (worldTangent / handTangent);
    }

    static void setViewCorrection(Matrix4f destination, Matrix4f modelView, float fovScale) {
        destination.identity()
                .scale(
                        fovScale * VIEW_MODEL_SCALE,
                        fovScale * VIEW_MODEL_SCALE,
                        VIEW_MODEL_SCALE)
                .mul(modelView);
    }

    /**
     * Builds an object-to-world transform whose local axes match Minecraft's
     * camera space: +X right, +Y up, and -Z forward. Its translation is in scene
     * space ({@link SceneSpace}), as everything handed to Remix is.
     */
    private static void setCameraToWorld(Camera camera, Transform transform) {
        Vec3 pos = camera.position();
        float yawRad = (float) Math.toRadians(-camera.yRot());
        float pitchRad = (float) Math.toRadians(camera.xRot());

        float forwardX = (float) (Math.sin(yawRad) * Math.cos(pitchRad));
        float forwardY = (float) (-Math.sin(pitchRad));
        float forwardZ = (float) (Math.cos(yawRad) * Math.cos(pitchRad));

        float rightX = (float) (-Math.cos(yawRad));
        float rightY = 0.0f;
        float rightZ = (float) Math.sin(yawRad);

        float upX = rightY * forwardZ - rightZ * forwardY;
        float upY = rightZ * forwardX - rightX * forwardZ;
        float upZ = rightX * forwardY - rightY * forwardX;

        transform.set(0, 0, rightX);
        transform.set(0, 1, upX);
        transform.set(0, 2, -forwardX);
        transform.set(0, 3, SceneSpace.x(pos.x));
        transform.set(1, 0, rightY);
        transform.set(1, 1, upY);
        transform.set(1, 2, -forwardY);
        transform.set(1, 3, SceneSpace.y(pos.y));
        transform.set(2, 0, rightZ);
        transform.set(2, 1, upZ);
        transform.set(2, 2, -forwardZ);
        transform.set(2, 3, SceneSpace.z(pos.z));
    }

    @Override
    public void close() {
        clearPending();
    }
}
