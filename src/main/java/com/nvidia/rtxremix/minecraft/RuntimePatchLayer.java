package com.nvidia.rtxremix.minecraft;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The mod's Vulkan layer for the runtime ({@code runtime-patch/layer}): armed before the runtime
 * loads, so the Vulkan loader loads it into the runtime's instance and it sees every shader module
 * the runtime creates. Render thread, once per process. Never throws; a failure leaves the runtime
 * as shipped and says why in the log. {@code -Drtxremix.runtimePatches=false} skips it.
 */
final class RuntimePatchLayer {

    private static final Logger LOGGER = LoggerFactory.getLogger(RuntimePatchLayer.class);
    static final String LIBRARY = "rtxremix_patch_layer.dll";

    private static MethodHandle status;

    private RuntimePatchLayer() {
    }

    /** Arms the layer in {@code sdkDir}; call before {@code RemixAPI.load}. */
    static void arm(Path sdkDir) {
        if (!Boolean.parseBoolean(System.getProperty("rtxremix.runtimePatches", "true"))) {
            LOGGER.info("Runtime patch layer off (rtxremix.runtimePatches=false)");
            return;
        }
        Path library = sdkDir.resolve(LIBRARY);
        if (!Files.isRegularFile(library)) {
            LOGGER.info("Runtime patch layer not installed ({} missing)", library);
            return;
        }
        try {
            Linker linker = Linker.nativeLinker();
            SymbolLookup layer = SymbolLookup.libraryLookup(library, Arena.global());
            setDumpEnvironment(linker);
            MethodHandle arm = linker.downcallHandle(layer.find("rrp_arm").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            status = linker.downcallHandle(layer.find("rrp_status").orElseThrow(),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment report = arena.allocate(512);
                int result = (int) arm.invokeExact(report, 512);
                String text = report.getString(0, StandardCharsets.UTF_8);
                if (result < 0) {
                    LOGGER.warn("Runtime patch layer not armed: {}", text);
                    status = null;
                } else {
                    LOGGER.info("Runtime patch layer: {}", text);
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("Runtime patch layer not armed: {}", t.toString());
            status = null;
        }
    }

    /** Logs what the layer saw; call after the runtime's startup created its instance. */
    static void logStatus(String when) {
        if (status == null) {
            return;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ValueLayout.JAVA_INT, 5);
            status.invokeExact(out);
            int hooked = out.getAtIndex(ValueLayout.JAVA_INT, 1);
            String line = "instanceHooked=" + hooked + " devices=" + out.getAtIndex(ValueLayout.JAVA_INT, 2)
                    + " modulesSeen=" + out.getAtIndex(ValueLayout.JAVA_INT, 3)
                    + " modulesDumped=" + out.getAtIndex(ValueLayout.JAVA_INT, 4);
            if (hooked == 0) {
                LOGGER.warn("Runtime patch layer armed but the Vulkan loader did not load it ({}): {}", when, line);
            } else {
                LOGGER.info("Runtime patch layer ({}): {}", when, line);
            }
        } catch (Throwable t) {
            LOGGER.warn("Runtime patch layer status unavailable: {}", t.toString());
        }
    }

    // shortcut: diagnostic dump switch for the first spike; remove once modules are keyed.
    private static void setDumpEnvironment(Linker linker) throws Throwable {
        String dir = System.getProperty("rtxremix.runtimePatch.dump");
        String sizes = System.getProperty("rtxremix.runtimePatch.dumpSizes");
        if (dir == null || sizes == null) {
            return;
        }
        MethodHandle set = linker.downcallHandle(
                SymbolLookup.libraryLookup("kernel32", Arena.global()).find("SetEnvironmentVariableW").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        try (Arena arena = Arena.ofConfined()) {
            int a = (int) set.invokeExact(arena.allocateFrom("RTXREMIX_PATCH_DUMP", StandardCharsets.UTF_16LE),
                    arena.allocateFrom(dir, StandardCharsets.UTF_16LE));
            int b = (int) set.invokeExact(arena.allocateFrom("RTXREMIX_PATCH_DUMP_SIZES", StandardCharsets.UTF_16LE),
                    arena.allocateFrom(sizes, StandardCharsets.UTF_16LE));
        }
    }
}
