/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.compat.immediatelyfast;

import java.util.List;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.SemanticVersion;
import net.fabricmc.loader.api.Version;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Optional internal API references stay behind the supported-version gate. */
public final class ImmediatelyFastMixinPlugin implements IMixinConfigPlugin {
    private static final Logger LOGGER = LoggerFactory.getLogger(ImmediatelyFastMixinPlugin.class);
    private boolean supported;

    @Override public void onLoad(String mixinPackage) {
        FabricLoader.getInstance().getModContainer("immediatelyfast").ifPresent(mod -> {
            Version version = mod.getMetadata().getVersion();
            supported = supports(version);
            if (!supported) {
                LOGGER.warn("ImmediatelyFast {} is untested; skipping Remix's map/sign compatibility hooks. "
                        + "Map/sign capture may be incorrect. Use ImmediatelyFast 1.17.1 for supported integration. "
                        + "General glyph capture and ImmediatelyFast's own optimizations remain enabled.",
                        version.getFriendlyString());
            }
        });
    }

    /** Build metadata such as +26.3 does not change the supported API version. */
    static boolean supports(Version version) {
        return version instanceof SemanticVersion semantic
                && semantic.getVersionComponentCount() == 3
                && semantic.getVersionComponent(0) == 1
                && semantic.getVersionComponent(1) == 17
                && semantic.getVersionComponent(2) == 1
                && semantic.getPrereleaseKey().isEmpty();
    }

    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) { return supported; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
