/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.compat.figura;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nvidia.rtxremix.minecraft.entity.RemixVertexConsumerProvider;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.figuramc.figura.ducks.FiguraSubmitCallBackExtension;
import org.figuramc.figura.model.rendering.nodeRenderer.FiguraSubmission;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * What vanilla's submit storage does for Figura, done for the Remix capture.
 * Figura leaves callbacks on a model that hide its vanilla parts, which vanilla's
 * model feature renderer runs around the draw, and hands its avatar to vanilla's
 * storage alone. Call only where {@link FiguraMixinPlugin#INSTALLED}.
 */
public final class FiguraCapture {

    private FiguraCapture() {
    }

    public static boolean preventsAnimSetup(Model<?> model) {
        return ((FiguraSubmitCallBackExtension) model).figura$getPreventAnimSetup();
    }

    /** Runs the callbacks Figura left for before the draw; false when one of them cancels it. */
    public static boolean beforeDraw(Model<?> model, VertexConsumer buffer, PoseStack poseStack) {
        List<BiFunction<VertexConsumer, PoseStack, Boolean>> callbacks =
                ((FiguraSubmitCallBackExtension) model).figura$getPreRenderingCallbacks();
        boolean draw = true;
        for (int i = 0; i < callbacks.size(); i++) {
            draw &= callbacks.get(i).apply(buffer, poseStack);
        }
        callbacks.clear();
        return draw;
    }

    public static void afterDraw(Model<?> model) {
        FiguraSubmitCallBackExtension extension = (FiguraSubmitCallBackExtension) model;
        List<Runnable> callbacks = extension.figura$getPostRenderingCallbacks();
        for (int i = 0; i < callbacks.size(); i++) {
            callbacks.get(i).run();
        }
        callbacks.clear();
        extension.figura$setPreventAnimSetup(false);
    }

    /** Draws an avatar's buffered parts and then its queued tasks (text, sprites), in Figura's own order. */
    public static void draw(RemixVertexConsumerProvider provider, FiguraSubmission submission) {
        draw(provider, submission.primaryBuffers());
        draw(provider, submission.secondaryBuffers());
        List<FiguraSubmission.QueuedRenderTask> tasks = submission.renderTasks();
        for (int i = 0; i < tasks.size(); i++) {
            FiguraSubmission.QueuedRenderTask queued = tasks.get(i);
            queued.task().render(queued.poseStack(), provider::getBuffer, queued.light(), queued.overlay());
        }
    }

    private static void draw(RemixVertexConsumerProvider provider,
                             Map<RenderType, List<Consumer<VertexConsumer>>> buffers) {
        for (Map.Entry<RenderType, List<Consumer<VertexConsumer>>> entry : buffers.entrySet()) {
            if (entry.getKey().isOutline()) {
                continue;
            }
            VertexConsumer buffer = provider.getBuffer(entry.getKey());
            for (Consumer<VertexConsumer> part : entry.getValue()) {
                part.accept(buffer);
            }
        }
    }
}
