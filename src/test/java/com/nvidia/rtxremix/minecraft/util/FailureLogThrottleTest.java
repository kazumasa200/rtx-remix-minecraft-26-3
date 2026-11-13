/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.util;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.helpers.MessageFormatter;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * Pins the throttle's contract: the first failure of an episode is logged, later ones
 * are counted, and the count is reported once {@link FailureLogThrottle#REARM_FRAMES}
 * frames end without a failure.
 */
class FailureLogThrottleTest {

    private static final Exception FAILURE = new IllegalStateException("native call failed");

    private final List<String> errors = new ArrayList<>();
    private final FailureLogThrottle throttle =
            new FailureLogThrottle(recordingLogger(), "Test subject");

    @Test
    void firstFailureOfAnEpisodeIsLoggedWithItsPlaceAndTheRestAreNot() {
        throttle.noteFailure("capturing", "test subject", "A", FAILURE);
        throttle.noteFailure("capturing", "test subject", "B", FAILURE);
        throttle.endFrame();
        throttle.noteFailure("submitting", "test subject", "C", FAILURE);

        assertEquals(1, errors.size());
        assertTrue(errors.get(0).startsWith("Error capturing test subject at A"), errors.get(0));
    }

    @Test
    void episodeEndsAfterRearmFramesWithoutAFailureAndReportsWhatWentUnlogged() {
        throttle.noteFailure("capturing", "test subject", "A", FAILURE);
        throttle.noteFailure("capturing", "test subject", "A", FAILURE);
        throttle.noteFailure("capturing", "test subject", "A", FAILURE);
        endFrames(FailureLogThrottle.REARM_FRAMES - 1);
        assertEquals(1, errors.size());

        throttle.endFrame();
        assertEquals(2, errors.size());
        assertEquals("Test subject failures have stopped; 2 went unlogged", errors.get(1));

        throttle.noteFailure("submitting", "test subject", "B", FAILURE);
        assertEquals(3, errors.size());
        assertTrue(errors.get(2).startsWith("Error submitting test subject at B"), errors.get(2));
    }

    @Test
    void aFailureInsideTheWindowRestartsIt() {
        throttle.noteFailure("capturing", "test subject", "A", FAILURE);
        endFrames(FailureLogThrottle.REARM_FRAMES - 1);
        throttle.noteFailure("capturing", "test subject", "A", FAILURE);
        endFrames(FailureLogThrottle.REARM_FRAMES - 1);
        assertEquals(1, errors.size());

        throttle.endFrame();
        assertEquals(2, errors.size());
        assertEquals("Test subject failures have stopped; 1 went unlogged", errors.get(1));
    }

    @Test
    void anEpisodeOfOneFailureEndsWithoutASummary() {
        throttle.noteFailure("capturing", "test subject", "A", FAILURE);
        endFrames(FailureLogThrottle.REARM_FRAMES);
        throttle.noteFailure("capturing", "test subject", "A", FAILURE);

        assertEquals(2, errors.size());
        assertTrue(errors.get(1).startsWith("Error capturing test subject at A"), errors.get(1));
    }

    @Test
    void framesWithoutAnyFailureLogNothing() {
        endFrames(FailureLogThrottle.REARM_FRAMES * 2);

        assertEquals(0, errors.size());
    }

    private void endFrames(int count) {
        for (int i = 0; i < count; i++) {
            throttle.endFrame();
        }
    }

    /** A logger that records each error line's formatted message and swallows the rest. */
    private Logger recordingLogger() {
        return (Logger) Proxy.newProxyInstance(Logger.class.getClassLoader(),
                new Class<?>[] {Logger.class}, (proxy, method, args) -> {
                    if (method.getName().equals("error") && args[0] instanceof String format) {
                        Object[] formatArgs = args.length == 2 && args[1] instanceof Object[] varargs
                                ? varargs
                                : Arrays.copyOfRange(args, 1, args.length);
                        errors.add(MessageFormatter.arrayFormat(format, formatArgs).getMessage());
                    }
                    return method.getReturnType() == boolean.class ? Boolean.FALSE : null;
                });
    }
}
