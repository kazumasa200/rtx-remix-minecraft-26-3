/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.gl;

import com.nvidia.rtxremix.InteropInfo;
import com.nvidia.rtxremix.RemixBridge;
import com.nvidia.rtxremix.TextureFormat;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.EXTMemoryObject.*;
import static org.lwjgl.opengl.EXTMemoryObjectWin32.GL_HANDLE_TYPE_OPAQUE_WIN32_EXT;
import static org.lwjgl.opengl.EXTMemoryObjectWin32.glImportMemoryWin32HandleEXT;
import static org.lwjgl.opengl.EXTSemaphore.*;
import static org.lwjgl.opengl.EXTSemaphoreWin32.glImportSemaphoreWin32HandleEXT;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL21.GL_SRGB8_ALPHA8;
import static org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL30.GL_R32F;
import static org.lwjgl.opengl.GL30.glDeleteFramebuffers;
import static org.lwjgl.opengl.GL45.*;

/**
 * Imports Remix color and primary-depth images into OpenGL under one semaphore pair.
 */
public final class GlInterop {
    private static final Logger LOGGER = LoggerFactory.getLogger(GlInterop.class);
    private static final int[] NO_BUFFERS = new int[0];

    private final InteropImage colorImage = new InteropImage(true);
    private final InteropImage depthImage = new InteropImage(false);
    private final long dxvkDoneSemaphoreHandle;
    private final long clientDoneSemaphoreHandle;
    private int vkDoneSemaphore;
    private int clientDoneSemaphore;
    private long clientDoneSemaphoreValue;
    private long lastWaitedVkDoneSemaphoreValue = -1;
    private int[] barrierTextures = new int[0];
    private int[] barrierLayouts = new int[0];
    /**
     * Pixels of the GL composite last copied into the color image. Shader
     * compilation signals a new timeline value without writing a world, and the
     * image still holds this composite. The overlay must not flip that copy.
     * The read stands down once play is producing new frames; see {@link CompositeHold}.
     */
    private static final int STAMP_PIXELS = 16;
    private static final int STAMP_ROW_BYTES = STAMP_PIXELS * 4;
    private final CompositeHold compositeHold = new CompositeHold();
    private byte[] savedCompositeStamp;
    private ByteBuffer stampRead;
    private boolean keptCompositeLogged;
    private boolean stampReadFailedLogged;
    /** Set by {@link #decideOverlay} and consumed by the next composite copy. */
    private boolean stampOnPresent;

    public GlInterop(InteropInfo interopInfo) {
        if (interopInfo.dxvkDoneSemaphore_Handle == 0
                || interopInfo.clientDoneSemaphore_Handle == 0) {
            throw new IllegalArgumentException("Remix interop semaphore handles must be nonzero");
        }
        dxvkDoneSemaphoreHandle = interopInfo.dxvkDoneSemaphore_Handle;
        clientDoneSemaphoreHandle = interopInfo.clientDoneSemaphore_Handle;
        vkDoneSemaphore = importVulkanSemaphore(
                dxvkDoneSemaphoreHandle, "dxvkDoneSemaphore_Handle");
        clientDoneSemaphore = importVulkanSemaphore(
                clientDoneSemaphoreHandle, "clientDoneSemaphore_Handle");
    }

    private int importVulkanSemaphore(long win32Handle, String debugName) {
        int semaphore = RemixBridge.openglCreateSemaphoresNV();
        checkGlError("glCreateSemaphoresNV " + debugName);
        if (semaphore == 0) {
            throw new IllegalStateException("Failed to create GL semaphore for " + debugName);
        }
        int result = RemixBridge.openglSemaphoreParameterivNV(
                semaphore,
                RemixBridge.GL_SEMAPHORE_TYPE_NV,
                RemixBridge.GL_SEMAPHORE_TYPE_TIMELINE_NV);
        checkGlError("glSemaphoreParameterivNV " + debugName);
        if (result != 0) {
            throw new IllegalStateException("Failed to configure timeline semaphore " + debugName);
        }
        glImportSemaphoreWin32HandleEXT(
                semaphore, GL_HANDLE_TYPE_OPAQUE_WIN32_EXT, win32Handle);
        checkGlError("glImportSemaphoreWin32HandleEXT " + debugName);
        return semaphore;
    }

