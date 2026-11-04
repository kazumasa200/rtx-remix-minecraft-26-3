/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.fixture;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/** Public vanilla-texture geometry only. It requires no third-party mod or assets. */
public final class ParticleFixture implements ClientModInitializer {
    public static boolean enabled;
    public static Vec3 origin = Vec3.ZERO;
    public static Vec3 right = new Vec3(1, 0, 0);
    public static Vec3 normal = new Vec3(0, 0, 1);
    private static ClientLevel level;
    private static boolean automatic;
    private static int ticks;

    @Override public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> dispatcher.register(
                ClientCommands.literal("remixquads").executes(context -> {
                    if (enabled) enabled = false; else activate(Minecraft.getInstance());
                    context.getSource().sendFeedback(Component.literal("Synthetic extracted quads: " + (enabled ? "on" : "off")));
                    return 1;
                })));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (level != client.level) { level = client.level; enabled = automatic = false; ticks = 0; }
            if (!automatic && client.player != null && ++ticks >= 80 && Boolean.getBoolean("rtxremix.particleFixture")) {
                activate(client);
                automatic = true;
            }
        });
    }

    public static void activate(Minecraft client) {
        if (client.player == null) return;
        normal = client.player.getLookAngle().normalize();
        right = normal.cross(new Vec3(0, 1, 0)).normalize();
        origin = client.player.getEyePosition().add(normal.scale(4));
        enabled = true;
    }
}
