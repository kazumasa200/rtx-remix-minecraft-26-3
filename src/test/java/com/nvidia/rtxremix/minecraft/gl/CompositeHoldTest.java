/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.gl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompositeHoldTest {
    @Test
    void anOpenScreenKeepsTheStampCheckArmed() {
        CompositeHold hold = new CompositeHold();

        for (int frame = 0; frame < CompositeHold.REAL_FRAMES_TO_DISARM; frame++) {
            CompositeHold.Decision decision = hold.advancedWhileArmed(true, false);
            assertTrue(hold.isArmed(), "frame " + frame);
            assertEquals(CompositeHold.Source.LAST_WORLD, decision.source());
            assertTrue(decision.updateLastWorld());
            assertTrue(decision.stampOnPresent());
            assertFalse(decision.flipInterop());
        }
    }

    @Test
    void eightReplacedFramesWithNoScreenDisarmTheCheck() {
        CompositeHold hold = new CompositeHold();

        for (int frame = 0; frame < CompositeHold.REAL_FRAMES_TO_DISARM - 1; frame++) {
            hold.advancedWhileArmed(false, false);
            assertTrue(hold.isArmed(), "frame " + frame);
        }
        CompositeHold.Decision disarmed = hold.advancedWhileArmed(false, false);

        assertFalse(hold.isArmed());
        assertEquals(CompositeHold.Source.INTEROP, disarmed.source());
        assertTrue(disarmed.flipInterop());
        assertTrue(disarmed.updateLastWorld());
        assertTrue(disarmed.sampleEffects());
        assertFalse(disarmed.stampOnPresent());
    }

    @Test
    void anUnchangedImageResetsTheStreak() {
        CompositeHold hold = new CompositeHold();
        feedReplacedFrames(hold, CompositeHold.REAL_FRAMES_TO_DISARM - 1);
        assertTrue(hold.isArmed());

        CompositeHold.Decision unchanged = hold.unchangedSemaphore(false);
        assertTrue(hold.isArmed());
        assertEquals(CompositeHold.Source.LAST_WORLD, unchanged.source());
        assertFalse(unchanged.updateLastWorld());
        assertFalse(unchanged.sampleEffects());

        CompositeHold.Decision stillComposite = hold.advancedWhileArmed(false, true);
        assertTrue(hold.isArmed());
        assertFalse(stillComposite.updateLastWorld());
        assertFalse(stillComposite.sampleEffects());

        feedReplacedFrames(hold, CompositeHold.REAL_FRAMES_TO_DISARM - 1);
        assertTrue(hold.isArmed());
        assertFalse(hold.advancedWhileArmed(false, false).stampOnPresent());
        assertFalse(hold.isArmed());
    }

    @Test
    void aScreenArmsTheCheckAndThatFrameStaysOnTheHeldWorld() {
        CompositeHold hold = disarm(new CompositeHold());

        CompositeHold.Decision opened = hold.advancedWhileDisarmed(true);

        assertTrue(hold.isArmed());
        assertEquals(CompositeHold.Source.LAST_WORLD, opened.source());
        assertFalse(opened.flipInterop());
        assertFalse(opened.updateLastWorld());
        assertFalse(opened.sampleEffects());
        assertTrue(opened.stampOnPresent());
    }

    @Test
    void anUnchangedSemaphoreAlsoRearmsOnAScreen() {
        CompositeHold hold = disarm(new CompositeHold());

        CompositeHold.Decision opened = hold.unchangedSemaphore(true);

        assertTrue(hold.isArmed());
        assertEquals(CompositeHold.Source.LAST_WORLD, opened.source());
        assertFalse(opened.updateLastWorld());
        assertFalse(opened.sampleEffects());
        assertTrue(opened.stampOnPresent());
    }

    @Test
    void steadyPlayAfterDisarmFlipsTheLiveImageWithoutReading() {
        CompositeHold hold = disarm(new CompositeHold());

        CompositeHold.Decision frame = hold.advancedWhileDisarmed(false);

        assertFalse(hold.isArmed());
        assertEquals(CompositeHold.Source.INTEROP, frame.source());
        assertTrue(frame.flipInterop());
        assertTrue(frame.updateLastWorld());
        assertFalse(frame.stampOnPresent());
    }

    @Test
    void aScreenThatIsAlreadyOpenDoesNotHideAReplacedWorld() {
        CompositeHold hold = new CompositeHold();
        hold.advancedWhileArmed(true, false);

        CompositeHold.Decision next = hold.advancedWhileArmed(true, false);

        assertTrue(hold.isArmed());
        assertTrue(next.updateLastWorld());
        assertTrue(next.sampleEffects());
    }

    @Test
    void theFirstCompositeBeforeAnyWorldIsCopiedStraight() {
        CompositeHold hold = new CompositeHold();

        CompositeHold.Decision decision = hold.advancedWhileArmed(true, true);

        assertTrue(hold.isArmed());
        assertEquals(CompositeHold.Source.INTEROP, decision.source());
        assertFalse(decision.flipInterop());
        assertFalse(decision.updateLastWorld());
        assertTrue(decision.stampOnPresent());
    }

    private static void feedReplacedFrames(CompositeHold hold, int frames) {
        for (int frame = 0; frame < frames; frame++) {
            hold.advancedWhileArmed(false, false);
        }
    }

    private static CompositeHold disarm(CompositeHold hold) {
        feedReplacedFrames(hold, CompositeHold.REAL_FRAMES_TO_DISARM);
        assertFalse(hold.isArmed());
        return hold;
    }
}
