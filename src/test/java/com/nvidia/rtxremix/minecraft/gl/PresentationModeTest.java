/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.gl;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PresentationModeTest {
    @Test
    void aRepeatedSemaphoreIsThePreviousGlComposite() {
        assertFalse(GlInterop.hasNewVulkanImage(4L, 4L));
    }

    @Test
    void aMovedSemaphoreIsANewVulkanImage() {
        assertTrue(GlInterop.hasNewVulkanImage(5L, 4L));
        assertTrue(GlInterop.hasNewVulkanImage(0L, -1L));
    }

    @Test
    void aSignaledImageThatStillMatchesTheCompositeStaysUnflipped() {
        assertFalse(GlInterop.overlayNeedsYFlip(true, true));
        assertFalse(GlInterop.overlayNeedsYFlip(false, true));
        assertFalse(GlInterop.overlayNeedsYFlip(false, false));
    }

    @Test
    void aSignaledImageWithReplacedPixelsIsFlipped() {
        assertTrue(GlInterop.overlayNeedsYFlip(true, false));
    }

    @Test
    void aCompositeStampMatchesOnlyTheSameBytes() {
        byte[] saved = {1, 2, 3, 4};
        assertTrue(GlInterop.stampMatches(saved, new byte[] {1, 2, 3, 4}));
        assertFalse(GlInterop.stampMatches(saved, new byte[] {1, 2, 3, 5}));
        assertFalse(GlInterop.stampMatches(saved, new byte[] {1, 2, 3}));
        assertFalse(GlInterop.stampMatches(saved, null));
        assertFalse(GlInterop.stampMatches(null, saved));
        assertFalse(GlInterop.stampMatches(new byte[0], new byte[0]));
    }

    @Test
    void presentationSelectionIsExplicit() {
        assertEquals(PresentationMode.CHILD, PresentationMode.parse("child"));
        assertEquals(PresentationMode.PARENT, PresentationMode.parse("parent"));
    }

    @Test
    void invalidValuesUseTheDefaultWithoutPreventingStartup() {
        for (String value : new String[] {"auto", "", "PARENT", " parent "}) {
            assertEquals(PresentationMode.CHILD, PresentationMode.parse(value), value);
        }
    }

    @Test
    void configuredSelectionDefaultsAndPreservesAnExplicitParent() {
        String previous = System.getProperty("rtxremix.presentation");
        try {
            System.clearProperty("rtxremix.presentation");
            assertEquals(PresentationMode.CHILD, PresentationMode.configured());
            System.setProperty("rtxremix.presentation", "parent");
            assertEquals(PresentationMode.PARENT, PresentationMode.configured());
            System.setProperty("rtxremix.presentation", "typo");
            assertEquals(PresentationMode.CHILD, PresentationMode.configured());
        } finally {
            if (previous == null) System.clearProperty("rtxremix.presentation");
            else System.setProperty("rtxremix.presentation", previous);
        }
    }
}
