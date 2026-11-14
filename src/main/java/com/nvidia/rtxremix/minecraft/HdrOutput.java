/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * HDR display output on the runtime-patch branch, the one owner of whether the screen is HDR and how bright. With
 * HDR Output saved on and Windows HDR on for the window's monitor, the runtime patch layer presents scRGB
 * ({@code runtime-patch/layer/hdr.h}): the patched final combine writes the world through the HDR curve into the
 * widened shared image, and GL composes the picture outside the main target
 * ({@link com.nvidia.rtxremix.minecraft.gl.HdrComposite}): the world, the lens and the weather go into an fp16 image,
 * and at present the GUI is laid over it at paper white.
 *
 * <p>The layer's state is read once a frame ({@link RuntimePatchLayer#refreshHdr}); whether GL composed the frame
 * outside the main target is decided per frame here, never through the frame block, which reaches the runtime a
 * frame late.
 * A launch with HDR on leaves a marker in the config directory until it has presented {@link #SURVIVED_FRAMES}
 * frames, so a runtime that dies on the scRGB swapchain makes the next launch SDR until the switch is turned on
 * again. Render thread, except the settings.
 */
public final class HdrOutput {

    private static final Logger LOGGER = LoggerFactory.getLogger(HdrOutput.class);

    /** Read from the saved settings before the runtime starts (RtxRemixMod); applies from the next launch. */
    public static final String SETTING = "hdr.output";
    /** scRGB's 1.0. */
    public static final float SCRGB_NITS = 80f;
    public static final float MIN_PAPER_WHITE = 80f, DEFAULT_PAPER_WHITE = 200f, MAX_PAPER_WHITE = 480f;
    public static final float MIN_PEAK = 400f, DEFAULT_PEAK = 1000f, MAX_PEAK = 4000f;
    /** The frame block's HdrMode (rrp_frame.glsl). */
    static final int MODE_OFF = 0, MODE_HDR = 1, MODE_SDR_REFERENCE = 2;
    /** The layer's states (hdr.h). */
    static final int STATE_ACTIVE = 2;
    static final String ATTEMPT = "rtxremix-hdr.attempt", FAILED = "rtxremix-hdr.failed";
    static final int SURVIVED_FRAMES = 600;

    private static volatile boolean requested;
    private static volatile float paperWhite = DEFAULT_PAPER_WHITE;
    private static volatile float peak = DEFAULT_PEAK;
    private static volatile boolean sdrReference;
    private static volatile boolean testPattern;
    private static volatile boolean active;
    private static volatile boolean interopWidened;
    private static boolean worldTaken;
    private static Path configDir;
    /** Whether this launch asks the layer for HDR: the switch was saved on and no failed launch holds it off. */
    private static boolean asking;

    private HdrOutput() {
    }

    /**
     * Startup, before the layer is armed: what the saved switch asks, and whether this launch may ask the layer for
     * HDR. A marker left by a launch that did not survive becomes the failed marker, which holds HDR off.
     */
    static boolean startup(Path config, boolean saved) {
        asking = askable(config, saved);
        return asking;
    }

    static boolean asking() {
        return asking;
    }

    private static boolean askable(Path config, boolean saved) {
        configDir = config;
        requested = saved;
        if (!saved) {
            return false;
        }
        try {
            Path attempt = config.resolve(ATTEMPT);
            if (Files.exists(attempt)) {
                Files.move(attempt, config.resolve(FAILED), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            if (Files.exists(config.resolve(FAILED))) {
                LOGGER.warn("HDR output skipped: the last launch with it did not reach {} frames. Turn HDR Output off "
                        + "and on to try again ({}).", SURVIVED_FRAMES, config.resolve(FAILED));
                return false;
            }
        } catch (IOException e) {
            LOGGER.warn("HDR output skipped: its launch marker could not be read: {}", e.toString());
            return false;
        }
        return true;
    }

    /** The layer accepted: marks the launch until it survives. */
    static void attempted() {
        if (configDir == null) {
            return;
        }
        try {
            Files.writeString(configDir.resolve(ATTEMPT), "HDR output launch in progress\n");
        } catch (IOException e) {
            LOGGER.warn("HDR output launch marker not written: {}", e.toString());
        }
    }

    /** Called with each presented frame; the launch survived once it reaches SURVIVED_FRAMES. */
    static void presented(int frame) {
        if (frame != SURVIVED_FRAMES || configDir == null) {
            return;
        }
        try {
            Files.deleteIfExists(configDir.resolve(ATTEMPT));
        } catch (IOException e) {
            LOGGER.warn("HDR output launch marker not removed: {}", e.toString());
        }
    }

    public static boolean isRequested() {
        return requested;
    }

    /** The HDR Output switch: applies from the next launch. Turning it on clears a failed launch's marker. */
    public static void setRequested(boolean on) {
        if (on && !requested && configDir != null) {
            try {
                Files.deleteIfExists(configDir.resolve(FAILED));
            } catch (IOException e) {
                LOGGER.warn("HDR output failure marker not removed: {}", e.toString());
            }
        }
        requested = on;
    }

    public static float paperWhite() {
        return paperWhite;
    }

    public static void setPaperWhite(float nits) {
        paperWhite = Math.max(MIN_PAPER_WHITE, Math.min(MAX_PAPER_WHITE, nits));
    }

    public static float peak() {
        return peak;
    }

    public static void setPeak(float nits) {
        peak = Math.max(MIN_PEAK, Math.min(MAX_PEAK, nits));
    }

    public static boolean isSdrReference() {
        return sdrReference;
    }

    public static void setSdrReference(boolean on) {
        sdrReference = on;
    }

    public static boolean isTestPattern() {
        return testPattern;
    }

    public static void setTestPattern(boolean on) {
        testPattern = on;
    }

    /**
     * The layer's state this frame: whether its swapchain is scRGB, whether it widened the shared image, and whether
     * the hdr-output patch group, which writes scRGB, applies.
     */
    static void refresh(int state, boolean widened, boolean group) {
        active = state == STATE_ACTIVE && widened && group;
        interopWidened = widened;
    }

    /** The screen is scRGB this frame: the patched shaders write scRGB and the GUI is laid over at present. */
    public static boolean active() {
        return active;
    }

    /** The layer widened the shared colour image to fp16, so GL imports it as such. */
    public static boolean interopWidened() {
        return interopWidened;
    }

    /** The frame block's HdrMode for this frame. */
    public static int frameMode() {
        return mode(active, sdrReference);
    }

    static int mode(boolean active, boolean sdrReference) {
        if (!active) {
            return MODE_OFF;
        }
        return sdrReference ? MODE_SDR_REFERENCE : MODE_HDR;
    }

    /**
     * Whether this frame draws the path-traced world, the lens and the weather into HdrComposite's image instead of
     * the main target; then the main target holds only other mods' draws over transparency, and the GUI that
     * follows. Called where the world would be drawn; recorded for {@link #takenWorld}.
     */
    public static boolean takeWorld() {
        worldTaken = active;
        return worldTaken;
    }

    /** Whether this frame took the world; ends the frame's choice. Called once per present. */
    public static boolean takenWorld() {
        boolean taken = worldTaken;
        worldTaken = false;
        return taken;
    }
}
