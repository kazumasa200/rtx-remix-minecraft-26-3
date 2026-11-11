/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.gl;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL45C;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Copies the Remix output texture over Minecraft's main render target, fully
 * opaque, after the world and first-person model have been submitted and
 * before the screen overlays and HUD. A fresh Remix write is Vulkan-oriented
 * and is flipped upright once. While {@link CompositeHold} is checking for a
 * leftover composite, that flip lands in a private texture and the main target
 * copies the private texture straight, so the HUD picture copied back for
 * present is never itself flipped.
 *
 * <p>Render thread only. Minecraft runs on its OpenGL backend whenever the mod
 * is active, so the copy is a framebuffer blit on the backend's own context.
 * It binds no framebuffer and only switches off the scissor test through the
 * backend's state cache, so the surrounding render state stays valid.
 */
public class TextureOverlay {

    private static final Logger LOGGER = LoggerFactory.getLogger(TextureOverlay.class);

    private final Minecraft client;

    /**
     * Framebuffers reading the Remix texture and writing the main target's color
     * texture. Both textures are attached again every frame: either is recreated
     * on a resize, and GL may hand a deleted texture's name to its replacement.
     */
    private int sourceFbo;
    private int targetFbo;
    /** Upright copy of the last Remix world. The composite never lands here. */
    private int lastWorldTex;
    private int lastWorldFbo;
    private int lastWorldWidth;
    private int lastWorldHeight;
    private int lastWorldFormat;
    
    public TextureOverlay() {
        this.client = Minecraft.getInstance();
        LOGGER.info("TextureOverlay initialized");
    }

    /**
     * Renders the world under the HUD. A real Remix write is flipped once into
     * the private copy when the decision asks for that. The composite path
     * blits that copy straight.
     *
     * @param textureId The shared Remix color texture
     */
    public void render(int textureId, CompositeHold.Decision decision) {
        if (textureId <= 0 || decision == null) {
            return;
        }

        if (client == null || client.getWindow() == null) {
            return;
        }

        try {
            renderTextureOverlay(textureId, decision);
        } catch (Exception e) {
            LOGGER.error("Error rendering texture overlay", e);
        }
    }

    private void renderTextureOverlay(int texId, CompositeHold.Decision decision) {
        RenderTarget mainTarget = client.gameRenderer.mainRenderTarget();
        GpuTexture color = mainTarget.getColorTexture();
        if (!(color instanceof GlTexture glColor)) {
            return;
        }

        int sourceWidth = GL45C.glGetTextureLevelParameteri(texId, 0, GL45C.GL_TEXTURE_WIDTH);
        int sourceHeight = GL45C.glGetTextureLevelParameteri(texId, 0, GL45C.GL_TEXTURE_HEIGHT);
        if (decision.updateLastWorld()) {
            copyIntoLastWorld(texId, sourceWidth, sourceHeight);
        }
        boolean showHeld = decision.source() == CompositeHold.Source.LAST_WORLD && holdsWorld(sourceWidth, sourceHeight);

        // Opaque: the Remix image replaces vanilla's output rather than blending
        // with it, and it neither tests nor writes depth. A fresh Vulkan write is
        // stored top to bottom. The private copy is already upright.
        GlStateManager._disableScissorTest();
        bindTarget(glColor.glId());
        if (showHeld) {
            bindSource(lastWorldTex);
            blitSourceToTarget(sourceWidth, sourceHeight, mainTarget.width, mainTarget.height, false);
            return;
        }
        bindSource(texId);
        boolean flipY = decision.flipInterop() || (decision.updateLastWorld() && !holdsWorld(sourceWidth, sourceHeight));
        blitSourceToTarget(sourceWidth, sourceHeight, mainTarget.width, mainTarget.height, flipY);
    }

    /** Flips a fresh Vulkan write into the private texture. A composite is never passed here. */
    private void copyIntoLastWorld(int texId, int width, int height) {
        int format = GL45C.glGetTextureLevelParameteri(texId, 0, GL45C.GL_TEXTURE_INTERNAL_FORMAT);
        if (!ensureLastWorld(width, height, format)) {
            return;
        }
        bindSource(texId);
        GL45C.glBlitNamedFramebuffer(
                sourceFbo,
                lastWorldFbo,
                0,
                0,
                width,
                height,
                0,
                height,
                width,
                0,
                GL45C.GL_COLOR_BUFFER_BIT,
                GL45C.GL_NEAREST);
    }

