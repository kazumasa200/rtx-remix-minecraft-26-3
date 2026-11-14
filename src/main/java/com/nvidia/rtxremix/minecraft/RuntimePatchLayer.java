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
import java.util.Optional;
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
 * {@code -Drtxremix.runtimePatches=false} runs the runtime unpatched. With the Debug page's Log
 * Presentation Survey saved on, arming also turns on the layer's record of how the runtime presents
 * ({@code runtime-patch/layer/survey.cpp}), which {@link #logSurvey} logs.
 */
public final class RuntimePatchLayer {

    private static final Logger LOGGER = LoggerFactory.getLogger(RuntimePatchLayer.class);
    static final String LIBRARY = "rtxremix_patch_layer.dll";
    private static final Pattern ACTIVE_GROUP = Pattern.compile("([a-z0-9-]+) ACTIVE");
    /** The group whose shaders march the Unbound clouds in the runtime (runtime_patch/clouds.py). */
    public static final String UNBOUND_CLOUDS = "unbound-clouds";
    /** The Debug page's Presentation Survey, read from the saved settings before the runtime starts. */
    public static final String SURVEY_SETTING = "debug.presentationSurvey";
    private static final int SURVEY_CHUNK = 16384;

    private static MethodHandle status;
    private static MethodHandle channelStatus;
    private static MethodHandle publishFrame;
    private static MethodHandle upload;
    private static MethodHandle groups;
    /** The survey's lines; set only when arming turned the survey on. */
    private static MethodHandle surveyTake;
    /** What the switch asks for the next launch; the survey runs only when it was on at this launch's arming. */
    private static volatile boolean surveyRequested;
    /** HDR output's state (rrp_hdr_status); set only when arming asked for HDR and the layer accepted. */
    private static MethodHandle hdrStatus;
    private static final MemorySegment HDR_STATUS = Arena.global().allocate(ValueLayout.JAVA_INT, 6);
    private static final MemorySegment HDR_REASON = Arena.global().allocate(256);
    /** The HDR patch group (runtime_patch/hdr.py): the final combine and the sRGB pass write scRGB. */
    public static final String HDR_OUTPUT = "hdr-output";
    /** The groups arming reported ACTIVE. Written once on the render thread before the runtime starts. */
    private static volatile Set<String> active = Set.of();
    private static volatile boolean shadowAbsorption;

    private RuntimePatchLayer() {
    }

    /**
     * Arms the layer in {@code sdkDir}; call before {@code RemixAPI.load}. window is the game window, whose
     * monitor decides whether HDR output can be accepted.
     */
    static void arm(Path sdkDir, long window, boolean askHdr) {
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
            if (surveyRequested) {
                Optional<MemorySegment> on = layer.find("rrp_survey");
                Optional<MemorySegment> take = layer.find("rrp_survey_take");
                if (on.isPresent() && take.isPresent()) {
                    MethodHandle survey = linker.downcallHandle(on.get(), FunctionDescriptor.ofVoid());
                    survey.invokeExact();
                    surveyTake = linker.downcallHandle(take.get(), FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
                    LOGGER.info("Runtime presentation survey on (Debug page)");
                } else {
                    LOGGER.warn("Runtime presentation survey asked for, but this layer build has none");
                }
            }
            if (askHdr) {
                askHdr(linker, layer, window);
            }
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

    private static void askHdr(Linker linker, SymbolLookup layer, long window) throws Throwable {
        Optional<MemorySegment> ask = layer.find("rrp_hdr");
        Optional<MemorySegment> state = layer.find("rrp_hdr_status");
        if (ask.isEmpty() || state.isEmpty()) {
            LOGGER.warn("HDR output asked for, but this layer build has none");
            return;
        }
        MethodHandle request = linker.downcallHandle(ask.get(), FunctionDescriptor.of(ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment report = arena.allocate(512);
            int accepted = (int) request.invokeExact(MemorySegment.ofAddress(window), report, 512);
            LOGGER.info("HDR output: {}", report.getString(0, StandardCharsets.UTF_8));
            if (accepted != 0) {
                hdrStatus = linker.downcallHandle(state.get(), FunctionDescriptor.ofVoid(ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
                HdrOutput.attempted();
            }
        }
    }

    /** Reads HDR output's state from the layer into HdrOutput; once a frame, before the GL side uses it. */
    static void refreshHdr() {
        if (hdrStatus == null) {
            return;
        }
        try {
            hdrStatus.invokeExact(HDR_STATUS, HDR_REASON, 256);
            HdrOutput.refresh(HDR_STATUS.getAtIndex(ValueLayout.JAVA_INT, 0),
                    HDR_STATUS.getAtIndex(ValueLayout.JAVA_INT, 3) != 0, isActive(HDR_OUTPUT));
        } catch (Throwable t) {
            hdrStatus = null;
            HdrOutput.refresh(0, false, false);
        }
    }

    private static void logHdr(String when) {
        if (hdrStatus == null) {
            return;
        }
        refreshHdr();
        LOGGER.info("HDR output ({}): {}; state={} swapchains={} widened={} interopWidened={} windowsHdr={} "
                        + "sdrWhite={} nits; patch group {}", when, HDR_REASON.getString(0, StandardCharsets.UTF_8),
                HDR_STATUS.getAtIndex(ValueLayout.JAVA_INT, 0), HDR_STATUS.getAtIndex(ValueLayout.JAVA_INT, 1),
                HDR_STATUS.getAtIndex(ValueLayout.JAVA_INT, 2), HDR_STATUS.getAtIndex(ValueLayout.JAVA_INT, 3),
                HDR_STATUS.getAtIndex(ValueLayout.JAVA_INT, 4), HDR_STATUS.getAtIndex(ValueLayout.JAVA_INT, 5),
                isActive(HDR_OUTPUT) ? "ACTIVE" : "not active");
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
        logHdr(when);
    }

    public static boolean isSurveyRequested() {
        return surveyRequested;
    }

    /** Asks for the presentation survey from the next launch, when the layer is armed before the runtime starts. */
    public static void setSurveyRequested(boolean on) {
        surveyRequested = on;
    }

    /**
     * Logs what the survey recorded since it was last asked, on the 120th presented frame and every 1200th,
     * with how often each kind happened on the 1200th and every 6000th after it. Render thread; does nothing
     * unless the survey runs.
     */
    static void logSurvey(int frame) {
        if (surveyTake == null || (frame != 120 && frame % 1200 != 0)) {
            return;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(SURVEY_CHUNK);
            int counts = frame % 6000 == 1200 ? 1 : 0;
            for (int i = 0; i < 64; i++) {
                int written = (int) surveyTake.invokeExact(counts, out, SURVEY_CHUNK);
                if (written <= 0) {
                    break;
                }
                counts = 0;
                for (String line : out.getString(0, StandardCharsets.UTF_8).split("\n")) {
                    LOGGER.info("Runtime presentation survey (frame {}): {}", frame, line);
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("Runtime presentation survey unavailable: {}", t.toString());
            surveyTake = null;
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
