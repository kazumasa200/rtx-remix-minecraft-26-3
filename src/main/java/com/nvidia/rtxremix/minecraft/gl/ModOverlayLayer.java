/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.gl;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import java.nio.IntBuffer;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryStack;

/**
 * Keeps what other mods draw into the world during the level render over the Remix image:
 * Litematica's schematics, MiniHUD's overlays, Axiom's selections, and whatever else draws
 * through Fabric's {@code LevelRenderEvents} or at the end of {@code LevelRenderer.render}.
 * They draw straight into the main target, which the opaque Remix image then covered.
 *
 * <p>At the start of the main pass the main target's colour is cleared to transparent and
 * its depth is given the last Remix frame's, so those draws are hidden behind the path-traced
 * world as they are behind vanilla's, a frame late. After the level render the colour, which
 * then holds only those draws over transparency, is kept, and once the lens and weather have
 * drawn it is laid back over them.
 *
 * <p>Render thread only. Raw GL state other than the backend's cached state is restored.
 */
public final class ModOverlayLayer {

    private static final int UNIT = 0;

    private static final String VERTEX_SHADER = """
            #version 330 core
            void main() {
                vec2 corner = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                gl_Position = vec4(corner * 2.0 - 1.0, 0.0, 1.0);
            }
            """;

    // The Remix depth is window depth with top-left rows; Minecraft's projection decides how a
    // distance along the view axis lands in its own depth buffer, reversed or not.
    private static final String DEPTH_FRAGMENT_SHADER = """
            #version 330 core
            uniform sampler2D RemixDepth;
            uniform vec2 ViewportSize;
            uniform float Near;
            uniform float Far;
            // How far behind the game's camera the Remix camera stands.
            uniform float Back;
            // Rows 2 and 3 of the projection's third and fourth columns: m22, m32, m23, m33.
            uniform vec4 Projection;
            uniform float ZeroToOne;

            void main() {
                vec2 uv = gl_FragCoord.xy / ViewportSize;
                float depth = texture(RemixDepth, vec2(uv.x, 1.0 - uv.y)).r;
                float z = Near * Far / (Far - depth * (Far - Near)) - Back;
                float ndc = (Projection.x * -z + Projection.y) / (Projection.z * -z + Projection.w);
                gl_FragDepth = clamp(ZeroToOne > 0.5 ? ndc : ndc * 0.5 + 0.5, 0.0, 1.0);
            }
            """;

    private static final String COMPOSITE_FRAGMENT_SHADER = """
            #version 330 core
            uniform sampler2D Layer;
            out vec4 fragColor;

            void main() {
                fragColor = texelFetch(Layer, ivec2(gl_FragCoord.xy), 0);
            }
            """;

    private final Minecraft client = Minecraft.getInstance();
    private int depthProgram;
    private int compositeProgram;
    private int vertexArray;
    private int sampler;
    private int depthFramebuffer;
    private int mainFramebuffer;
    private int layerFramebuffer;
    private int layerTexture;
    private int layerWidth;
    private int layerHeight;
    private boolean prepared;
    private boolean captured;

    /**
     * Clears the main target's colour to transparent and writes the last Remix frame's depth
     * into its depth. Called at the start of the main pass, before any draw of it. The Remix
     * camera wrote that depth between {@code nearPlane} and {@code farPlane} from {@code back}
     * blocks behind the game's camera, which is 0 but in an orthographic view.
     */
    public void prepare(int remixDepthTexture, float nearPlane, float farPlane, float back, Matrix4fc projection) {
        prepared = false;
        captured = false;
        RenderTarget main = client.gameRenderer.mainRenderTarget();
        if (remixDepthTexture <= 0 || projection == null
                || !(main.getColorTexture() instanceof GlTexture color)
                || !(main.getDepthTexture() instanceof GlTexture depth)) {
            return;
        }
        ensureResources();
        if (mainFramebuffer == 0) {
            mainFramebuffer = GL45C.glCreateFramebuffers();
            GL45C.glNamedFramebufferDrawBuffer(mainFramebuffer, GL45C.GL_COLOR_ATTACHMENT0);
            GL45C.glNamedFramebufferReadBuffer(mainFramebuffer, GL45C.GL_COLOR_ATTACHMENT0);
            depthFramebuffer = GL45C.glCreateFramebuffers();
            GL45C.glNamedFramebufferDrawBuffer(depthFramebuffer, GL45C.GL_NONE);
        }
        // Attached every frame: a resize recreates the targets, and GL may hand a deleted
        // texture's name to its replacement.
        GL45C.glNamedFramebufferTexture(mainFramebuffer, GL45C.GL_COLOR_ATTACHMENT0, color.glId(), 0);
        GL45C.glNamedFramebufferTexture(depthFramebuffer, GL45C.GL_DEPTH_ATTACHMENT, depth.glId(), 0);

        GlStateManager._disableScissorTest();
        GlStateManager._colorMask(0xF);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            GL45C.glClearNamedFramebufferfv(mainFramebuffer, GL45C.GL_COLOR, 0, stack.floats(0, 0, 0, 0));
        }

