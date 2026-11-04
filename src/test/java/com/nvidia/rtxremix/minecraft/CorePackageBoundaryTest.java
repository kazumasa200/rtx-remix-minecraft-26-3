/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The packages a module shared by several game versions and loaders would hold. Nothing in
 * them names Minecraft, Mojang or Fabric, so they can move into that module unchanged; code
 * that needs the game belongs in the package that captures from it.
 */
class CorePackageBoundaryTest {
    private static final Path SOURCE_ROOT = Path.of("src", "main", "java", "com", "nvidia", "rtxremix", "minecraft");
    private static final List<String> CORE_PACKAGES = List.of("asset", "profiling", "util");
    private static final Pattern GAME_REFERENCE = Pattern.compile("\\b(?:net\\.minecraft|net\\.fabricmc|com\\.mojang)\\.");

    @Test
    void corePackagesNameNeitherTheGameNorTheLoader() throws Exception {
        Set<String> offenders = new TreeSet<>();
        for (String name : CORE_PACKAGES) {
            Path dir = SOURCE_ROOT.resolve(name);
            assertTrue(Files.isDirectory(dir), "missing core package: " + dir);
            try (Stream<Path> sources = Files.walk(dir)) {
                var iterator = sources.filter(path -> path.toString().endsWith(".java")).iterator();
                while (iterator.hasNext()) {
                    Path source = iterator.next();
                    if (GAME_REFERENCE.matcher(Files.readString(source)).find()) {
                        offenders.add(SOURCE_ROOT.relativize(source).toString());
                    }
                }
            }
        }

        assertEquals(Set.of(), offenders,
                "these packages must stay free of Minecraft, Mojang and Fabric so a shared core module can hold them");
    }
}
