/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.gl;

import com.mojang.renderpearl.backend.opengl.GlStateManager;
import com.nvidia.rtxremix.minecraft.HdrOutput;
import java.nio.IntBuffer;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryStack;

/**
 * HDR output's GL side: the images the lens and the weather work in, and the picture written to the shared image at
 * present.
 *
 * <p>The runtime leaves the HDR world in the shared image (scRGB, fp16, its top-down rows). The lens, the weather
 * and Minecraft's GUI are all made for the SDR picture, display encoded with white at 1, so the world is brought
 * into that encoding with paper white at 1 and nothing clamped: below the HDR curve's knee it is then the very
 * image they are tuned on, and a highlight is a value above 1. {@link #decodeWorld} writes that into the scene
 * texture, which stands where the interop texture does in SDR (same rows, the lens's and the weather's input), and
 * {@link #image} is the fp16 texture that stands where the main target does: the scene is copied into it, the lens
 * redraws it and the weather draws over it.
 *
 * <p>In such a frame the main target holds other mods' draws and the GUI over transparency, premultiplied.
 * {@link #compose} lays them over the image as they blend over it in SDR, so a translucent panel darkens the world
 * as it does there, and writes scRGB. In any other frame (menus, RTX off) the main target is the whole picture and
 * is shown at paper white. The Debug page's HDR Test Pattern draws patches at fixed brightness across the top.
 *
 * <p>Render thread only. Raw GL state other than the backend's cached state is restored.
 */
public final class HdrComposite {

    private static final String VERTEX_SHADER = """
            #version 330 core
            void main() {
                vec2 corner = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                gl_Position = vec4(corner * 2.0 - 1.0, 0.0, 1.0);
            }
            """;

    // The runtime encodes its SDR image with this gamma (its sRGB pass), and Minecraft blends its GUI on it.
    private static final String ENCODING = """
            const float GAMMA = 2.2;
            uniform float PaperScale;   // paper white / 80, scRGB's 1.0

            vec3 toDisplay(vec3 scRgb) {
                return pow(max(scRgb, vec3(0.0)) / PaperScale, vec3(1.0 / GAMMA));
            }

            vec3 toScRgb(vec3 display) {
                return pow(max(display, vec3(0.0)), vec3(GAMMA)) * PaperScale;
            }
            """;

    private static final String DECODE_FRAGMENT_SHADER = """
            #version 330 core
            uniform sampler2D World;    // the shared image: scRGB, the runtime's top-down rows
            out vec4 fragColor;
            """ + ENCODING + """

            void main() {
                fragColor = vec4(toDisplay(texelFetch(World, ivec2(gl_FragCoord.xy), 0).rgb), 1.0);
            }
            """;

    private static final String COMPOSE_FRAGMENT_SHADER = """
            #version 330 core
            uniform sampler2D Gui;      // the main target: RGBA8, GL's bottom-up rows
            uniform sampler2D Image;    // the composed world: display encoded, the main target's size and rows
            uniform ivec2 GuiSize;
            uniform vec2 Size;
            uniform int HasWorld;
            uniform int TestPattern;
            out vec4 fragColor;
            """ + ENCODING + """
            const float PATCH_NITS[11] = float[](80.0, 100.0, 200.0, 300.0, 400.0, 600.0, 800.0, 1000.0, 1500.0,
                    2000.0, 4000.0);

            void main() {
                ivec2 p = ivec2(gl_FragCoord.xy);
                vec4 gui = vec4(0.0);
                vec3 world = vec3(0.0);
                if (p.x < GuiSize.x && p.y < GuiSize.y) {
                    ivec2 texel = ivec2(p.x, GuiSize.y - 1 - p.y);
                    gui = texelFetch(Gui, texel, 0);
                    if (HasWorld != 0) {
                        world = texelFetch(Image, texel, 0).rgb;
                    }
                }
                vec3 colour = toScRgb(HasWorld != 0 ? gui.rgb + world * (1.0 - gui.a) : gui.rgb);
                if (TestPattern != 0) {
                    float row = float(p.y);
                    if (row >= Size.y / 40.0 && row < Size.y / 10.0) {
                        int i = clamp(int(float(p.x) / Size.x * 11.0), 0, 10);
                        colour = vec3(PATCH_NITS[i] / 80.0);
                    } else if (row >= Size.y / 10.0 && row < Size.y / 8.0) {
                        colour = vec3(PaperScale);
                    }
                }
                fragColor = vec4(colour, 1.0);
            }
            """;

    private int decodeProgram;
    private int decodePaperScale;
    private int composeProgram;
    private int guiSize;
    private int size;
    private int hasWorld;
    private int paperScale;
    private int testPattern;
    private int vertexArray;
    private int sampler;
    private int framebuffer;
    /** The world as the lens and the weather read it: display encoded, the shared image's size and rows. */
    private int scene;
    private int sceneWidth;
    private int sceneHeight;
    /** The picture they compose: display encoded, the main target's size and rows. */
    private int image;
    private int imageWidth;
    private int imageHeight;
    // What a draw changes and restores; fields so a frame allocates nothing.
    private final int[] previousSamplers = new int[2];
    private final int[] previousTextures = new int[2];
    private final int[] previousViewport = new int[4];

