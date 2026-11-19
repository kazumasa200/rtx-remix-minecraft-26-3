/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.compat.flashback;

import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.EditorStateManager;
import com.nvidia.rtxremix.minecraft.entity.EntityCaptureVisibility;
import java.util.IdentityHashMap;
import java.util.function.Supplier;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.SemanticVersion;
import net.fabricmc.loader.api.Version;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Flashback's editor exclusions, independent of raster frustum and distance culling. */
public final class FlashbackVisibility {
    private static final Logger LOGGER = LoggerFactory.getLogger(FlashbackVisibility.class);

    private FlashbackVisibility() { }

    public static EntityCaptureVisibility create() {
        return select(FabricLoader.getInstance().getModContainer("flashback")
                .map(mod -> mod.getMetadata().getVersion()).orElse(null));
    }

    static EntityCaptureVisibility select(Version version) {
        if (version == null) return EntityCaptureVisibility.VISIBLE;
        if (supports(version)) return Installed.create();
        LOGGER.warn("Flashback {} is untested; skipping Remix's entity visibility integration. "
                + "Use Flashback 0.43.6 for supported player hiding.", version.getFriendlyString());
        return EntityCaptureVisibility.VISIBLE;
    }

    static boolean supports(Version version) {
        return version instanceof SemanticVersion semantic
                && semantic.getVersionComponentCount() == 3
                && semantic.getVersionComponent(0) == 0
                && semantic.getVersionComponent(1) == 43
                && semantic.getVersionComponent(2) == 6
                && semantic.getPrereleaseKey().isEmpty();
    }

    /** Loaded only after the version gate; no Flashback class is needed when it is absent. */
    static final class Installed implements EntityCaptureVisibility {
        private final Supplier<EditorState> currentState;
        private final IdentityHashMap<EntityType<?>, String> typeKeys = new IdentityHashMap<>();

        Installed(Supplier<EditorState> currentState) {
            this.currentState = currentState;
        }

        static EntityCaptureVisibility create() {
            // getCurrent also selects the export job's editor state.
            return new Installed(EditorStateManager::getCurrent);
        }

        @Override
        public boolean isEntityHidden(Entity entity) {
            EditorState state = currentState.get();
            return state != null && (state.isEntityHidden(entity)
                    || !state.filteredEntities.isEmpty() && state.filteredEntities.contains(
                            typeKeys.computeIfAbsent(entity.getType(),
                                    type -> BuiltInRegistries.ENTITY_TYPE.getKey(type).toString())));
        }

        @Override
        public boolean isStateHidden(boolean avatar) {
            EditorState state = currentState.get();
            return state != null && !(avatar
                    ? state.replayVisuals.renderPlayers : state.replayVisuals.renderEntities);
        }
    }
}
