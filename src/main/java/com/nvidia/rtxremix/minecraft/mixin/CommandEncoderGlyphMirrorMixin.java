/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import com.nvidia.rtxremix.minecraft.texture.GlyphPageMirror;
import com.nvidia.rtxremix.minecraft.texture.GpuTextureReadback;
import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Copies a glyph upload into its page mirror where its pixels meet the GPU.
 *
 * <p>Every glyph reaches its page through one of three command-encoder writes: a whole
 * {@link NativeImage} for TrueType and built-in glyphs, a packed buffer for Unihex, and a
 * staging-buffer copy for bitmap fonts. Only a write inside a glyph upload scope
 * ({@code GlyphAtlasTextureMixin}) is mirrored, so every other texture write in the game
 * costs one static read.
 */
@Mixin(FrontendCommandEncoder.class)
public abstract class CommandEncoderGlyphMirrorMixin {

    @Inject(method = "writeToTexture(Lcom/mojang/renderpearl/api/textures/GpuTexture;Lcom/mojang/blaze3d/platform/NativeImage;IIII)V",
            at = @At("HEAD"))
    private void rtxremix$mirrorImageGlyph(GpuTexture destination, NativeImage source, int mipLevel,
                                           int depthOrLayer, int destX, int destY, CallbackInfo ci) {
        GpuTextureReadback.written(destination);
        GlyphPageMirror page = GlyphPageMirror.uploadTarget();
        if (page == null || mipLevel != 0) {
            return;
        }
        int width = source.getWidth();
        int height = source.getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        int channels = source.format().components();
        int rowPitch = width * channels;
        page.blit(destX, destY, width, height, channels,
                MemoryUtil.memByteBuffer(source.getPointer(), height * rowPitch), rowPitch);
    }

    @Inject(method = "writeToTexture(Lcom/mojang/renderpearl/api/textures/GpuTexture;Ljava/nio/ByteBuffer;IIIIII)V",
            at = @At("HEAD"))
    private void rtxremix$mirrorBufferGlyph(GpuTexture destination, ByteBuffer source, int mipLevel,
                                            int depthOrLayer, int destX, int destY, int width, int height,
                                            CallbackInfo ci) {
        GpuTextureReadback.written(destination);
        GlyphPageMirror page = GlyphPageMirror.uploadTarget();
        if (page == null || mipLevel != 0 || width <= 0 || height <= 0) {
            return;
        }
        // Rows are packed at the write's width, as the GPU reads them.
        int channels = destination.getFormat().componentCount();
        page.blit(destX, destY, width, height, channels, source, width * channels);
    }

    @Inject(method = "copyBufferToTexture", at = @At("HEAD"))
    private void rtxremix$mirrorStagedGlyph(GpuBufferSlice source, int sourceX, int sourceY,
                                            int sourceWidth, int sourceHeight, GpuTexture destination,
                                            int destinationX, int destinationY, int copyWidth, int copyHeight,
                                            int mipLevel, int arrayLayer, CallbackInfo ci) {
        GpuTextureReadback.written(destination);
        if (mipLevel == 0) {
            GlyphPageMirror.blitFromStaging(source.buffer(), sourceX, sourceY,
                    destinationX, destinationY, copyWidth, copyHeight);
        }
    }
}