    /**
     * Brings the world the runtime left in {@code sharedTexture} into the scene texture, display encoded at paper
     * white, and returns that texture.
     */
    public int decodeWorld(int sharedTexture) {
        ensurePrograms();
        int width = GL45C.glGetTextureLevelParameteri(sharedTexture, 0, GL45C.GL_TEXTURE_WIDTH);
        int height = GL45C.glGetTextureLevelParameteri(sharedTexture, 0, GL45C.GL_TEXTURE_HEIGHT);
        if (scene == 0 || width != sceneWidth || height != sceneHeight) {
            scene = recreate(scene, width, height);
            sceneWidth = width;
            sceneHeight = height;
        }
        GL45C.glNamedFramebufferTexture(framebuffer, GL45C.GL_COLOR_ATTACHMENT0, scene, 0);
        GL45C.glProgramUniform1f(decodeProgram, decodePaperScale, HdrOutput.paperWhite() / HdrOutput.SCRGB_NITS);
        draw(decodeProgram, framebuffer, width, height, sharedTexture, 0);
        return scene;
    }

    /** The texture this frame's picture is composed in, {@code width} by {@code height} as the main target. */
    public int image(int width, int height) {
        ensurePrograms();
        if (image == 0 || width != imageWidth || height != imageHeight) {
            image = recreate(image, width, height);
            imageWidth = width;
            imageHeight = height;
        }
        return image;
    }

