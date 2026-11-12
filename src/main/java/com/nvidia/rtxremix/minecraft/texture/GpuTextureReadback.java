/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import com.nvidia.rtxremix.minecraft.mixin.TextureManagerAccessor;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryUtil;

/**
 * The pixels of a registered texture that has none on the CPU side, read back from the GPU.
 *
 * <p>A texture reaches Remix as pixels. A pack's file is read from the pack and a
 * {@code DynamicTexture} is snapshotted when it uploads ({@link DynamicTextureCache}), but a
 * mod may keep a texture anywhere: Figura's avatars live in a {@code SimpleTexture} with no
 * file behind it, and layers drawn with one had no material. Whatever the texture's class,
 * its pixels are on the GPU, so that is where they are read, once, when a material first
 * needs them; a later write to the same GPU texture marks the copy stale
 * ({@code CommandEncoderGlyphMirrorMixin}) and the next reader takes a fresh one.
 *
 * <p>Render thread only, except {@link #written}, which only tests a set.
 */
public final class GpuTextureReadback {

    /** Textures above this many texels are left alone: an atlas or a render target, not a skin. */
    private static final int MAX_TEXELS = 4096 * 4096;

    /** The GPU textures read back so far and the ids they were read for. */
    private static final Map<GpuTexture, Identifier> READ = Collections.synchronizedMap(new IdentityHashMap<>());
    private static final Set<Identifier> STALE = Collections.synchronizedSet(new java.util.HashSet<>());
    private static final Map<Identifier, DynamicTextureCache.Pixels> PIXELS = new HashMap<>();

    private GpuTextureReadback() {
    }

    /** Called for every write to a GPU texture; cheap unless that texture has been read back. */
    public static void written(GpuTexture destination) {
        if (READ.isEmpty()) {
            return;
        }
        Identifier id = READ.get(destination);
        if (id != null) {
            STALE.add(id);
        }
    }

    /** The pixels already read for {@code id}, refreshed if the texture was written since; null if never read. */
    public static DynamicTextureCache.@Nullable Pixels current(Identifier id) {
        DynamicTextureCache.Pixels pixels = PIXELS.get(id);
        if (pixels == null) {
            return null;
        }
        return STALE.remove(id) ? read(id) : pixels;
    }

    /** Reads the texture registered under {@code id} from the GPU, or returns null if it cannot be read. */
    public static DynamicTextureCache.@Nullable Pixels read(Identifier id) {
        AbstractTexture texture = ((TextureManagerAccessor) Minecraft.getInstance().getTextureManager())
                .rtxremix$byPath().get(id);
        if (texture == null) {
            return forget(id);
        }
        GpuTexture gpu;
        try {
            gpu = texture.getTexture();
        } catch (IllegalStateException notLoaded) {
            return forget(id);
        }
        if (!(gpu instanceof GlTexture gl) || gpu.isClosed() || gpu.getFormat() != GpuFormat.RGBA8_UNORM) {
            return forget(id);
        }
        int width = gpu.getWidth(0);
        int height = gpu.getHeight(0);
        if (width <= 0 || height <= 0 || (long) width * height > MAX_TEXELS) {
            return forget(id);
        }
        byte[] data = new byte[width * height * 4];
        ByteBuffer buffer = MemoryUtil.memAlloc(data.length);
        int alignment = GL45C.glGetInteger(GL45C.GL_PACK_ALIGNMENT);
        int rowLength = GL45C.glGetInteger(GL45C.GL_PACK_ROW_LENGTH);
        int skipRows = GL45C.glGetInteger(GL45C.GL_PACK_SKIP_ROWS);
        int skipPixels = GL45C.glGetInteger(GL45C.GL_PACK_SKIP_PIXELS);
        int packBuffer = GL45C.glGetInteger(GL45C.GL_PIXEL_PACK_BUFFER_BINDING);
        try {
            GL45C.glBindBuffer(GL45C.GL_PIXEL_PACK_BUFFER, 0);
            GL45C.glPixelStorei(GL45C.GL_PACK_ALIGNMENT, 1);
            GL45C.glPixelStorei(GL45C.GL_PACK_ROW_LENGTH, 0);
            GL45C.glPixelStorei(GL45C.GL_PACK_SKIP_ROWS, 0);
            GL45C.glPixelStorei(GL45C.GL_PACK_SKIP_PIXELS, 0);
            // Rows come back in the order they were uploaded, which is the image's own.
            GL45C.glGetTextureImage(gl.glId(), 0, GL45C.GL_RGBA, GL45C.GL_UNSIGNED_BYTE, buffer);
            buffer.get(0, data);
        } finally {
            GL45C.glPixelStorei(GL45C.GL_PACK_ALIGNMENT, alignment);
            GL45C.glPixelStorei(GL45C.GL_PACK_ROW_LENGTH, rowLength);
            GL45C.glPixelStorei(GL45C.GL_PACK_SKIP_ROWS, skipRows);
            GL45C.glPixelStorei(GL45C.GL_PACK_SKIP_PIXELS, skipPixels);
            GL45C.glBindBuffer(GL45C.GL_PIXEL_PACK_BUFFER, packBuffer);
            MemoryUtil.memFree(buffer);
        }
        DynamicTextureCache.Pixels pixels =
                new DynamicTextureCache.Pixels(data, width, height, DynamicTextureCache.nextVersion());
        PIXELS.put(id, pixels);
        READ.values().removeIf(id::equals);
        READ.put(gpu, id);
        STALE.remove(id);
        return pixels;
    }

    private static DynamicTextureCache.@Nullable Pixels forget(Identifier id) {
        PIXELS.remove(id);
        READ.values().removeIf(id::equals);
        STALE.remove(id);
        return null;
    }

    /** Drops every copy, as when resources reload and the textures behind them are replaced. */
    public static void clear() {
        PIXELS.clear();
        READ.clear();
        STALE.clear();
    }
}
