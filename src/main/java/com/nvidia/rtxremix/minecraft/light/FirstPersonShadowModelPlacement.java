/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.light;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Applies a render-only offset to the hidden local-player model so first-person
 * analytic lights have clear space around their emitter.
 *
 * <p>Without it the hidden model's arm and held item stand in the light and lay a
 * shadow of a hand ahead of the player. {@link HeldLightPlacement} puts the light
 * just outside that clearance, so in the open the model stays where the player is;
 * near a wall, which pulls the light in, the model moves straight away from the
 * light. The offset follows that one direction through the spring: chosen among
 * fixed candidates and snapped to, the model held still while the view turned and
 * then jumped, which drew the player's shadow and reflection in steps.
 */
final class FirstPersonShadowModelPlacement {

    static final double HEAD_KEEP_OUT_RADIUS = 0.58;
    static final double TARGET_LIGHT_GAP = 0.39;
    static final double MAX_MODEL_OFFSET = 0.40;
    static final double COLLISION_EPSILON = 1.0e-4;
    static final double SPRING_ANGULAR_FREQUENCY = 14.0;

    private static final double DIRECTION_EPSILON_SQ = 1.0e-10;

    private Vec3 offset = Vec3.ZERO;
    private Vec3 velocity = Vec3.ZERO;

    Vec3 resolve(ClientLevel world, Camera camera, Entity player,
                  List<LightConstraint> lights, float dtSeconds) {
        Vec3 target = lights.isEmpty()
                ? Vec3.ZERO
                : clampToBlockCollisions(world, player, targetOffset(
                        camera.position(),
                        lights,
                        HeldLightPlacement.horizontalForward(camera.yRot()).reverse()));

        SpringStep spring = advanceSpring(
                offset,
                velocity,
                target,
                dtSeconds,
                SPRING_ANGULAR_FREQUENCY);
        Vec3 collisionSafeOffset = clampToBlockCollisions(world, player, spring.position);
        velocity = collisionSafeOffset.distanceToSqr(spring.position) > DIRECTION_EPSILON_SQ
                ? Vec3.ZERO
                : spring.velocity;
        offset = collisionSafeOffset;
        return offset;
    }

    /**
     * The horizontal offset that clears every light, along the mean horizontal
     * direction away from them, or along {@code fallback} when a light stands
     * straight above or below the head. It turns continuously with the lights.
     */
    static Vec3 targetOffset(Vec3 headCenter, List<LightConstraint> lights, Vec3 fallback) {
        Vec3 away = Vec3.ZERO;
        for (LightConstraint light : lights) {
            Vec3 fromLight = horizontal(headCenter.subtract(light.position));
            if (fromLight.lengthSqr() > DIRECTION_EPSILON_SQ) {
                away = away.add(fromLight.normalize());
            }
        }
        Vec3 direction = away.lengthSqr() > DIRECTION_EPSILON_SQ ? away.normalize() : fallback;

        double length = 0.0;
        for (LightConstraint light : lights) {
            length = Math.max(length, requiredTranslation(
                    headCenter.subtract(light.position),
                    direction,
                    clearCenterDistance(light.emitterRadius, light.targetGap)));
        }
        return direction.scale(Math.min(length, MAX_MODEL_OFFSET));
    }

    /** How far a light's centre must stand from the head's centre to keep its gap. */
    static double clearCenterDistance(double emitterRadius, double targetGap) {
        return HEAD_KEEP_OUT_RADIUS + emitterRadius + targetGap;
    }

    void clear() {
        offset = Vec3.ZERO;
        velocity = Vec3.ZERO;
    }

    static double requiredTranslation(
            Vec3 headCenterFromLight, Vec3 direction, double requiredCenterDistance) {
        double distanceSquared = headCenterFromLight.lengthSqr();
        double requiredSquared = requiredCenterDistance * requiredCenterDistance;
        if (distanceSquared >= requiredSquared) {
            return 0.0;
        }
        double projection = headCenterFromLight.dot(direction);
        double discriminant = projection * projection - distanceSquared + requiredSquared;
        return Math.max(0.0, -projection + Math.sqrt(Math.max(0.0, discriminant)));
    }

    static float clearanceRadianceScale(Vec3 displacedHeadCenter, LightConstraint light) {
        double surfaceGap = displacedHeadCenter.distanceTo(light.position)
                - HEAD_KEEP_OUT_RADIUS
                - light.emitterRadius;
        float normalized = (float) Math.clamp(surfaceGap / light.targetGap, 0.0, 1.0);
        return normalized * normalized * (3.0f - 2.0f * normalized);
    }

    static SpringStep advanceSpring(
            Vec3 position, Vec3 velocity, Vec3 target,
            float dtSeconds, double angularFrequency) {
        double dt = Math.clamp(
                dtSeconds, 0.0f, HeldLightPlacement.MAX_FRAME_DT_SECONDS);
        double omega = Math.max(0.0, angularFrequency);
        if (dt == 0.0 || omega == 0.0) {
            return new SpringStep(position, velocity);
        }

        double decay = Math.exp(-omega * dt);
        Vec3 displacement = position.subtract(target);
        Vec3 temporary = velocity.add(displacement.scale(omega)).scale(dt);
        Vec3 nextPosition = target.add(displacement.add(temporary).scale(decay));
        Vec3 nextVelocity = velocity.subtract(temporary.scale(omega)).scale(decay);
        return new SpringStep(nextPosition, nextVelocity);
    }

