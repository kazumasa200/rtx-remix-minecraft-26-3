/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.util;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CleanupStepsTest {
    @Test
    void attemptsLaterResourcesAndPreservesTheOriginalFailure() {
        CleanupSteps cleanup = new CleanupSteps();
        List<Integer> released = new ArrayList<>();
        IllegalStateException first = new IllegalStateException("window hook");
        AssertionError second = new AssertionError("another resource");
        cleanup.run(() -> { released.add(1); throw first; });
        cleanup.run(() -> released.add(2));
        cleanup.run(() -> { released.add(3); throw second; });
        cleanup.run(() -> released.add(4));
        assertEquals(List.of(1, 2, 3, 4), released);
        assertSame(first, assertThrows(IllegalStateException.class, cleanup::finish));
        assertArrayEquals(new Throwable[] {second}, first.getSuppressed());
    }

    @Test
    void anErrorRemainsAnErrorAndSuccessfulCleanupDoesNotThrow() {
        CleanupSteps success = new CleanupSteps();
        success.run(() -> {});
        assertDoesNotThrow(success::finish);
        CleanupSteps failed = new CleanupSteps();
        Error error = new LinkageError("native resource");
        failed.run(() -> { throw error; });
        failed.run(() -> { throw error; });
        assertSame(error, assertThrows(LinkageError.class, failed::finish));
    }
}