    public boolean recreateImagesIf(InteropInfo info) {
        validateSemaphoreHandles(info);
        boolean recreated = colorImage.recreate(
                info.imageMemory_Handle,
                info.allocationSize,
                info.width,
                info.height,
                glInternalFormat(info.interopImageFormat),
                GL_LINEAR,
                "color");
        recreated |= depthImage.recreate(
                info.depthImageMemory_Handle,
                info.depthAllocationSize,
                info.depthWidth,
                info.depthHeight,
                glInternalFormat(info.interopDepthImageFormat),
                GL_NEAREST,
                "depth");
        if (recreated) {
            rebuildBarrierArrays();
            lastWaitedVkDoneSemaphoreValue = -1;
            forgetCompositeStamp();
        }
        return recreated;
    }

    /**
     * Waits until Remix has finished a submit. An unchanged timeline value is
     * the previous present and returns false without touching the pixels.
     * A moved value means the submit completed; the pixels may still be the
     * composite this client copied in. {@link #decideOverlay} is what chooses
     * the flip, and only the overlay path calls it.
     */
    public boolean waitForVk(InteropInfo info) {
        validateSemaphoreHandles(info);
        if (!hasNewVulkanImage(info.dxvkDoneSemaphore_ValueToWait, lastWaitedVkDoneSemaphoreValue)) {
            return false;
        }
        if (info.dxvkDoneSemaphore_ValueToWait < lastWaitedVkDoneSemaphoreValue) {
            throw new IllegalStateException(
                    "Vulkan-done semaphore value moved backwards on the same semaphore: "
                            + info.dxvkDoneSemaphore_ValueToWait + " after "
                            + lastWaitedVkDoneSemaphoreValue);
        }
        drainStaleGlErrors();
        int result = RemixBridge.openglSemaphoreParameterui64vEXT(
                vkDoneSemaphore,
                RemixBridge.GL_TIMELINE_SEMAPHORE_VALUE_NV,
                info.dxvkDoneSemaphore_ValueToWait);
        checkGlError("glSemaphoreParameterui64vNV(wait)");
        if (result != 0) {
            throw new IllegalStateException("Failed to set Vulkan-done semaphore value");
        }
        glWaitSemaphoreEXT(
                vkDoneSemaphore, NO_BUFFERS, barrierTextures, barrierLayouts);
        checkGlError("glWaitSemaphoreEXT");
        lastWaitedVkDoneSemaphoreValue = info.dxvkDoneSemaphore_ValueToWait;
        return true;
    }

    /**
     * Chooses the overlay source for this frame. Call once, after {@link #waitForVk},
     * and not again from the present path: that second wait sees the same timeline
     * value and would clear the streak.
     */
    public CompositeHold.Decision decideOverlay(boolean screenOpen, boolean semaphoreAdvanced) {
        CompositeHold.Decision decision;
        if (!semaphoreAdvanced) {
            decision = compositeHold.unchangedSemaphore(screenOpen);
        } else if (!compositeHold.isArmed()) {
            keptCompositeLogged = false;
            decision = compositeHold.advancedWhileDisarmed(screenOpen);
        } else {
            boolean stillComposite = imageMatchesSavedComposite();
            if (stillComposite) {
                if (!keptCompositeLogged) {
                    LOGGER.info(
                            "Remix completed a submit without replacing the previous frame; keeping that frame upright");
                    keptCompositeLogged = true;
                }
            } else {
                keptCompositeLogged = false;
            }
            decision = compositeHold.advancedWhileArmed(screenOpen, stillComposite);
        }
        stampOnPresent = decision.stampOnPresent();
        return decision;
    }

    /** A changed timeline value means Remix completed a submit. The pixels may be unchanged. */
    static boolean hasNewVulkanImage(long signaledValue, long lastWaitedValue) {
        return signaledValue != lastWaitedValue;
    }