    private static Vec3 clampToBlockCollisions(
            ClientLevel world, Entity player, Vec3 desiredOffset) {
        if (desiredOffset.lengthSqr() <= DIRECTION_EPSILON_SQ) {
            return Vec3.ZERO;
        }

        AABB modelBounds = player.getBoundingBox().deflate(COLLISION_EPSILON);
        AABB desiredBounds = modelBounds.move(desiredOffset);
        AABB queryBounds = new AABB(
                Math.min(modelBounds.minX, desiredBounds.minX) - COLLISION_EPSILON,
                Math.min(modelBounds.minY, desiredBounds.minY) - COLLISION_EPSILON,
                Math.min(modelBounds.minZ, desiredBounds.minZ) - COLLISION_EPSILON,
                Math.max(modelBounds.maxX, desiredBounds.maxX) + COLLISION_EPSILON,
                Math.max(modelBounds.maxY, desiredBounds.maxY) + COLLISION_EPSILON,
                Math.max(modelBounds.maxZ, desiredBounds.maxZ) + COLLISION_EPSILON);
        List<AABB> nearbyBlocks = new ArrayList<>();
        world.getBlockCollisions(player, queryBounds).forEach(
                shape -> nearbyBlocks.addAll(shape.toAabbs()));
        return slideAgainstBlocks(modelBounds, desiredOffset, nearbyBlocks);
    }

    static Vec3 slideAgainstBlocks(
            AABB modelBounds, Vec3 desiredOffset, List<AABB> nearbyBlocks) {
        double xFirstX = clampX(modelBounds, desiredOffset.x, nearbyBlocks);
        AABB afterX = modelBounds.move(xFirstX, 0.0, 0.0);
        double xFirstZ = clampZ(afterX, desiredOffset.z, nearbyBlocks);
        Vec3 xFirst = new Vec3(xFirstX, desiredOffset.y, xFirstZ);

        double zFirstZ = clampZ(modelBounds, desiredOffset.z, nearbyBlocks);
        AABB afterZ = modelBounds.move(0.0, 0.0, zFirstZ);
        double zFirstX = clampX(afterZ, desiredOffset.x, nearbyBlocks);
        Vec3 zFirst = new Vec3(zFirstX, desiredOffset.y, zFirstZ);

        return horizontalErrorSquared(xFirst, desiredOffset)
                <= horizontalErrorSquared(zFirst, desiredOffset)
                ? xFirst
                : zFirst;
    }

    private static double clampX(AABB bounds, double desiredX, List<AABB> blocks) {
        double allowedX = desiredX;
        for (AABB block : blocks) {
            if (!overlaps(bounds.minY, bounds.maxY, block.minY, block.maxY)
                    || !overlaps(bounds.minZ, bounds.maxZ, block.minZ, block.maxZ)) {
                continue;
            }
            if (desiredX > 0.0 && bounds.maxX <= block.minX) {
                allowedX = Math.min(allowedX, block.minX - bounds.maxX);
            } else if (desiredX < 0.0 && bounds.minX >= block.maxX) {
                allowedX = Math.max(allowedX, block.maxX - bounds.minX);
            }
        }
        return allowedX;
    }

    private static double clampZ(AABB bounds, double desiredZ, List<AABB> blocks) {
        double allowedZ = desiredZ;
        for (AABB block : blocks) {
            if (!overlaps(bounds.minY, bounds.maxY, block.minY, block.maxY)
                    || !overlaps(bounds.minX, bounds.maxX, block.minX, block.maxX)) {
                continue;
            }
            if (desiredZ > 0.0 && bounds.maxZ <= block.minZ) {
                allowedZ = Math.min(allowedZ, block.minZ - bounds.maxZ);
            } else if (desiredZ < 0.0 && bounds.minZ >= block.maxZ) {
                allowedZ = Math.max(allowedZ, block.maxZ - bounds.minZ);
            }
        }
        return allowedZ;
    }

    private static double horizontalErrorSquared(Vec3 candidate, Vec3 desired) {
        double xError = candidate.x - desired.x;
        double zError = candidate.z - desired.z;
        return xError * xError + zError * zError;
    }

    private static boolean overlaps(
            double firstMin, double firstMax, double secondMin, double secondMax) {
        return firstMax > secondMin && firstMin < secondMax;
    }

    private static Vec3 horizontal(Vec3 vector) {
        return new Vec3(vector.x, 0.0, vector.z);
    }

    record LightConstraint(Vec3 position, double emitterRadius, double targetGap) {
        LightConstraint(Vec3 position, double emitterRadius) {
            this(position, emitterRadius, TARGET_LIGHT_GAP);
        }
    }

    record SpringStep(Vec3 position, Vec3 velocity) {
    }

}
