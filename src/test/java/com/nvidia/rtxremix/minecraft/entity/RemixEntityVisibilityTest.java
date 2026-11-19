/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import sun.misc.Unsafe;

import static org.junit.jupiter.api.Assertions.*;

class RemixEntityVisibilityTest {
    static {
        try {
            SharedConstants.getCurrentVersion();
        } catch (IllegalStateException unset) {
            SharedConstants.tryDetectVersion();
        }
        Bootstrap.bootStrap();
    }

    @Test
    void hiddenInPlaceCaptureIsClaimedWithoutCallingTheRendererAndInvalidatesItsPose() throws Exception {
        var visibility = new Visibility();
        visibility.hiddenEntity = true;
        var pass = new RemixEntityPass(id -> true, visibility);
        var submitter = new RemixEntitySubmitter(null, null);
        Entity entity = entity();
        entity.setId(17);
        var poses = poses(pass);
        snapshot(pass, entity, true);
        pass.beginFrame(submitter);

        assertTrue(pass.captureAndSubmit(entity, new AvatarRenderState(), null, null, 0, submitter),
                "a logical exclusion must not fall back to vanilla submission");
        assertFalse(poses.containsKey(17));
        assertFalse(pass.captureAndSubmit(entity, new AvatarRenderState(), null, null, 0, submitter),
                "the same entity cannot re-enter capture in the same frame");
        submitter.endFrame();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void globalExclusionUsesTheExtractedStateRatherThanTheEntityType(boolean avatar) throws Exception {
        var visibility = new Visibility();
        visibility.hiddenAvatar = avatar;
        visibility.hiddenOther = !avatar;
        var pass = new RemixEntityPass(id -> false, visibility);
        var submitter = new RemixEntitySubmitter(null, null);
        Entity entity = entity();
        EntityRenderState state = avatar ? new AvatarRenderState() : new EntityRenderState();
        pass.beginFrame(submitter);
        assertTrue(pass.captureAndSubmit(entity, state, null, null, 0, submitter));
        submitter.endFrame();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void hidingBlocksCachedPoseReplayAndShowingAgainRequestsFreshExtraction(boolean global) throws Throwable {
        var visibility = new Visibility();
        var pass = new RemixEntityPass(id -> true, visibility);
        var submitter = new RemixEntitySubmitter(null, null);
        Entity entity = entity();
        entity.setId(23);
        var poses = poses(pass);
        snapshot(pass, entity, true);
        if (global) visibility.hiddenAvatar = true;
        else visibility.hiddenEntity = true;
        var dispatcher = allocate(ExtractingDispatcher.class);
        pass.beginFrame(submitter);

        assertFalse(offFrustum(pass, entity, dispatcher, submitter));
        assertFalse(poses.containsKey(23), "hiding must discard the cached visible pose");
        submitter.endFrame();

        visibility.hiddenAvatar = false;
        visibility.hiddenEntity = false;
        pass.resetFrame();
        pass.beginFrame(submitter);
        assertThrows(ExtractionRequested.class, () -> offFrustum(pass, entity, dispatcher, submitter),
                "showing must reach fresh extraction instead of resurrecting the previous pose");
        submitter.endFrame();
    }

    @Test
    void anAllowedOffscreenEntityStillReachesExtractionWithoutRasterVisibilityChecks() throws Exception {
        var pass = new RemixEntityPass(id -> false, EntityCaptureVisibility.VISIBLE);
        var submitter = new RemixEntitySubmitter(null, null);
        Entity entity = entity();
        var dispatcher = allocate(ExtractingDispatcher.class);
        pass.beginFrame(submitter);
        assertThrows(ExtractionRequested.class, () -> offFrustum(pass, entity, dispatcher, submitter));
        submitter.endFrame();
    }

    private static boolean offFrustum(RemixEntityPass pass, Entity entity,
                                      EntityRenderDispatcher dispatcher, RemixEntitySubmitter submitter) throws Throwable {
        Method method = RemixEntityPass.class.getDeclaredMethod("submitOffFrustum", Entity.class,
                Vec3.class, float.class, EntityRenderDispatcher.class, CameraRenderState.class,
                RemixEntitySubmitter.class);
        method.setAccessible(true);
        try {
            return (boolean) method.invoke(pass, entity, Vec3.ZERO, 0.0f, dispatcher, null, submitter);
        } catch (InvocationTargetException error) {
            throw error.getCause();
        }
    }

    @SuppressWarnings("unchecked")
    private static Int2ObjectOpenHashMap<Object> poses(RemixEntityPass pass) throws Exception {
        Field field = RemixEntityPass.class.getDeclaredField("poseSnapshots");
        field.setAccessible(true);
        return (Int2ObjectOpenHashMap<Object>) field.get(pass);
    }

    private static void snapshot(RemixEntityPass pass, Entity entity, boolean avatar) throws Exception {
        Method method = RemixEntityPass.class.getDeclaredMethod("snapshotPose", Entity.class,
                boolean.class, RemixEntityPass.EntityCaptureResult.class);
        method.setAccessible(true);
        method.invoke(pass, entity, avatar, new RemixEntityPass.EntityCaptureResult(
                entity, Vec3.ZERO, 0, List.of(), new float[0], 0, "test", false));
    }

    private static TestEntity entity() throws Exception {
        TestEntity entity = allocate(TestEntity.class);
        entity.setId(31);
        return entity;
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(((Unsafe) field.get(null)).allocateInstance(type));
    }

    private static final class Visibility implements EntityCaptureVisibility {
        boolean hiddenEntity, hiddenAvatar, hiddenOther;
        @Override public boolean isEntityHidden(Entity entity) { return hiddenEntity; }
        @Override public boolean isStateHidden(boolean avatar) { return avatar ? hiddenAvatar : hiddenOther; }
    }

    private static final class TestEntity extends ArmorStand {
        private TestEntity() { super(EntityTypes.ARMOR_STAND, null); }
        @Override public double distanceToSqr(Vec3 camera) { return 100.0 * 100.0; }
    }

    /** Stops a headless test at the boundary before model evaluation or native work. */
    private static final class ExtractionRequested extends Error { }

    private static final class ExtractingDispatcher extends EntityRenderDispatcher {
        private ExtractingDispatcher() { super(null, null, null, null, null, null, null, null, null, null, null); }
        @Override public <E extends Entity> EntityRenderState extractEntity(E entity, float tickDelta) {
            throw new ExtractionRequested();
        }
    }
}
