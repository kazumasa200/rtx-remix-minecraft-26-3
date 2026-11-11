/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.gl;

/**
 * Decides whether the overlay may Y-flip the shared interop image.
 *
 * <p>The image Remix presents is the same image the overlay reads. After the HUD
 * is drawn, that image holds an upright composite. Shader compilation and a pause
 * still signal a finished submit, so a flip of that composite turns the previous
 * HUD upside down under the new one. While this check is armed the overlay shows
 * a private copy of the last real world and never flips the composite. Eight
 * replaced frames in a row with no screen open retire the check: steady play
 * flips the live image directly and does not read it back. Opening a screen arms
 * the check again, and that one frame stays on the private copy.
 *
 * <p>No GL. The caller reads pixels only when a decision asks for the stamp.
 */
public final class CompositeHold {
    /** Replaced frames required before the stamp read stands down. */
    public static final int REAL_FRAMES_TO_DISARM = 8;

    public enum Source {
        /** The shared Remix image. {@code flipInterop} says whether to flip it. */
        INTEROP,
        /** The private upright copy of the last real world. Never flipped again. */
        LAST_WORLD
    }

    /**
     * @param source where the overlay copies the world from
     * @param flipInterop Y-flip when {@code source} is {@link Source#INTEROP}
     * @param updateLastWorld GPU-blit the interop image into the private copy, flipped once
     * @param sampleEffects lens and weather may sample the interop image this frame
     * @param stampOnPresent read the composite stamp after this frame is copied back
     */
    public record Decision(
            Source source,
            boolean flipInterop,
            boolean updateLastWorld,
            boolean sampleEffects,
            boolean stampOnPresent) {
    }

    private static final Decision STRAIGHT_INTEROP =
            new Decision(Source.INTEROP, false, false, false, false);
    private static final Decision HELD_WORLD =
            new Decision(Source.LAST_WORLD, false, false, false, true);
    private static final Decision STRAIGHT_COMPOSITE =
            new Decision(Source.INTEROP, false, false, false, true);

    private boolean armed = true;
    private int newFrameStreak;
    private boolean screenWasOpen;
    private boolean haveLastWorld;

    public boolean isArmed() {
        return armed;
    }

    /** The timeline value did not move. The pixels are not read. */
    public Decision unchangedSemaphore(boolean screenOpen) {
        if (rearmBecauseScreenOpened(screenOpen)) {
            return finish(screenOpen, heldOrStraight());
        }
        if (armed) {
            newFrameStreak = 0;
            return finish(screenOpen, heldOrStraight());
        }
        return finish(screenOpen, STRAIGHT_INTEROP);
    }

    /**
     * The timeline moved while the check is disarmed. The pixels are not read:
     * steady play treats that signal as a new world, and a screen that just
     * opened stays on the private copy instead.
     */
    public Decision advancedWhileDisarmed(boolean screenOpen) {
        if (armed) {
            throw new IllegalStateException("overlay hold is armed");
        }
        if (rearmBecauseScreenOpened(screenOpen)) {
            return finish(screenOpen, heldOrStraight());
        }
        haveLastWorld = true;
        return finish(screenOpen, new Decision(Source.INTEROP, true, true, true, false));
    }

    /**
     * The timeline moved while the check is armed.
     * {@code imageMatchesComposite} is the stamp the caller just read.
     */
    public Decision advancedWhileArmed(boolean screenOpen, boolean imageMatchesComposite) {
        if (!armed) {
            throw new IllegalStateException("overlay hold is disarmed");
        }
        if (imageMatchesComposite) {
            newFrameStreak = 0;
            return finish(screenOpen, heldOrStraight());
        }
        haveLastWorld = true;
        newFrameStreak++;
        if (newFrameStreak >= REAL_FRAMES_TO_DISARM && !screenOpen) {
            armed = false;
            newFrameStreak = 0;
            return finish(screenOpen, new Decision(Source.INTEROP, true, true, true, false));
        }
        if (newFrameStreak > REAL_FRAMES_TO_DISARM) {
            newFrameStreak = REAL_FRAMES_TO_DISARM;
        }
        return finish(screenOpen, new Decision(Source.LAST_WORLD, false, true, true, true));
    }

    private boolean rearmBecauseScreenOpened(boolean screenOpen) {
        boolean opened = screenOpen && !screenWasOpen && !armed;
        if (opened) {
            armed = true;
            newFrameStreak = 0;
        }
        return opened;
    }

    /** Upright private copy when one exists, otherwise the composite copied straight. */
    private Decision heldOrStraight() {
        return haveLastWorld ? HELD_WORLD : STRAIGHT_COMPOSITE;
    }

    private Decision finish(boolean screenOpen, Decision decision) {
        screenWasOpen = screenOpen;
        return decision;
    }
}
