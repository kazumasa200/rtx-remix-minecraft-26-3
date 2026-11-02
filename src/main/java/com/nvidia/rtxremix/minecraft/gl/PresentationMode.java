/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.gl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Selected once at startup; changing the native surface requires a restart. */
public enum PresentationMode {
    CHILD, PARENT;

    private static final Logger LOGGER = LoggerFactory.getLogger(PresentationMode.class);

    public static PresentationMode configured() {
        return parse(System.getProperty("rtxremix.presentation", "child"));
    }

    static PresentationMode parse(String value) {
        return switch (value) {
            case "child" -> CHILD;
            case "parent" -> PARENT;
            default -> {
                LOGGER.warn("Invalid rtxremix.presentation value '{}'; expected child or parent. Using child.", value);
                yield CHILD;
            }
        };
    }
}
