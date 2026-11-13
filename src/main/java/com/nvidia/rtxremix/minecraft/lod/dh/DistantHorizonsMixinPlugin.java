/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.lod.dh;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.SemanticVersion;
import net.fabricmc.loader.api.Version;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;

/**
 * Applies the Distant Horizons capture mixins only to the Distant Horizons release they were
 * written against. They reach into its renderer's internals, which any release may change, and
 * a mixin that no longer fits stops the game while it loads. On another release the LOD terrain
 * is not captured and a warning says so.
 */
public final class DistantHorizonsMixinPlugin implements IMixinConfigPlugin {

    private static final Logger LOGGER = LoggerFactory.getLogger(DistantHorizonsMixinPlugin.class);

    /** Whether the installed Distant Horizons is the supported release; false when it is absent. */
    public static final boolean SUPPORTED = detect();

    /** Whether another release of Distant Horizons is installed, which draws for itself. */
    public static final boolean UNTESTED =
            !SUPPORTED && FabricLoader.getInstance().isModLoaded("distanthorizons");

    private static boolean detect() {
        return FabricLoader.getInstance().getModContainer("distanthorizons").map(mod -> {
            Version version = mod.getMetadata().getVersion();
            boolean supported = supports(version);
            if (!supported) {
                LOGGER.warn("Distant Horizons {} is untested; its renderer is not captured. LOD terrain is "
                        + "built from its public data instead, nearer and without its textures. "
                        + "Use Distant Horizons 3.3.4 for the full LOD terrain.", version.getFriendlyString());
            }
            return supported;
        }).orElse(false);
    }

    /** Build metadata such as +26.3 does not change the supported release. */
    static boolean supports(Version version) {
        return version instanceof SemanticVersion semantic
                && semantic.getVersionComponentCount() == 3
                && semantic.getVersionComponent(0) == 3
                && semantic.getVersionComponent(1) == 3
                && semantic.getVersionComponent(2) == 4
                && semantic.getPrereleaseKey().isEmpty();
    }

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return SUPPORTED;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
