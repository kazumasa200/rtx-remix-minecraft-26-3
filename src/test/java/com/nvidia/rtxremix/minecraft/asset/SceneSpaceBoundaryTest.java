/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.asset;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every position handed to Remix goes through {@link SceneSpace}. A world position cast
 * straight to float into a Remix transform or light was the mistake at every site #22 fixed,
 * and it shows only far from spawn, so the source is checked for that shape.
 */
class SceneSpaceBoundaryTest {
    private static final Path SOURCE_ROOT = Path.of("src", "main", "java");
    /** The lens works in world space and never hands positions to Remix. */
    private static final String WORLD_SPACE_PACKAGE = "/lens/";
    private static final List<Pattern> RAW_CASTS = List.of(
            Pattern.compile("setTranslation\\(\\s*\\(float\\)"),
            Pattern.compile("\\[(3|7|11)\\]\\s*=\\s*\\(float\\)"),
            Pattern.compile("\\.set\\(\\s*\\d,\\s*3,\\s*\\(float\\)"),
            Pattern.compile("\\.position\\(\\s*\\(float\\)"),
            Pattern.compile("fromTRS\\(\\s*\\(float\\)"));

    @Test
    void positionsHandedToRemixGoThroughSceneSpace() throws IOException {
        TreeSet<String> offenders = new TreeSet<>();
        try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String path = file.toString().replace('\\', '/');
                if (path.contains(WORLD_SPACE_PACKAGE)) continue;
                String source = Files.readString(file);
                for (Pattern pattern : RAW_CASTS) {
                    Matcher matcher = pattern.matcher(source);
                    while (matcher.find()) {
                        int line = 1 + (int) source.substring(0, matcher.start()).chars().filter(c -> c == '\n').count();
                        offenders.add(path + ":" + line);
                    }
                }
            }
        }
        assertEquals(new TreeSet<String>(), offenders,
                "convert positions handed to Remix through SceneSpace, e.g. SceneSpace.x(worldX), "
                        + "or place a retained instance with setWorldTranslation; see AGENTS.md");
    }
}