    /**
     * The overlay flips only a submit whose pixels are no longer the composite
     * this client copied in. Shader compilation advances the semaphore and
     * leaves that composite in place.
     */
    static boolean overlayNeedsYFlip(boolean semaphoreAdvanced, boolean imageMatchesSavedComposite) {
        return semaphoreAdvanced && !imageMatchesSavedComposite;
    }

    static boolean stampMatches(byte[] saved, byte[] current) {
        if (saved == null || current == null || saved.length == 0 || saved.length != current.length) {
            return false;
        }
        for (int i = 0; i < saved.length; i++) {
            if (saved[i] != current[i]) {
                return false;
            }
        }
        return true;
    }

    private void validateSemaphoreHandles(InteropInfo info) {
        if (info.dxvkDoneSemaphore_Handle != dxvkDoneSemaphoreHandle
                || info.clientDoneSemaphore_Handle != clientDoneSemaphoreHandle) {
            throw new IllegalStateException(
                    "Remix replaced an interop semaphore during an active session");
        }
    }

    public void signalDone() {
        clientDoneSemaphoreValue++;
        int result = RemixBridge.openglSemaphoreParameterui64vEXT(
                clientDoneSemaphore,
                RemixBridge.GL_TIMELINE_SEMAPHORE_VALUE_NV,
                clientDoneSemaphoreValue);
        checkGlError("glSemaphoreParameterui64vNV(signal)");
        if (result != 0) {
            throw new IllegalStateException("Failed to set client-done semaphore value");
        }
        glSignalSemaphoreEXT(
                clientDoneSemaphore, NO_BUFFERS, barrierTextures, barrierLayouts);
        checkGlError("glSignalSemaphoreEXT");
        glFlush();
    }

    /** Copies the final texture with the same clipping and vertical placement as GL presentation. */
    public void copyFromFramebuffer(int sourceFbo, int sourceWidth, int sourceHeight) {
        boolean stamp = stampOnPresent;
        stampOnPresent = false;
        drainStaleGlErrors();
        if (!colorImage.available()) {
            return;
        }
        int destinationFbo = colorImage.framebuffer();
        int copyWidth = Math.min(sourceWidth, colorImage.width);
        int copyHeight = Math.min(sourceHeight, colorImage.height);
        int destinationY = Math.max(0, colorImage.height - sourceHeight);
        glBlitNamedFramebuffer(
                sourceFbo,
                destinationFbo,
                0,
                0,
                copyWidth,
                copyHeight,
                0,
                destinationY,
                copyWidth,
                destinationY + copyHeight,
                GL_COLOR_BUFFER_BIT,
                GL_NEAREST);
        checkGlError("glBlitNamedFramebuffer");
        if (stamp) {
            rememberCompositeStamp();
        }
    }

    public int getTexture() {
        return colorImage.texture;
    }

    public int getDepthTexture() {
        return depthImage.texture;
    }

    public int getVkDoneSemaphore() {
        return vkDoneSemaphore;
    }

    public int getClientDoneSemaphore() {
        return clientDoneSemaphore;
    }

    public long getClientDoneSemaphoreValue() {
        return clientDoneSemaphoreValue;
    }

    public void destroy() {
        colorImage.destroy();
        depthImage.destroy();
        if (vkDoneSemaphore != 0) {
            glDeleteSemaphoresEXT(vkDoneSemaphore);
            vkDoneSemaphore = 0;
        }
        if (clientDoneSemaphore != 0) {
            glDeleteSemaphoresEXT(clientDoneSemaphore);
            clientDoneSemaphore = 0;
        }
        barrierTextures = new int[0];
        barrierLayouts = new int[0];
        lastWaitedVkDoneSemaphoreValue = -1;
        forgetCompositeStamp();
        LOGGER.info("GlInterop: destroyed");
    }

    private void rememberCompositeStamp() {
        savedCompositeStamp = readCompositeStamp();
    }

    private void forgetCompositeStamp() {
        savedCompositeStamp = null;
        keptCompositeLogged = false;
    }

