/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.texture.AtlasAnimationForwarder;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import com.nvidia.rtxremix.minecraft.texture.ParticleAtlasDependencies;
import java.util.List;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import com.nvidia.rtxremix.minecraft.entity.RemixEntitySubmitter;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TextureAtlas.class)
public abstract class SpriteAtlasTextureMixin {

    @Shadow @Final private Identifier location;
    @Shadow private List<TextureAtlasSprite> sprites;
    @Shadow private List<SpriteContents.AnimationState> animatedTexturesStates;

    /**
     * Forwards the frames vanilla is about to draw into the block atlas.
     *
     * <p>The atlas builds one animation state per animated sprite, in sprite
     * order, so walking the animated sprites pairs each with its state.
     */
    @Inject(method = "cycleAnimationFrames", at = @At("RETURN"))
    private void rtxremix$forwardAnimatedSprites(CallbackInfo ci) {
        List<SpriteContents.AnimationState> states = animatedTexturesStates;
        if (states.isEmpty() || !BlockTextureManager.BLOCK_ATLAS_ID.equals(location)) {
            return;
        }
        AtlasAnimationForwarder.beginAtlasUpload(location);
        try {
            int state = 0;
            for (int i = 0, n = sprites.size(); i < n && state < states.size(); i++) {
                TextureAtlasSprite sprite = sprites.get(i);
                if (sprite.isAnimated()) {
                    AtlasAnimationForwarder.addAnimatedSprite(sprite, states.get(state++));
                }
            }
        } finally {
            AtlasAnimationForwarder.endAtlasUpload();
        }
    }

    @Inject(method = "upload", at = @At("RETURN"))
    private void rtxremix$onAtlasUploaded(SpriteLoader.Preparations stitchResult, CallbackInfo ci) {
        RemixEntitySubmitter.onMinecraftAtlasUploaded(location);
        ParticleAtlasDependencies.uploaded(location);
    }
}
