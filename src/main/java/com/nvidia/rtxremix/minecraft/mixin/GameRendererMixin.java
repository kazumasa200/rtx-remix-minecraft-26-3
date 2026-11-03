/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.nvidia.rtxremix.minecraft.RemixRenderer;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.OptionsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Moves the first-person arm/item draw into Remix while preserving Minecraft's
 * own first-person animation and selection logic, brackets the GUI tail of its
 * render method with a profiling scope, and has input capture follow the Remix
 * menus at the start of each frame.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @WrapOperation(
            method = "renderItemInHand",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/FirstPersonHandsAndItemsRenderer;submitHandsWithItems(FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/level/FirstPersonHandsAndItemsRenderState;)V"
            ))
    private void rtxremix$captureFirstPersonModel(
            FirstPersonHandsAndItemsRenderer handsRenderer,
            float partialTicks,
            PoseStack poseStack,
            SubmitNodeCollector collector,
            PlayerRenderState playerState,
            FirstPersonHandsAndItemsRenderState handsState,
            Operation<Void> original,
            CameraRenderState cameraState) {
        RemixRenderer renderer = RtxRemixMod.renderer;
        if (!RtxRemixMod.shouldCaptureFirstPersonModel()) {
            original.call(handsRenderer, partialTicks, poseStack, collector, playerState, handsState);
            return;
        }

        renderer.captureFirstPersonModel(handsRenderer, partialTicks, poseStack, playerState, handsState, cameraState);
    }

    /**
     * Right after the level render, including draws other mods inject at its return, and
     * before the first-person model.
     */
    @Inject(method = "render3dHud", at = @At("HEAD"))
    private void rtxremix$captureModOverlay(CallbackInfo ci) {
        RtxRemixMod.captureModOverlay();
    }

    @Inject(
            method = "render3dHud",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/ScreenEffectRenderer;submit(FLnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/level/CameraRenderState;Z)V",
                    shift = At.Shift.BEFORE
            ))
    private void rtxremix$submitBeforeScreenOverlays(
            CameraRenderState cameraState,
            PlayerRenderState playerState,
            OptionsRenderState optionsState,
            boolean consistentDepthRequired,
            CallbackInfo ci) {
        RtxRemixMod.submitRemixFrameIfReady();
    }

    /**
     * Covers panorama and any other path where the 3D HUD's screen effects are
     * skipped.
     */
    @Inject(method = "renderLevel", at = @At("TAIL"))
    private void rtxremix$submitAtRenderWorldTail(CallbackInfo ci) {
        RtxRemixMod.submitRemixFrameIfReady();
    }

    /**
     * The GUI tail of GameRenderer.render: HUD, in-game screen or overlay, toasts,
     * and the final GUI flush. WorldRender.END accounts for the world half of the
     * frame.
     */
    private ProfileScope rtxremix$guiScope;

    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/render/GuiRenderer;render()V"
            ))
    private void rtxremix$beginGuiScope(CallbackInfo ci) {
        rtxremix$closeGuiScope();
        if (ProfileScope.isJavaScopeEnabled()) {
            rtxremix$guiScope = ProfileScope.begin("GameRenderer.render.gui")
                    .plotOnClose("Java.GameRenderer.render.gui.ms");
        }
    }

    /**
     * Before the frame's GUI is extracted, so the screens' hover state already
     * matches input capture when they are drawn.
     */
    @Inject(method = "extract", at = @At("HEAD"))
    private void rtxremix$followRemixMenuVisibility(DeltaTracker deltaTracker, boolean advanceGameTime,
                                                    CallbackInfo ci) {
        RtxRemixMod.followRemixMenuVisibility();
        RtxRemixMod.advanceSceneOrigin();
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void rtxremix$endGuiScope(CallbackInfo ci) {
        rtxremix$closeGuiScope();
    }

    private void rtxremix$closeGuiScope() {
        ProfileScope scope = rtxremix$guiScope;
        if (scope != null) {
            rtxremix$guiScope = null;
            scope.close();
        }
    }
}