    /**
     * Writes this frame's picture into the shared image: guiFramebuffer's colour (the main target) over the composed
     * image when the frame took the world, else alone.
     */
    public void compose(GlInterop interop, int guiFramebuffer, int guiWidth, int guiHeight, boolean world) {
        int target = interop.colorFramebuffer();
        if (target == 0 || guiFramebuffer <= 0) {
            return;
        }
        int gui = GL45C.glGetNamedFramebufferAttachmentParameteri(guiFramebuffer, GL45C.GL_COLOR_ATTACHMENT0,
                GL45C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        if (gui == 0) {
            return;
        }
        ensurePrograms();
        // A resize between the world's draw and the present leaves an image of the old size: the frame shows the
        // GUI alone rather than a picture read out of place.
        boolean composed = world && image != 0 && imageWidth == guiWidth && imageHeight == guiHeight;
        int width = interop.colorWidth();
        int height = interop.colorHeight();
        GL45C.glProgramUniform2i(composeProgram, guiSize, guiWidth, guiHeight);
        GL45C.glProgramUniform2f(composeProgram, size, width, height);
        GL45C.glProgramUniform1i(composeProgram, hasWorld, composed ? 1 : 0);
        GL45C.glProgramUniform1f(composeProgram, paperScale, HdrOutput.paperWhite() / HdrOutput.SCRGB_NITS);
        GL45C.glProgramUniform1i(composeProgram, testPattern, HdrOutput.isTestPattern() ? 1 : 0);
        draw(composeProgram, target, width, height, gui, composed ? image : 0);
    }

    /** One full-target draw of {@code program} reading the two textures, with the state it changes put back. */
    private void draw(int program, int target, int width, int height, int texture0, int texture1) {
        int previousProgram = GL45C.glGetInteger(GL45C.GL_CURRENT_PROGRAM);
        int previousVertexArray = GL45C.glGetInteger(GL45C.GL_VERTEX_ARRAY_BINDING);
        int previousDrawFramebuffer = GlStateManager.getFrameBuffer(GL45C.GL_DRAW_FRAMEBUFFER);
        for (int unit = 0; unit < 2; unit++) {
            GlStateManager._activeTexture(GL45C.GL_TEXTURE0 + unit);
            previousSamplers[unit] = GL45C.glGetInteger(GL45C.GL_SAMPLER_BINDING);
            previousTextures[unit] = GL45C.glGetInteger(GL45C.GL_TEXTURE_BINDING_2D);
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer viewport = stack.mallocInt(4);
            GL45C.glGetIntegerv(GL45C.GL_VIEWPORT, viewport);
            viewport.get(previousViewport);
        }
        try {
            GlStateManager._glBindFramebuffer(GL45C.GL_DRAW_FRAMEBUFFER, target);
            GlStateManager._viewport(0, 0, width, height);
            GlStateManager._disableCull();
            GlStateManager._disableScissorTest();
            GlStateManager._disableDepthTest();
            GlStateManager._depthMask(false);
            GlStateManager._disableBlend(0);
            GlStateManager._colorMask(0xF);
            GL45C.glUseProgram(program);
            GL45C.glBindTextureUnit(0, texture0);
            GL45C.glBindSampler(0, sampler);
            GL45C.glBindTextureUnit(1, texture1);
            GL45C.glBindSampler(1, sampler);
            GL45C.glBindVertexArray(vertexArray);
            GL45C.glDrawArrays(GL45C.GL_TRIANGLES, 0, 3);
        } finally {
            for (int unit = 1; unit >= 0; unit--) {
                GlStateManager._activeTexture(GL45C.GL_TEXTURE0 + unit);
                GL45C.glBindSampler(unit, previousSamplers[unit]);
                GL45C.glBindTexture(GL45C.GL_TEXTURE_2D, previousTextures[unit]);
            }
            GL45C.glBindVertexArray(previousVertexArray);
            GL45C.glUseProgram(previousProgram);
            GlStateManager._glBindFramebuffer(GL45C.GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer);
            GlStateManager._viewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3]);
        }
    }

    private static int recreate(int texture, int width, int height) {
        if (texture != 0) {
            GL45C.glDeleteTextures(texture);
        }
        int created = GL45C.glCreateTextures(GL45C.GL_TEXTURE_2D);
        GL45C.glTextureStorage2D(created, 1, GL45C.GL_RGBA16F, width, height);
        return created;
    }

    private void ensurePrograms() {
        if (composeProgram != 0) {
            return;
        }
        int decode = link(DECODE_FRAGMENT_SHADER);
        GL45C.glProgramUniform1i(decode, GL45C.glGetUniformLocation(decode, "World"), 0);
        decodePaperScale = GL45C.glGetUniformLocation(decode, "PaperScale");
        int compose = link(COMPOSE_FRAGMENT_SHADER);
        GL45C.glProgramUniform1i(compose, GL45C.glGetUniformLocation(compose, "Gui"), 0);
        GL45C.glProgramUniform1i(compose, GL45C.glGetUniformLocation(compose, "Image"), 1);
        guiSize = GL45C.glGetUniformLocation(compose, "GuiSize");
        size = GL45C.glGetUniformLocation(compose, "Size");
        hasWorld = GL45C.glGetUniformLocation(compose, "HasWorld");
        paperScale = GL45C.glGetUniformLocation(compose, "PaperScale");
        testPattern = GL45C.glGetUniformLocation(compose, "TestPattern");
        vertexArray = GL45C.glCreateVertexArrays();
        sampler = GL45C.glCreateSamplers();
        GL45C.glSamplerParameteri(sampler, GL45C.GL_TEXTURE_MIN_FILTER, GL45C.GL_NEAREST);
        GL45C.glSamplerParameteri(sampler, GL45C.GL_TEXTURE_MAG_FILTER, GL45C.GL_NEAREST);
        GL45C.glSamplerParameteri(sampler, GL45C.GL_TEXTURE_WRAP_S, GL45C.GL_CLAMP_TO_EDGE);
        GL45C.glSamplerParameteri(sampler, GL45C.GL_TEXTURE_WRAP_T, GL45C.GL_CLAMP_TO_EDGE);
        framebuffer = GL45C.glCreateFramebuffers();
        GL45C.glNamedFramebufferDrawBuffer(framebuffer, GL45C.GL_COLOR_ATTACHMENT0);
        decodeProgram = decode;
        composeProgram = compose;
    }

    private static int link(String fragmentSource) {
        int vertex = compile(GL45C.GL_VERTEX_SHADER, VERTEX_SHADER);
        int fragment = 0;
        try {
            fragment = compile(GL45C.GL_FRAGMENT_SHADER, fragmentSource);
            int linked = GL45C.glCreateProgram();
            GL45C.glAttachShader(linked, vertex);
            GL45C.glAttachShader(linked, fragment);
            GL45C.glLinkProgram(linked);
            if (GL45C.glGetProgrami(linked, GL45C.GL_LINK_STATUS) == GL45C.GL_FALSE) {
                String log = GL45C.glGetProgramInfoLog(linked);
                GL45C.glDeleteProgram(linked);
                throw new IllegalStateException("Failed to link an HDR composite shader: " + log);
            }
            return linked;
        } finally {
            GL45C.glDeleteShader(vertex);
            if (fragment != 0) {
                GL45C.glDeleteShader(fragment);
            }
        }
    }

    private static int compile(int type, String source) {
        int shader = GL45C.glCreateShader(type);
        GL45C.glShaderSource(shader, source);
        GL45C.glCompileShader(shader);
        if (GL45C.glGetShaderi(shader, GL45C.GL_COMPILE_STATUS) == GL45C.GL_FALSE) {
            String log = GL45C.glGetShaderInfoLog(shader);
            GL45C.glDeleteShader(shader);
            throw new IllegalStateException("Failed to compile an HDR composite shader: " + log);
        }
        return shader;
    }

    public void shutdown() {
        if (decodeProgram != 0) GL45C.glDeleteProgram(decodeProgram);
        if (composeProgram != 0) GL45C.glDeleteProgram(composeProgram);
        if (vertexArray != 0) GL45C.glDeleteVertexArrays(vertexArray);
        if (sampler != 0) GL45C.glDeleteSamplers(sampler);
        if (framebuffer != 0) GL45C.glDeleteFramebuffers(framebuffer);
        if (scene != 0) GL45C.glDeleteTextures(scene);
        if (image != 0) GL45C.glDeleteTextures(image);
        decodeProgram = composeProgram = vertexArray = sampler = framebuffer = scene = image = 0;
    }
}
