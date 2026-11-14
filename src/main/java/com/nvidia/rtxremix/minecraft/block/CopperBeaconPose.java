/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.block;

import net.minecraft.core.Direction;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Shared articulated transform used by both the visible head and its Remix light. */
public final class CopperBeaconPose {

    private static final float HEAD_UP_OFFSET = 2.0f / 16.0f;
    private static final float LIGHT_FORWARD_OFFSET = 5.0f / 16.0f;

    private CopperBeaconPose() {}

    /**
     * The direction a beacon's idle head points along. A block state whose facing lies on its
     * mount's axis is a state of the block all the same, though no placement makes one: the
     * debug world holds every state, and commands and structures can set one. It takes the
     * forward a placement would have given it, rather than failing every frame it is drawn.
     */
    public static Direction forward(Direction mount, Direction facing) {
        return CopperBeaconState.orthogonalForward(facing, Direction.NORTH, mount);
    }

    public static void evaluate(
            Direction mount,
            Direction neutralForward,
            float yawDegrees,
            float pitchDegrees,
            Pose destination) {
        neutralForward = forward(mount, neutralForward);
        mountingRotation(
                mount,
                neutralForward,
                destination.mountingRotation,
                destination.up,
                destination.forward,
                destination.rotatedForward,
                destination.cross,
                destination.upRotation);
        destination.modelYawRadians = (float) Math.toRadians(-yawDegrees);
        destination.modelPitchRadians = (float) Math.toRadians(pitchDegrees);

        destination.articulationRotation
                .rotationY(destination.modelYawRadians)
                .rotateX(destination.modelPitchRadians);
        destination.direction
                .set(0.0f, 0.0f, -1.0f)
                .rotate(destination.articulationRotation)
                .rotate(destination.mountingRotation)
                .normalize();
        destination.xAxis
                .set(1.0f, 0.0f, 0.0f)
                .rotate(destination.articulationRotation)
                .rotate(destination.mountingRotation)
                .normalize();
        destination.yAxis
                .set(0.0f, 1.0f, 0.0f)
                .rotate(destination.articulationRotation)
                .rotate(destination.mountingRotation)
                .normalize();

        destination.emitter
                .set(
                        mount.getStepX(),
                        mount.getStepY(),
                        mount.getStepZ())
                .mul(HEAD_UP_OFFSET)
                .fma(LIGHT_FORWARD_OFFSET, destination.direction)
                .add(0.5f, 0.5f, 0.5f);
    }

    public static Quaternionf mountingRotation(
            Direction upDirection, Direction forwardDirection, Quaternionf destination) {
        return mountingRotation(
                upDirection,
                forwardDirection,
                destination,
                new Vector3f(),
                new Vector3f(),
                new Vector3f(),
                new Vector3f(),
                new Quaternionf());
    }

    private static Quaternionf mountingRotation(
            Direction upDirection,
            Direction forwardDirection,
            Quaternionf destination,
            Vector3f up,
            Vector3f forward,
            Vector3f rotatedForward,
            Vector3f cross,
            Quaternionf upRotation) {
        direction(upDirection, up);
        direction(forward(upDirection, forwardDirection), forward);

        upRotation.rotationTo(0.0f, 1.0f, 0.0f, up.x, up.y, up.z);
        rotatedForward.set(0.0f, 0.0f, -1.0f).rotate(upRotation);
        float angle = (float) Math.atan2(
                up.dot(cross.set(rotatedForward).cross(forward)),
                rotatedForward.dot(forward));
        return destination.rotationAxis(angle, up).mul(upRotation);
    }

    private static Vector3f direction(Direction direction, Vector3f destination) {
        return destination.set(
                direction.getStepX(), direction.getStepY(), direction.getStepZ());
    }

    /** Render-thread scratch output; one instance can be reused across evaluations. */
    public static final class Pose {
        private final Quaternionf mountingRotation = new Quaternionf();
        private final Quaternionf articulationRotation = new Quaternionf();
        private final Quaternionf upRotation = new Quaternionf();
        private final Vector3f direction = new Vector3f();
        private final Vector3f emitter = new Vector3f();
        private final Vector3f xAxis = new Vector3f();
        private final Vector3f yAxis = new Vector3f();
        private final Vector3f up = new Vector3f();
        private final Vector3f forward = new Vector3f();
        private final Vector3f rotatedForward = new Vector3f();
        private final Vector3f cross = new Vector3f();
        private float modelYawRadians;
        private float modelPitchRadians;

        public Quaternionf mountingRotation() {
            return mountingRotation;
        }

        public Vector3f direction() {
            return direction;
        }

        public Vector3f emitter() {
            return emitter;
        }

        public Vector3f xAxis() {
            return xAxis;
        }

        public Vector3f yAxis() {
            return yAxis;
        }

        public float modelYawRadians() {
            return modelYawRadians;
        }

        public float modelPitchRadians() {
            return modelPitchRadians;
        }
    }
}