    private boolean holdsWorld(int width, int height) {
        return lastWorldTex != 0 && lastWorldWidth == width && lastWorldHeight == height;
    }

    private boolean ensureLastWorld(int width, int height, int format) {
        if (width < 1 || height < 1 || (format != GL45C.GL_RGBA8 && format != GL45C.GL_SRGB8_ALPHA8)) {
            return false;
        }
        if (lastWorldTex != 0 && lastWorldWidth == width && lastWorldHeight == height && lastWorldFormat == format) {
            return true;
        }
        deleteLastWorldTexture();
        lastWorldTex = GL45C.glCreateTextures(GL45C.GL_TEXTURE_2D);
        GL45C.glTextureStorage2D(lastWorldTex, 1, format, width, height);
        GL45C.glTextureParameteri(lastWorldTex, GL45C.GL_TEXTURE_MIN_FILTER, GL45C.GL_LINEAR);
        GL45C.glTextureParameteri(lastWorldTex, GL45C.GL_TEXTURE_MAG_FILTER, GL45C.GL_LINEAR);
        if (lastWorldFbo == 0) {
            lastWorldFbo = GL45C.glCreateFramebuffers();
            GL45C.glNamedFramebufferDrawBuffer(lastWorldFbo, GL45C.GL_COLOR_ATTACHMENT0);
        }
        GL45C.glNamedFramebufferTexture(lastWorldFbo, GL45C.GL_COLOR_ATTACHMENT0, lastWorldTex, 0);
        lastWorldWidth = width;
        lastWorldHeight = height;
        lastWorldFormat = format;
        return true;
    }

    private void blitSourceToTarget(int sourceWidth, int sourceHeight, int destWidth, int destHeight, boolean flipY) {
        int destY0 = flipY ? destHeight : 0;
        int destY1 = flipY ? 0 : destHeight;
        GL45C.glBlitNamedFramebuffer(
                sourceFbo,
                targetFbo,
                0,
                0,
                sourceWidth,
                sourceHeight,
                0,
                destY0,
                destWidth,
                destY1,
                GL45C.GL_COLOR_BUFFER_BIT,
                GL45C.GL_LINEAR);
    }

    private void bindSource(int texId) {
        if (sourceFbo == 0) {
            sourceFbo = GL45C.glCreateFramebuffers();
            GL45C.glNamedFramebufferReadBuffer(sourceFbo, GL45C.GL_COLOR_ATTACHMENT0);
        }
        GL45C.glNamedFramebufferTexture(sourceFbo, GL45C.GL_COLOR_ATTACHMENT0, texId, 0);
    }

    private void bindTarget(int texId) {
        if (targetFbo == 0) {
            targetFbo = GL45C.glCreateFramebuffers();
            GL45C.glNamedFramebufferDrawBuffer(targetFbo, GL45C.GL_COLOR_ATTACHMENT0);
        }
        GL45C.glNamedFramebufferTexture(targetFbo, GL45C.GL_COLOR_ATTACHMENT0, texId, 0);
    }
    
    /**
     * Cleans up resources.
     */
    public void shutdown() {
        if (sourceFbo != 0) {
            GL45C.glDeleteFramebuffers(sourceFbo);
            sourceFbo = 0;
        }
        if (targetFbo != 0) {
            GL45C.glDeleteFramebuffers(targetFbo);
            targetFbo = 0;
        }
        deleteLastWorldTexture();
        if (lastWorldFbo != 0) {
            GL45C.glDeleteFramebuffers(lastWorldFbo);
            lastWorldFbo = 0;
        }
        LOGGER.info("TextureOverlay shutdown");
    }

    private void deleteLastWorldTexture() {
        if (lastWorldTex != 0) {
            GL45C.glDeleteTextures(lastWorldTex);
            lastWorldTex = 0;
        }
        lastWorldWidth = 0;
        lastWorldHeight = 0;
        lastWorldFormat = 0;
    }
}