    /**
     * Two rows, low and high. A new Remix world is stored the other way up, so
     * those rows are not the rows of the composite even when the scene is still.
     * An untouched composite matches both, including while shader compilation
     * only adds its notice in the corner.
     */
    private boolean imageMatchesSavedComposite() {
        if (savedCompositeStamp == null) {
            return false;
        }
        return stampMatches(savedCompositeStamp, readCompositeStamp());
    }

    private byte[] readCompositeStamp() {
        if (!colorImage.available() || colorImage.width < STAMP_PIXELS || colorImage.height < 2) {
            return null;
        }
        int alignment = glGetInteger(GL_PACK_ALIGNMENT);
        int rowLength = glGetInteger(GL_PACK_ROW_LENGTH);
        int skipRows = glGetInteger(GL_PACK_SKIP_ROWS);
        int skipPixels = glGetInteger(GL_PACK_SKIP_PIXELS);
        glPixelStorei(GL_PACK_ALIGNMENT, 4);
        glPixelStorei(GL_PACK_ROW_LENGTH, 0);
        glPixelStorei(GL_PACK_SKIP_ROWS, 0);
        glPixelStorei(GL_PACK_SKIP_PIXELS, 0);
        try {
            ByteBuffer buffer = stampBuffer();
            byte[] out = new byte[STAMP_ROW_BYTES * 2];
            int x = Math.max(0, colorImage.width / 2 - STAMP_PIXELS / 2);
            readStampRow(buffer, x, Math.max(1, colorImage.height / 8), out, 0);
            readStampRow(buffer, x, Math.max(1, (colorImage.height * 7) / 8), out, STAMP_ROW_BYTES);
            return out;
        } catch (RuntimeException e) {
            if (!stampReadFailedLogged) {
                LOGGER.warn("Could not read the interop frame stamp; flipping whenever the semaphore moves", e);
                stampReadFailedLogged = true;
            }
            return null;
        } finally {
            glPixelStorei(GL_PACK_ALIGNMENT, alignment);
            glPixelStorei(GL_PACK_ROW_LENGTH, rowLength);
            glPixelStorei(GL_PACK_SKIP_ROWS, skipRows);
            glPixelStorei(GL_PACK_SKIP_PIXELS, skipPixels);
        }
    }

    private void readStampRow(ByteBuffer buffer, int x, int y, byte[] out, int outOffset) {
        buffer.clear();
        glGetTextureSubImage(
                colorImage.texture,
                0,
                x,
                y,
                0,
                STAMP_PIXELS,
                1,
                1,
                GL_RGBA,
                GL_UNSIGNED_BYTE,
                buffer);
        checkGlError("glGetTextureSubImage(composite stamp)");
        for (int i = 0; i < STAMP_ROW_BYTES; i++) {
            out[outOffset + i] = buffer.get(i);
        }
    }

    private ByteBuffer stampBuffer() {
        if (stampRead == null) {
            stampRead = ByteBuffer.allocateDirect(STAMP_ROW_BYTES);
        }
        return stampRead;
    }

    private void rebuildBarrierArrays() {
        int count = (colorImage.available() ? 1 : 0) + (depthImage.available() ? 1 : 0);
        barrierTextures = new int[count];
        barrierLayouts = new int[count];
        int index = 0;
        if (colorImage.available()) {
            barrierTextures[index] = colorImage.texture;
            barrierLayouts[index++] = GL_LAYOUT_GENERAL_EXT;
        }
        if (depthImage.available()) {
            barrierTextures[index] = depthImage.texture;
            barrierLayouts[index] = GL_LAYOUT_GENERAL_EXT;
        }
    }

    private static int glInternalFormat(int remixFormat) {
        return switch (remixFormat) {
            case TextureFormat.RGBA8_UNORM, TextureFormat.BGRA8_UNORM -> GL_RGBA8;
            case TextureFormat.RGBA8_SRGB, TextureFormat.BGRA8_SRGB -> GL_SRGB8_ALPHA8;
            case TextureFormat.R32_SFLOAT -> GL_R32F;
            default -> throw new IllegalArgumentException(
                    "Unsupported Remix interop texture format: " + remixFormat);
        };
    }

