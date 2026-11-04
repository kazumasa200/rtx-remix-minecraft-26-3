/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.particle;

import java.util.ArrayList;
import java.util.HashSet;
import org.slf4j.Logger;

/** Render-thread quarantine: a broken optional producer logs once and cannot break other rendering. */
public final class ParticleGeometryFailures {
    private final Logger logger;
    private final HashSet<Class<?>> disabled = new HashSet<>();
    private final ArrayList<Class<?>> captured = new ArrayList<>();

    public ParticleGeometryFailures(Logger logger) { this.logger = logger; }
    public boolean disabled(Class<?> type) { return disabled.contains(type); }
    public void beginFrame() { captured.clear(); }
    public void captured(Class<?> type) { if (!captured.contains(type)) captured.add(type); }
    public void fail(Class<?> type, String stage, Throwable failure) {
        if (disabled.add(type)) logger.error("Skipping extracted particle state {} after {} failed", type.getName(), stage, failure);
    }
    public void submissionFailed(Throwable failure) {
        for (Class<?> type : captured) fail(type, "submission", failure);
        captured.clear();
    }
}
