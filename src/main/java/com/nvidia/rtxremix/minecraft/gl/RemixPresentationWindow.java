/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.gl;

import com.nvidia.rtxremix.minecraft.util.CleanupSteps;
import java.util.concurrent.atomic.AtomicReference;
import org.lwjgl.sdl.SDLInit;
import org.lwjgl.sdl.SDL_MainThreadCallback;
import org.lwjgl.sdl.SDLError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.windows.WNDCLASSEX;
import org.lwjgl.system.windows.WinBase;
import org.lwjgl.system.windows.WindowProc;

import static org.lwjgl.system.windows.User32.*;

/**
 * Hosts Vulkan presentation in a child of the OpenGL game window. A native
 * window cannot own both WGL and Vulkan presentation surfaces.
 */
public final class RemixPresentationWindow implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(RemixPresentationWindow.class);
    private final long module = WinBase.GetModuleHandle(null, (String) null);
    private final String className = "RemixPresentation" + Long.toHexString(System.nanoTime());
    private final WindowProc childProcedure = WindowProc.create(this::processChildMessage);
    private final WindowProc parentProcedure = WindowProc.create(this::processParentMessage);
    private volatile long parent;
    private long previousParentProcedure;
    private volatile long window;
    private boolean registered;
    private boolean parentHooked;
    private boolean forwardingInput;
    private boolean childCallbackFreed;
    private boolean parentCallbackFreed;
    private volatile boolean shown;
    private volatile boolean stopped;
    private volatile boolean closed;
    private volatile boolean parentDestroyed;
    private int width;
    private int height;

    /** Creates a hidden, non-OpenGL child with the parent's framebuffer size. */
    public RemixPresentationWindow(long parent, int width, int height) {
        this.parent = parent;
        this.width = width;
        this.height = height;
        try {
            onWindowThread(this::create);
        } catch (RuntimeException | Error failure) {
            if (!closed && !registered && window == 0 && !parentHooked) {
                if (!childCallbackFreed) childProcedure.free();
                if (!parentCallbackFreed) parentProcedure.free();
                childCallbackFreed = true;
                parentCallbackFreed = true;
                closed = true;
            }
            throw failure;
        }
    }

    private void create() {
        try {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                WNDCLASSEX windowClass = WNDCLASSEX.calloc(stack)
                        .cbSize(WNDCLASSEX.SIZEOF)
                        .lpfnWndProc(childProcedure)
                        .hInstance(module)
                        .lpszClassName(stack.UTF16(className));
                if (RegisterClassEx(null, windowClass) == 0) {
                    throw failure("RegisterClassEx");
                }
                registered = true;
            }
            window = CreateWindowEx(null, WS_EX_NOACTIVATE, className, "",
                    WS_CHILD | WS_CLIPSIBLINGS, 0, 0, width, height,
                    parent, 0, module, 0);
            if (window == 0) {
                throw failure("CreateWindowEx");
            }
            previousParentProcedure = GetWindowLongPtr(parent, GWL_WNDPROC);
            if (previousParentProcedure == 0) {
                throw failure("GetWindowLongPtr");
            }
            if (SetWindowLongPtr(null, parent, GWL_WNDPROC, parentProcedure.address()) == 0) {
                throw failure("SetWindowLongPtr");
            }
            parentHooked = true;
        } catch (RuntimeException | Error e) {
            try {
                close();
            } catch (RuntimeException | Error cleanupFailure) {
                if (cleanupFailure != e) e.addSuppressed(cleanupFailure);
            }
            throw e;
        }
    }

    public long handle() {
        return window;
    }

    /** Completes SDL parent destruction on its owner thread before native renderer shutdown. */
    public void destroyParentWindow(Runnable destruction) {
        onWindowThread(() -> {
            destruction.run();
            if (window != 0 || parent != 0 && GetWindowLongPtr(parent, GWL_WNDPROC) != 0) {
                throw new IllegalStateException("SDL parent destruction left a presentation window alive");
            }
            parent = 0;
            // Published after DestroyWindow and all of its callbacks return. A
            // cleared handle inside WM_NCDESTROY alone does not establish that.
            parentDestroyed = true;
        });
    }

    /** Makes the native output visible after its first successful present. */
    public void show() {
        if (!shown && !stopped) {
            onWindowThread(() -> {
                if (window != 0 && !stopped) {
                    ShowWindow(window, SW_SHOWNOACTIVATE);
                    shown = true;
                }
            });
        }
    }

    /** Stops output and input forwarding, retaining the SDK window until teardown. */
    public void stop() {
        if (!closed) {
            if (parent == 0 && window == 0) {
                stopOnWindowThread();
            } else {
                onWindowThread(this::stopOnWindowThread);
            }
        }
    }

    private void stopOnWindowThread() {
        stopped = true;
        if (shown && window != 0) {
            ShowWindow(window, SW_HIDE);
        }
        shown = false;
        // Preserve any later subclass installed by another mod. Our callback
        // stays alive until the parent is destroyed if it remains in that chain.
        if (parentHooked && parent != 0
                && GetWindowLongPtr(parent, GWL_WNDPROC) == parentProcedure.address()) {
            if (SetWindowLongPtr(null, parent, GWL_WNDPROC, previousParentProcedure) == 0) {
                throw failure("SetWindowLongPtr");
            }
            parentHooked = false;
        }
    }

    private long processChildMessage(long hwnd, int message, long wParam, long lParam) {
        // The native runtime has already had the mirrored input. Its fallback
        // must neither bubble to SDL again nor enter a second system-menu loop.
        if (forwardingInput && (isInputMessage(message) || message == WM_SYSCOMMAND)) return 0;
        if (message == WM_NCHITTEST) {
            // SDL retains input and focus. Its native messages are mirrored below
            // so Remix's menu receives the same input as when it owned this HWND.
            return HTTRANSPARENT;
        }
        if (message == WM_NCDESTROY) {
            window = 0;
        }
        return DefWindowProc(hwnd, message, wParam, lParam);
    }

    private long processParentMessage(long hwnd, int message, long wParam, long lParam) {
        // Also covers a runtime subclass calling DefWindowProc directly rather
        // than chaining through our child procedure.
        if (forwardingInput && (isInputMessage(message) || message == WM_SYSCOMMAND)) return 0;
        if (!stopped && window != 0) {
            if (message == WM_SIZE && wParam != SIZE_MINIMIZED) {
                resize((int) (lParam & 0xffff), (int) ((lParam >>> 16) & 0xffff));
            } else if (isInputMessage(message)) {
                forwardingInput = true;
                try {
                    SendMessage(null, window, message, wParam, lParam);
                } finally {
                    forwardingInput = false;
                }
                // Alt+letter belongs to the runtime, not a window-menu mnemonic.
                // SDL still receives the key transitions. Alt+Space and Alt+F4
                // retain the parent's native system-command handling.
                if (message == WM_SYSCHAR && wParam != VK_SPACE || message == WM_SYSDEADCHAR) return 0;
            }
        }
        long result = nCallWindowProc(previousParentProcedure, hwnd, message, wParam, lParam);
        if (message == WM_NCDESTROY) {
            parent = 0;
            window = 0;
        }
        return result;
    }

    private void resize(int newWidth, int newHeight) {
        if (newWidth > 0 && newHeight > 0 && (newWidth != width || newHeight != height)) {
            if (!SetWindowPos(null, window, 0, 0, 0, newWidth, newHeight,
                    SWP_NOMOVE | SWP_NOZORDER | SWP_NOACTIVATE)) {
                LOGGER.error("Could not resize Remix presentation window: Win32 error {}",
                        WinBase.GetLastError());
                return;
            }
            width = newWidth;
            height = newHeight;
        }
    }

    private static boolean isInputMessage(int message) {
        // RAWINPUT stays with SDL: its native handle must only be consumed once.
        return message >= WM_KEYFIRST && message <= WM_UNICHAR
                || message >= WM_MOUSEFIRST && message <= WM_MOUSEHWHEEL
                || message == WM_MOUSELEAVE
                || message == WM_SETFOCUS
                || message == WM_KILLFOCUS
                || message == WM_ACTIVATEAPP;
    }

    private static IllegalStateException failure(String operation) {
        return new IllegalStateException(operation + " failed: Win32 error " + WinBase.GetLastError());
    }

    /** Releases callbacks after the parent and native renderer have stopped. */
    @Override
    public void close() {
        if (closed) return;
        // Once synchronous destruction completes, only process-wide class and
        // callback cleanup remains. Do not dispatch through an event loop that
        // may already have queued SDL_Quit.
        if (parentDestroyed || window == 0 && SDLInit.SDL_WasInit(SDLInit.SDL_INIT_VIDEO) == 0) {
            closeOnWindowThread();
        } else {
            onWindowThread(this::closeOnWindowThread);
        }
    }

    private void closeOnWindowThread() {
        if (closed) return;
        CleanupSteps cleanup = new CleanupSteps();
        cleanup.run(this::stopOnWindowThread);
        cleanup.run(() -> {
            if (window != 0) {
                if (!DestroyWindow(null, window)) throw failure("DestroyWindow");
                window = 0;
            }
        });
        cleanup.run(() -> {
            if (window == 0 && registered) {
                if (!UnregisterClass(null, className, module)) throw failure("UnregisterClass");
                registered = false;
            }
            if (!registered && window == 0 && !childCallbackFreed) {
                childProcedure.free();
                childCallbackFreed = true;
            }
        });
        cleanup.run(() -> {
            if (parentHooked && parent != 0 && GetWindowLongPtr(parent, GWL_WNDPROC) != 0) {
                throw new IllegalStateException("Presentation callback is still in a live window subclass chain");
            }
            if (!parentCallbackFreed) {
                parentProcedure.free();
                parentCallbackFreed = true;
            }
        });
        closed = childCallbackFreed && parentCallbackFreed;
        cleanup.finish();
    }

    /** Window callbacks and lifecycle share SDL's thread, including with event-thread mods. */
    private static void onWindowThread(Runnable action) {
        if (SDLInit.SDL_IsMainThread()) {
            action.run();
            return;
        }
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (SDL_MainThreadCallback callback = SDL_MainThreadCallback.create(userdata -> {
            try {
                action.run();
            } catch (Throwable error) {
                failure.set(error);
            }
        })) {
            if (!SDLInit.SDL_RunOnMainThread(callback, 0L, true)) {
                throw new IllegalStateException("Could not run window operation: " + SDLError.SDL_GetError());
            }
        }
        Throwable error = failure.get();
        if (error instanceof RuntimeException runtime) throw runtime;
        if (error instanceof Error fatal) throw fatal;
        if (error != null) throw new IllegalStateException("Window operation failed", error);
    }
}