    /**
     * GL error flags are sticky, so an error left by the rest of the frame would be
     * reported as an interop failure by the next {@link #checkGlError}. Called once at
     * the head of each interop sequence rather than in every method: glGetError is a
     * synchronous driver query on the render thread, and the calls that follow within
     * one sequence report their own errors.
     */
    private void drainStaleGlErrors() {
        while (GL11.glGetError() != GL11.GL_NO_ERROR) {
            // Discard: only errors raised by the interop's own calls are its to report.
        }
    }

    private void checkGlError(String operation) {
        int error = GL11.glGetError();
        if (error != GL11.GL_NO_ERROR) {
            throw new IllegalStateException(
                    "OpenGL error after " + operation + ": 0x" + Integer.toHexString(error));
        }
    }

    private final class InteropImage {
        private final boolean color;
        private long memoryHandle;
        private int memoryObject;
        private int texture;
        private int width;
        private int height;
        private int internalFormat;
        private int destinationFbo;

        private InteropImage(boolean color) {
            this.color = color;
        }

        private boolean recreate(
                long newMemoryHandle,
                long allocationSize,
                int newWidth,
                int newHeight,
                int internalFormat,
                int filter,
                String debugName) {
            if (newMemoryHandle == 0 || newMemoryHandle == -1L) {
                if (available()) {
                    destroy();
                    return true;
                }
                return false;
            }
            if (available()
                    && memoryHandle == newMemoryHandle
                    && width == newWidth
                    && height == newHeight
                    && this.internalFormat == internalFormat) {
                return false;
            }
            if (allocationSize <= 0 || newWidth <= 0 || newHeight <= 0 || internalFormat == 0) {
                throw new IllegalArgumentException("Invalid Remix " + debugName + " interop image");
            }
            destroy();

            memoryHandle = newMemoryHandle;
            width = newWidth;
            height = newHeight;
            this.internalFormat = internalFormat;
            try {
                drainStaleGlErrors();
                memoryObject = glCreateMemoryObjectsEXT();
                glImportMemoryWin32HandleEXT(
                        memoryObject,
                        allocationSize,
                        GL_HANDLE_TYPE_OPAQUE_WIN32_EXT,
                        newMemoryHandle);
                checkGlError("glImportMemoryWin32HandleEXT(" + debugName + ")");

                texture = glCreateTextures(GL_TEXTURE_2D);
                glTextureStorageMem2DEXT(
                        texture, 1, internalFormat, width, height, memoryObject, 0);
                glTextureParameteri(texture, GL_TEXTURE_MIN_FILTER, filter);
                glTextureParameteri(texture, GL_TEXTURE_MAG_FILTER, filter);
                glTextureParameteri(texture, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
                glTextureParameteri(texture, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
                checkGlError("glTextureStorageMem2DEXT(" + debugName + ")");
            } catch (RuntimeException exception) {
                destroy();
                throw exception;
            }

            LOGGER.info(
                    "GlInterop: imported {} image texture={} size={}x{}",
                    debugName,
                    texture,
                    width,
                    height);
            return true;
        }

        private int framebuffer() {
            if (!color) {
                throw new IllegalStateException("Depth interop image cannot be a color target");
            }
            if (destinationFbo == 0) {
                destinationFbo = glCreateFramebuffers();
                glNamedFramebufferTexture(destinationFbo, GL_COLOR_ATTACHMENT0, texture, 0);
                checkGlError("glNamedFramebufferTexture");
            }
            return destinationFbo;
        }

        private boolean available() {
            return memoryObject != 0 && texture != 0;
        }

        private void destroy() {
            if (destinationFbo != 0) {
                glDeleteFramebuffers(destinationFbo);
                destinationFbo = 0;
            }
            if (texture != 0) {
                glDeleteTextures(texture);
                texture = 0;
            }
            if (memoryObject != 0) {
                glDeleteMemoryObjectsEXT(memoryObject);
                memoryObject = 0;
            }
            memoryHandle = 0;
            width = 0;
            height = 0;
            internalFormat = 0;
        }
    }
}
