/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The mod's Vulkan layer for the runtime ({@code runtime-patch/layer}): armed before the runtime
 * starts, so the Vulkan loader loads it into the runtime's instance, where it replaces the shader
 * modules the patch set in {@code remix-sdk/patches/} lists (built by {@code tools\rtx.bat patches
 * build}). The runtime file is never written. Arming logs each patch group as ACTIVE or MISMATCH; a
 * group applies only when every one of its modules matched the shipped runtime. Render thread, once
 * per process. Never throws; a failure leaves the runtime as shipped and says why in the log.
 * {@code -Drtxremix.runtimePatches=false} runs the runtime unpatched.
 */
public final class RuntimePatchLayer {

    private static final Logger LOGGER = LoggerFactory.getLogger(RuntimePatchLayer.class);
    static final String LIBRARY = "rtxremix_patch_layer.dll";
    private static final Pattern ACTIVE_GROUP = Pattern.compile("([a-z0-9-]+) ACTIVE");
    /** The group whose shaders march the Unbound clouds in the runtime (runtime_patch/clouds.py). */
    public static final String UNBOUND_CLOUDS = "unbound-clouds";

    private static MethodHandle status;
    private static MethodHandle channelStatus;
    private static MethodHandle publishFrame;
    private static MethodHandle upload;
    private static MethodHandle groups;
    /** The groups arming reported ACTIVE. Written once on the render thread before the runtime starts. */
    private static volatile Set<String> active = Set.of();
    private static volatile boolean shadowAbsorption;

    private RuntimePatchLayer() {
    }

    /** Arms the layer in {@code sdkDir}; call before {@code RemixAPI.load}. */
    static void arm(Path sdkDir) {
        if (!Boolean.parseBoolean(System.getProperty("rtxremix.runtimePatches", "true"))) {
            LOGGER.info("Runtime patch layer off (rtxremix.runtimePatches=false)");
            return;
        }
        Path library = sdkDir.resolve(LIBRARY);
        if (!Files.isRegularFile(library)) {
            LOGGER.info("Runtime patch layer not installed ({} missing)", library);
            return;
        }
        try {
            Linker linker = Linker.nativeLinker();
            SymbolLookup layer = SymbolLookup.libraryLookup(library, Arena.global());
            MethodHandle arm = linker.downcallHandle(layer.find("rrp_arm").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            status = linker.downcallHandle(layer.find("rrp_status").orElseThrow(),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            channelStatus = linker.downcallHandle(layer.find("rrp_channel_status").orElseThrow(),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            publishFrame = linker.downcallHandle(layer.find("rrp_publish_frame").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            upload = linker.downcallHandle(layer.find("rrp_upload").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG));
            groups = linker.downcallHandle(layer.find("rrp_groups").orElseThrow(),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment report = arena.allocate(512);
                int result = (int) arm.invokeExact(report, 512);
                String text = report.getString(0, StandardCharsets.UTF_8);
                if (result < 0) {
                    LOGGER.warn("Runtime patch layer not armed: {}", text);
                    status = null;
                } else {
                    LOGGER.info("Runtime patch layer: {}", text);
                    setActive(text);
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("Runtime patch layer not armed: {}", t.toString());
            status = null;
        }
    }

    private static void setActive(String report) {
        Set<String> found = new HashSet<>();
        Matcher m = ACTIVE_GROUP.matcher(report);
        while (m.find()) {
            found.add(m.group(1));
        }
        active = Set.copyOf(found);
        shadowAbsorption = found.contains("shadow-absorption");
    }

    /** Whether the patch group (a pass id, such as {@code shadow-absorption}) applies to the runtime. */
    public static boolean isActive(String group) {
        return active.contains(group);
    }

    /**
     * Whether shadow rays absorb by the distance between a surface's entries and exits: water's top is
     * then emitted once, since a reversed copy in its plane cancelled every crossing. Read per fluid
     * quad on capture workers, so a field rather than a set lookup.
     */
    public static boolean shadowAbsorption() {
        return shadowAbsorption;
    }

    /** Logs what the layer saw; call after the runtime's startup created its instance. */
    static void logStatus(String when) {
        if (status == null) {
            return;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ValueLayout.JAVA_INT, 6);
            status.invokeExact(out);
            int hooked = out.getAtIndex(ValueLayout.JAVA_INT, 1);
            String line = "instanceHooked=" + hooked + " devices=" + out.getAtIndex(ValueLayout.JAVA_INT, 2)
                    + " modulesSeen=" + out.getAtIndex(ValueLayout.JAVA_INT, 3)
                    + " modulesReplaced=" + out.getAtIndex(ValueLayout.JAVA_INT, 4)
                    + " replacementsRefused=" + out.getAtIndex(ValueLayout.JAVA_INT, 5);
            if (hooked == 0) {
                LOGGER.warn("Runtime patch layer armed but the Vulkan loader did not load it ({}): {}", when, line);
            } else {
                LOGGER.info("Runtime patch layer ({}): {}", when, line);
            }
            // The device may have dropped a group it has no frame channel for.
            MemorySegment published = arena.allocate(512);
            groups.invokeExact(published, 512);
            String groupReport = published.getString(0, StandardCharsets.UTF_8);
            if (!groupReport.isEmpty()) {
                setActive(groupReport);
                LOGGER.info("Runtime patch groups ({}): {}", when, groupReport);
            }
            MemorySegment channel = arena.allocate(ValueLayout.JAVA_INT, 8);
            MemorySegment report = arena.allocate(256);
            channelStatus.invokeExact(channel, report, 256);
            LOGGER.info("Runtime patch channel ({}){}: state={} layoutsExtended={} layoutsRefused={} setBinds={} "
                            + "framesLatched={} submitsSkipped={} uploads={}", when,
                    report.getString(0, StandardCharsets.UTF_8),
                    channel.getAtIndex(ValueLayout.JAVA_INT, 0), channel.getAtIndex(ValueLayout.JAVA_INT, 1),
                    channel.getAtIndex(ValueLayout.JAVA_INT, 2), channel.getAtIndex(ValueLayout.JAVA_INT, 3),
                    channel.getAtIndex(ValueLayout.JAVA_INT, 4), channel.getAtIndex(ValueLayout.JAVA_INT, 5),
                    channel.getAtIndex(ValueLayout.JAVA_INT, 6));
        } catch (Throwable t) {
            LOGGER.warn("Runtime patch layer status unavailable: {}", t.toString());
        }
    }

    /**
     * Hands the patched shaders the frame block (set 4, binding 0) for the runtime's next frame; render
     * thread. False when the layer has no channel.
     */
    public static boolean publishFrame(MemorySegment frame, int bytes) {
        if (publishFrame == null) {
            return false;
        }
        try {
            return (int) publishFrame.invokeExact(frame, bytes) == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Hands the patched shaders a texture of set 4 (1 noise, 2 cirrus), uploaded with the next frame. */
    public static boolean upload(int binding, MemorySegment bytes) {
        if (upload == null) {
            return false;
        }
        try {
            return (int) upload.invokeExact(binding, bytes, bytes.byteSize()) == 0;
        } catch (Throwable t) {
            return false;
        }
    }
}