        boolean zeroToOne = GL45C.glGetInteger(GL45C.GL_CLIP_DEPTH_MODE) == GL45C.GL_ZERO_TO_ONE;
        draw(depthFramebuffer, depthProgram, remixDepthTexture, main.width, main.height, () -> {
            GlStateManager._colorMask(0);
            GlStateManager._enableDepthTest();
            GlStateManager._depthFunc(GL45C.GL_ALWAYS);
            GlStateManager._depthMask(true);
            GlStateManager._disableBlend(0);
            GL45C.glUniform2f(GL45C.glGetUniformLocation(depthProgram, "ViewportSize"), main.width, main.height);
            GL45C.glUniform1f(GL45C.glGetUniformLocation(depthProgram, "Near"), nearPlane);
            GL45C.glUniform1f(GL45C.glGetUniformLocation(depthProgram, "Back"), back);
            GL45C.glUniform1f(GL45C.glGetUniformLocation(depthProgram, "Far"), farPlane);
            GL45C.glUniform4f(GL45C.glGetUniformLocation(depthProgram, "Projection"),
                    projection.m22(), projection.m32(), projection.m23(), projection.m33());
            GL45C.glUniform1f(GL45C.glGetUniformLocation(depthProgram, "ZeroToOne"), zeroToOne ? 1.0f : 0.0f);
        });
        GlStateManager._colorMask(0xF);
        prepared = true;
    }

    /** Keeps the main target's colour, the other mods' draws over transparency. Called after the level render. */
    public void capture() {
        if (!prepared) return;
        prepared = false;
        RenderTarget main = client.gameRenderer.mainRenderTarget();
        if (!(main.getColorTexture() instanceof GlTexture color)) return;
        if (layerTexture == 0 || main.width != layerWidth || main.height != layerHeight) {
            if (layerTexture != 0) GL45C.glDeleteTextures(layerTexture);
            if (layerFramebuffer == 0) layerFramebuffer = GL45C.glCreateFramebuffers();
            layerTexture = GL45C.glCreateTextures(GL45C.GL_TEXTURE_2D);
            GL45C.glTextureStorage2D(layerTexture, 1, GL45C.GL_RGBA8, main.width, main.height);
            GL45C.glNamedFramebufferTexture(layerFramebuffer, GL45C.GL_COLOR_ATTACHMENT0, layerTexture, 0);
            GL45C.glNamedFramebufferDrawBuffer(layerFramebuffer, GL45C.GL_COLOR_ATTACHMENT0);
            layerWidth = main.width;
            layerHeight = main.height;
        }
        GL45C.glNamedFramebufferTexture(mainFramebuffer, GL45C.GL_COLOR_ATTACHMENT0, color.glId(), 0);
        GlStateManager._disableScissorTest();
        GL45C.glBlitNamedFramebuffer(mainFramebuffer, layerFramebuffer, 0, 0, main.width, main.height,
                0, 0, main.width, main.height, GL45C.GL_COLOR_BUFFER_BIT, GL45C.GL_NEAREST);
        captured = true;
    }

    /** Lays the kept draws over the main target. Called once the lens and weather have drawn. */
    public void composite() {
        if (!captured) return;
        captured = false;
        RenderTarget main = client.gameRenderer.mainRenderTarget();
        if (!(main.getColorTexture() instanceof GlTexture color)
                || main.width != layerWidth || main.height != layerHeight) {
            return;
        }
        GL45C.glNamedFramebufferTexture(mainFramebuffer, GL45C.GL_COLOR_ATTACHMENT0, color.glId(), 0);
        // The draws blended over transparency, so the kept colour is premultiplied.
        draw(mainFramebuffer, compositeProgram, layerTexture, main.width, main.height, () -> {
            GlStateManager._disableDepthTest();
            GlStateManager._depthMask(false);
            GlStateManager._colorMask(0xF);
            GlStateManager._enableBlend(0);
            GlStateManager._blendFuncSeparate(GL45C.GL_ONE, GL45C.GL_ONE_MINUS_SRC_ALPHA, GL45C.GL_ZERO, GL45C.GL_ONE);
            GlStateManager._blendEquationSeparate(GL45C.GL_FUNC_ADD, GL45C.GL_FUNC_ADD);
        });
    }

    private void draw(int framebuffer, int program, int texture, int width, int height, Runnable state) {
        int previousProgram = GL45C.glGetInteger(GL45C.GL_CURRENT_PROGRAM);
        int previousVertexArray = GL45C.glGetInteger(GL45C.GL_VERTEX_ARRAY_BINDING);
        int previousDrawFramebuffer = GlStateManager.getFrameBuffer(GL45C.GL_DRAW_FRAMEBUFFER);
        GlStateManager._activeTexture(GL45C.GL_TEXTURE0 + UNIT);
        int previousSampler = GL45C.glGetInteger(GL45C.GL_SAMPLER_BINDING);
        int previousTexture = GL45C.glGetInteger(GL45C.GL_TEXTURE_BINDING_2D);
        int[] previousViewport = new int[4];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer viewport = stack.mallocInt(4);
            GL45C.glGetIntegerv(GL45C.GL_VIEWPORT, viewport);
            viewport.get(previousViewport);
        }
        try {
            GlStateManager._glBindFramebuffer(GL45C.GL_DRAW_FRAMEBUFFER, framebuffer);
            GlStateManager._viewport(0, 0, width, height);
            GlStateManager._disableCull();
            GlStateManager._disableScissorTest();
            GL45C.glUseProgram(program);
            state.run();
            GL45C.glBindTextureUnit(UNIT, texture);
            GL45C.glBindSampler(UNIT, sampler);
            GL45C.glBindVertexArray(vertexArray);
            GL45C.glDrawArrays(GL45C.GL_TRIANGLES, 0, 3);
        } finally {
            GL45C.glBindSampler(UNIT, previousSampler);
            GL45C.glBindTexture(GL45C.GL_TEXTURE_2D, previousTexture);
            GL45C.glBindVertexArray(previousVertexArray);
            GL45C.glUseProgram(previousProgram);
            GlStateManager._glBindFramebuffer(GL45C.GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer);
            GlStateManager._viewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3]);
        }
    }

    private void ensureResources() {
        if (depthProgram != 0) return;
        depthProgram = link(DEPTH_FRAGMENT_SHADER, "RemixDepth");
        compositeProgram = link(COMPOSITE_FRAGMENT_SHADER, "Layer");
        vertexArray = GL45C.glCreateVertexArrays();
        sampler = GL45C.glCreateSamplers();
        GL45C.glSamplerParameteri(sampler, GL45C.GL_TEXTURE_MIN_FILTER, GL45C.GL_NEAREST);
        GL45C.glSamplerParameteri(sampler, GL45C.GL_TEXTURE_MAG_FILTER, GL45C.GL_NEAREST);
        GL45C.glSamplerParameteri(sampler, GL45C.GL_TEXTURE_WRAP_S, GL45C.GL_CLAMP_TO_EDGE);
        GL45C.glSamplerParameteri(sampler, GL45C.GL_TEXTURE_WRAP_T, GL45C.GL_CLAMP_TO_EDGE);
    }

    private static int link(String fragmentSource, String samplerName) {
        int vertexShader = compile(GL45C.GL_VERTEX_SHADER, VERTEX_SHADER);
        int fragmentShader = 0;
        try {
            fragmentShader = compile(GL45C.GL_FRAGMENT_SHADER, fragmentSource);
            int program = GL45C.glCreateProgram();
            GL45C.glAttachShader(program, vertexShader);
            GL45C.glAttachShader(program, fragmentShader);
            GL45C.glLinkProgram(program);
            if (GL45C.glGetProgrami(program, GL45C.GL_LINK_STATUS) == GL45C.GL_FALSE) {
                String log = GL45C.glGetProgramInfoLog(program);
                GL45C.glDeleteProgram(program);
                throw new IllegalStateException("Failed to link mod overlay shader: " + log);
            }
            GL45C.glProgramUniform1i(program, GL45C.glGetUniformLocation(program, samplerName), UNIT);
            return program;
        } finally {
            GL45C.glDeleteShader(vertexShader);
            if (fragmentShader != 0) GL45C.glDeleteShader(fragmentShader);
        }
    }

    private static int compile(int type, String source) {
        int shader = GL45C.glCreateShader(type);
        GL45C.glShaderSource(shader, source);
        GL45C.glCompileShader(shader);
        if (GL45C.glGetShaderi(shader, GL45C.GL_COMPILE_STATUS) == GL45C.GL_FALSE) {
            String log = GL45C.glGetShaderInfoLog(shader);
            GL45C.glDeleteShader(shader);
            throw new IllegalStateException("Failed to compile mod overlay shader: " + log);
        }
        return shader;
    }

    public void shutdown() {
        if (depthProgram != 0) GL45C.glDeleteProgram(depthProgram);
        if (compositeProgram != 0) GL45C.glDeleteProgram(compositeProgram);
        if (vertexArray != 0) GL45C.glDeleteVertexArrays(vertexArray);
        if (sampler != 0) GL45C.glDeleteSamplers(sampler);
        if (depthFramebuffer != 0) GL45C.glDeleteFramebuffers(depthFramebuffer);
        if (mainFramebuffer != 0) GL45C.glDeleteFramebuffers(mainFramebuffer);
        if (layerFramebuffer != 0) GL45C.glDeleteFramebuffers(layerFramebuffer);
        if (layerTexture != 0) GL45C.glDeleteTextures(layerTexture);
        depthProgram = compositeProgram = vertexArray = sampler = 0;
        depthFramebuffer = mainFramebuffer = layerFramebuffer = layerTexture = 0;
        prepared = captured = false;
    }
}
