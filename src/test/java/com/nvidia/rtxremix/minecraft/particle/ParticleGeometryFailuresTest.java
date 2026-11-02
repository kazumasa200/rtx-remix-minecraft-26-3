/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.particle;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import static org.junit.jupiter.api.Assertions.*;

class ParticleGeometryFailuresTest {
    @Test void failuresAreQuarantinedAndLoggedOncePerProducerAcrossFrames() {
        var logs = new AtomicInteger();
        Logger logger = (Logger) Proxy.newProxyInstance(Logger.class.getClassLoader(), new Class<?>[]{Logger.class},
                (proxy, method, args) -> { if (method.getName().equals("error")) logs.incrementAndGet(); return null; });
        var failures = new ParticleGeometryFailures(logger);
        failures.fail(String.class, "capture", new IllegalStateException("bad stream"));
        failures.fail(String.class, "capture", new IllegalStateException("repeated"));
        assertTrue(failures.disabled(String.class));
        assertFalse(failures.disabled(Integer.class));
        assertEquals(1, logs.get());
        failures.beginFrame();
        failures.captured(Integer.class);
        failures.captured(Integer.class);
        failures.submissionFailed(new IllegalStateException("bad count"));
        assertTrue(failures.disabled(Integer.class));
        assertEquals(2, logs.get());
        failures.beginFrame();
        failures.submissionFailed(new IllegalStateException("no producers"));
        assertEquals(2, logs.get());
    }
}
