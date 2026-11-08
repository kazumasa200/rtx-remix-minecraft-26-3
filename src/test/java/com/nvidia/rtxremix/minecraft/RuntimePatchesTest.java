package com.nvidia.rtxremix.minecraft;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimePatchesTest {

    @TempDir
    Path dir;

    /** A file of {@code size} bytes with the guard's four words at each offset given. */
    private Path runtime(int size, int operand, int... offsets) throws IOException {
        ByteBuffer bytes = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        for (int at : offsets) {
            bytes.putInt(at, 4 << 16 | 168).putInt(at + 4, 7).putInt(at + 8, RuntimePatches.FADE_GUARD).putInt(at + 12, operand);
        }
        Path dll = dir.resolve("d3d9.dll");
        Files.write(dll, bytes.array());
        return dll;
    }

    private static int wordAt(Path dll, int at) throws IOException {
        return ByteBuffer.wrap(Files.readAllBytes(dll)).order(ByteOrder.LITTLE_ENDIAN).getInt(at);
    }

    @Test
    void patchesTheGuardOnceAndLeavesItThen() throws IOException {
        // Across the scan's first chunk boundary, which is 16 MiB in.
        int at = (16 << 20) - 8;
        Path dll = runtime((17 << 20), RuntimePatches.PRIMARY_MISS, at);
        assertEquals(RuntimePatches.Outcome.PATCHED, RuntimePatches.patchSkyFade(dll));
        assertEquals(RuntimePatches.VIEW_Z_IS_MISS, wordAt(dll, at + 12));
        assertEquals(RuntimePatches.Outcome.ALREADY_PATCHED, RuntimePatches.patchSkyFade(dll));
        assertEquals(RuntimePatches.VIEW_Z_IS_MISS, wordAt(dll, at + 12));
    }

    @Test
    void leavesAnUnknownRuntimeAlone() throws IOException {
        Path none = runtime(4096, 1234, 64);
        assertEquals(RuntimePatches.Outcome.UNRECOGNISED, RuntimePatches.patchSkyFade(none));
        assertEquals(1234, wordAt(none, 64 + 12));

        Path two = runtime(4096, RuntimePatches.PRIMARY_MISS, 64, 1024);
        assertEquals(RuntimePatches.Outcome.UNRECOGNISED, RuntimePatches.patchSkyFade(two));
        assertEquals(RuntimePatches.PRIMARY_MISS, wordAt(two, 64 + 12));
        assertEquals(RuntimePatches.PRIMARY_MISS, wordAt(two, 1024 + 12));
    }
}
