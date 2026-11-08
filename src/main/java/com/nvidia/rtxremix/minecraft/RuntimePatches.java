package com.nvidia.rtxremix.minecraft;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Patches the RTX Remix runtime file before it loads, for runtime faults the fork cannot fix in
 * the runtime's source. The runtime is MIT-licensed; the patch rewrites one word of one embedded
 * SPIR-V shader, so its size and every other byte stay as shipped.
 *
 * <p>The composite's fade to sky ({@code rtx.viewDistance.distanceMode = 3}) runs for every pixel
 * that is not a primary miss. A primary ray that passed through glass or water into the sky is not
 * a miss, but it stores the miss view Z, far past the fade's end, so the fade replaced the tinted
 * sky with untinted sky. The guard's operand, {@code primaryMiss}, is pointed at the comparison of
 * the view Z with the miss view Z that {@code primaryMiss} is computed from.
 */
final class RuntimePatches {

    enum Outcome { PATCHED, ALREADY_PATCHED, UNRECOGNISED }

    /** {@code OpLogicalNot}: four words, opcode 168. */
    private static final int OP_LOGICAL_NOT = 4 << 16 | 168;
    // Result ids of the composite module in the minecraft-rtx2 71a581da and a00da12a runtimes,
    // whose embedded shaders are identical. Another build keeps the runtime as it is.
    static final int FADE_GUARD = 18556;
    static final int PRIMARY_MISS = 1039;
    static final int VIEW_Z_IS_MISS = 848;
    /** Where the guard's operand lies in the a00da12a runtime, read first to skip the scan. */
    private static final long KNOWN_OPERAND_OFFSET = 0x12452b54L;

    private static final int CHUNK = 16 << 20;

    private RuntimePatches() {
    }

    /** Points the sky fade's guard at the view-Z test, once; the file is left alone otherwise. */
    static Outcome patchSkyFade(Path dll) throws IOException {
        try (FileChannel channel = FileChannel.open(dll, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            long operand = guardAt(channel, KNOWN_OPERAND_OFFSET - 12);
            long[] found = operand >= 0 ? null : scan(channel);
            if (found != null) {
                if (found[0] + found[1] != 1) {
                    return Outcome.UNRECOGNISED;
                }
                operand = found[0] == 1 ? found[2] : found[3];
            }
            ByteBuffer word = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
            channel.read(word, operand);
            if (word.getInt(0) == VIEW_Z_IS_MISS) {
                return Outcome.ALREADY_PATCHED;
            }
            word.clear();
            word.putInt(VIEW_Z_IS_MISS).flip();
            channel.write(word, operand);
            channel.force(false);
            return Outcome.PATCHED;
        }
    }

    /** The operand's offset if the guard, patched or not, starts at {@code at}, else -1. */
    private static long guardAt(FileChannel channel, long at) throws IOException {
        if (at < 0 || at + 16 > channel.size()) {
            return -1;
        }
        ByteBuffer words = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        channel.read(words, at);
        return isGuard(words, 0) ? at + 12 : -1;
    }

    private static boolean isGuard(ByteBuffer words, int at) {
        int operand = words.getInt(at + 12);
        return words.getInt(at) == OP_LOGICAL_NOT && words.getInt(at + 8) == FADE_GUARD
                && (operand == PRIMARY_MISS || operand == VIEW_Z_IS_MISS);
    }

    /** Counts of unpatched and patched guards, and the operand offset of the last of each. */
    private static long[] scan(FileChannel channel) throws IOException {
        long[] found = {0, 0, -1, -1};
        ByteBuffer buffer = ByteBuffer.allocate(CHUNK + 16).order(ByteOrder.LITTLE_ENDIAN);
        long size = channel.size();
        // Chunks overlap by one instruction so a guard across a boundary is still seen whole.
        for (long start = 0; start < size; start += CHUNK) {
            buffer.clear();
            while (buffer.hasRemaining() && channel.read(buffer, start + buffer.position()) > 0) {
                // read until the chunk and its overlap are in, or the file ends
            }
            int limit = buffer.position() - 16;
            for (int i = 0; i <= limit && i < CHUNK; i += 4) {
                if (isGuard(buffer, i)) {
                    boolean patched = buffer.getInt(i + 12) == VIEW_Z_IS_MISS;
                    found[patched ? 1 : 0]++;
                    found[patched ? 3 : 2] = start + i + 12;
                }
            }
        }
        return found;
    }
}
