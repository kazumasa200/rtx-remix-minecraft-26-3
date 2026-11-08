/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.sky;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeFrameTest {

    /** The layout the patch build read from the compiled clouds library. */
    private static final Path LAYOUT = Path.of("runtime-patch", "shaders", "compiled", "frame_layout.txt");

    @Test
    void everyMemberIsWhereTheCompiledLibraryReadsIt() throws IOException, ReflectiveOperationException {
        List<String> lines = Files.readAllLines(LAYOUT);
        assertTrue(lines.size() > 10, "frame_layout.txt is missing its members");
        int last = 0;
        for (String line : lines) {
            String[] parts = line.trim().split(" ");
            String member = parts[0];
            int offset = Integer.parseInt(parts[1]);
            last = Math.max(last, offset);
            if (member.startsWith("Reserved")) {
                continue;
            }
            String constant = member.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase();
            Field field = RuntimeFrame.class.getDeclaredField(constant);
            assertEquals(offset, field.getInt(null), member);
        }
        // The shape arrays end the block: one vec4 per shape.
        assertEquals(RuntimeFrame.SHAPE_SIZE, last);
        assertEquals(RuntimeFrame.SHAPE_SIZE + CloudShapes.MAX_SHAPES * RuntimeFrame.SHAPE_STRIDE, RuntimeFrame.BYTES);
    }
}
