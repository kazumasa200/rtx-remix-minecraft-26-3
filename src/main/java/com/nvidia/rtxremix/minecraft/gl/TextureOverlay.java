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
 * before the screen overlays and HUD.
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
    
    public TextureOverlay() {
        this.client = Minecraft.getInstance();
        LOGGER.info("TextureOverlay initialized");
    }

    /**
     * Renders a texture as a full-screen overlay.
     *
     * @param textureId The GL texture ID to render as full-screen overlay
     * @param target the texture to fill, the main target's size, or 0 for the main target's colour
     */
    public void render(int textureId, int target) {
        if (textureId <= 0) {
            return;
        }

        if (client == null || client.getWindow() == null) {
            return;
        }

        try {
            renderTextureOverlay(textureId, target);
        } catch (Exception e) {
            LOGGER.error("Error rendering texture overlay", e);
        }
    }

    private void renderTextureOverlay(int texId, int target) {
        RenderTarget mainTarget = client.gameRenderer.mainRenderTarget();
        GpuTexture color = mainTarget.getColorTexture();
        if (!(color instanceof GlTexture glColor)) {
            return;
        }

        bindSource(texId);
        bindTarget(target != 0 ? target : glColor.glId());
        int sourceWidth = GL45C.glGetTextureLevelParameteri(texId, 0, GL45C.GL_TEXTURE_WIDTH);
        int sourceHeight = GL45C.glGetTextureLevelParameteri(texId, 0, GL45C.GL_TEXTURE_HEIGHT);

        // Opaque: the Remix image replaces vanilla's output rather than blending
        // with it, and it neither tests nor writes depth. The destination rows
        // run top to bottom so the image lands upright: the interop blit already
        // corrects the Vulkan image's orientation for top-left texture origin.
        GlStateManager._disableScissorTest();
        GL45C.glBlitNamedFramebuffer(
                sourceFbo,
                targetFbo,
                0,
                0,
                sourceWidth,
                sourceHeight,
                0,
                mainTarget.height,
                mainTarget.width,
                0,
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
        LOGGER.info("TextureOverlay shutdown");
    }
}
