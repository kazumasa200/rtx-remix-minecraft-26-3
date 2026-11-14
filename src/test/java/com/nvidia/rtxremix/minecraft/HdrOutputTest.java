/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HdrOutputTest {

    @Test
    void theFrameModeIsHdrOnlyWhileTheScreenIsScRgb() {
        assertEquals(HdrOutput.MODE_OFF, HdrOutput.mode(false, false));
        assertEquals(HdrOutput.MODE_OFF, HdrOutput.mode(false, true));
        assertEquals(HdrOutput.MODE_HDR, HdrOutput.mode(true, false));
        assertEquals(HdrOutput.MODE_SDR_REFERENCE, HdrOutput.mode(true, true));
    }

    @Test
    void aLaunchThatDidNotSurviveHoldsHdrOffUntilTheSwitchIsTurnedOnAgain(@TempDir Path config) throws Exception {
        assertFalse(HdrOutput.startup(config, false));

        assertTrue(HdrOutput.startup(config, true));
        HdrOutput.attempted();                      // the launch dies before SURVIVED_FRAMES
        assertFalse(HdrOutput.startup(config, true));
        assertTrue(Files.exists(config.resolve(HdrOutput.FAILED)));
        assertFalse(HdrOutput.startup(config, true));

        HdrOutput.setRequested(false);
        HdrOutput.setRequested(true);               // turned on again from the settings
        assertTrue(HdrOutput.startup(config, true));
        HdrOutput.attempted();
        HdrOutput.presented(HdrOutput.SURVIVED_FRAMES);
        assertFalse(Files.exists(config.resolve(HdrOutput.ATTEMPT)));
        assertTrue(HdrOutput.startup(config, true));
    }

    @Test
    void theScreenIsHdrOnlyWithAnScRgbSwapchainAWidenedImageAndThePatchGroup() {
        HdrOutput.refresh(HdrOutput.STATE_ACTIVE, true, true);
        assertTrue(HdrOutput.active());
        HdrOutput.refresh(HdrOutput.STATE_ACTIVE, true, false);
        assertFalse(HdrOutput.active());
        HdrOutput.refresh(HdrOutput.STATE_ACTIVE, false, true);
        assertFalse(HdrOutput.active());
        HdrOutput.refresh(3, true, true);
        assertFalse(HdrOutput.active());
        assertTrue(HdrOutput.interopWidened());
        HdrOutput.refresh(0, false, false);
    }
}
