/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.compat.flashback;

import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.visuals.ReplayVisuals;
import com.mojang.authlib.GameProfile;
import com.nvidia.rtxremix.minecraft.entity.EntityCaptureVisibility;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.loader.api.Version;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.GameType;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import static org.junit.jupiter.api.Assertions.*;

class FlashbackVisibilityTest {
    static {
        try {
            SharedConstants.getCurrentVersion();
        } catch (IllegalStateException unset) {
            SharedConstants.tryDetectVersion();
        }
        Bootstrap.bootStrap();
    }

    @Test
    void uuidHideAndShowDoNotAffectAnotherPlayerOrAnEntityWithTheSameNumericId() throws Exception {
        EditorState state = state();
        var visibility = new FlashbackVisibility.Installed(() -> state);
        Entity target = player(GameType.SURVIVAL);
        Entity other = player(GameType.SURVIVAL);
        target.setId(7);
        other.setId(7);

        assertFalse(visibility.isEntityHidden(target));
        state.hideDuringExport.add(target.getUUID());
        assertTrue(visibility.isEntityHidden(target));
        assertFalse(visibility.isEntityHidden(other));
        state.hideDuringExport.remove(target.getUUID());
        assertFalse(visibility.isEntityHidden(target));
    }

    @Test
    void spectatorAndEntityTypeFiltersFollowTheCurrentEditorState() throws Exception {
        EditorState state = state();
        var visibility = new FlashbackVisibility.Installed(() -> state);
        Entity spectator = player(GameType.SPECTATOR);
        Entity survival = player(GameType.SURVIVAL);
        Entity stand = allocate(ArmorStand.class);
        Field type = Entity.class.getDeclaredField("type");
        type.setAccessible(true);
        type.set(stand, EntityTypes.ARMOR_STAND);
        stand.setUUID(UUID.randomUUID());

        state.hideAllSpectators = true;
        assertTrue(visibility.isEntityHidden(spectator));
        assertFalse(visibility.isEntityHidden(survival));
        assertFalse(visibility.isEntityHidden(stand));
        state.hideAllSpectators = false;
        assertFalse(visibility.isEntityHidden(spectator));

        state.filteredEntities.add("minecraft:armor_stand");
        assertTrue(visibility.isEntityHidden(stand));
        assertFalse(visibility.isEntityHidden(survival));
        state.filteredEntities.clear();
        assertFalse(visibility.isEntityHidden(stand));
    }

    @Test
    void globalAvatarAndEntitySwitchesAreIndependentAndReversible() throws Exception {
        EditorState state = state();
        var visibility = new FlashbackVisibility.Installed(() -> state);
        assertFalse(visibility.isStateHidden(true));
        assertFalse(visibility.isStateHidden(false));
        state.replayVisuals.renderPlayers = false;
        assertTrue(visibility.isStateHidden(true));
        assertFalse(visibility.isStateHidden(false));
        state.replayVisuals.renderPlayers = true;
        state.replayVisuals.renderEntities = false;
        assertFalse(visibility.isStateHidden(true));
        assertTrue(visibility.isStateHidden(false));
        state.replayVisuals.renderEntities = true;
        assertFalse(visibility.isStateHidden(false));
    }

    @Test
    void readsTheSelectedStateAgainWhenPreviewExportOrReplaySessionChanges() throws Exception {
        var current = new AtomicReference<EditorState>();
        var visibility = new FlashbackVisibility.Installed(current::get);
        Entity target = player(GameType.SURVIVAL);
        assertFalse(visibility.isEntityHidden(target));
        assertFalse(visibility.isStateHidden(true));
        EditorState preview = state();
        preview.hideDuringExport.add(target.getUUID());
        current.set(preview);
        assertTrue(visibility.isEntityHidden(target));
        EditorState export = state();
        export.replayVisuals.renderEntities = false;
        current.set(export);
        assertFalse(visibility.isEntityHidden(target));
        assertTrue(visibility.isStateHidden(false));
        current.set(null);
        assertFalse(visibility.isEntityHidden(target));
        assertFalse(visibility.isStateHidden(false));
    }

    @Test
    void absentAndUntestedVersionsDoNotResolveFlashbackClasses() throws Exception {
        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("com.moulberry.flashback.")) throw new ClassNotFoundException(name);
                if (!name.startsWith(FlashbackVisibility.class.getName())) return super.loadClass(name, resolve);
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    try (var input = getResourceAsStream(name.replace('.', '/') + ".class")) {
                        if (input == null) throw new ClassNotFoundException(name);
                        byte[] bytes = input.readAllBytes();
                        loaded = defineClass(name, bytes, 0, bytes.length);
                    } catch (java.io.IOException error) {
                        throw new ClassNotFoundException(name, error);
                    }
                }
                if (resolve) resolveClass(loaded);
                return loaded;
            }
        };
        Class<?> adapter = loader.loadClass(FlashbackVisibility.class.getName());
        var select = adapter.getDeclaredMethod("select", Version.class);
        select.setAccessible(true);
        assertSame(EntityCaptureVisibility.VISIBLE, select.invoke(null, new Object[]{null}));
        assertSame(EntityCaptureVisibility.VISIBLE, select.invoke(null, Version.parse("0.43.5")));
        assertSame(EntityCaptureVisibility.VISIBLE, select.invoke(null, Version.parse("0.43.7")));
        assertTrue(FlashbackVisibility.supports(Version.parse("0.43.6")));
        assertTrue(FlashbackVisibility.supports(Version.parse("0.43.6+26.3")));
        assertFalse(FlashbackVisibility.supports(Version.parse("0.43.6-beta")));
    }

    private static EditorState state() throws Exception {
        // Its constructor reads Flashback's live client config. Only the editor data
        // used by this adapter is needed in a JVM test without Fabric or a client.
        EditorState state = allocate(EditorState.class);
        state.hideDuringExport = new HashSet<>();
        state.filteredEntities = new HashSet<>();
        Field visuals = EditorState.class.getDeclaredField("replayVisuals");
        visuals.setAccessible(true);
        visuals.set(state, new ReplayVisuals());
        return state;
    }

    private static TestPlayer player(GameType mode) throws Exception {
        TestPlayer player = allocate(TestPlayer.class);
        player.mode = mode;
        player.setUUID(UUID.randomUUID());
        Field type = Entity.class.getDeclaredField("type");
        type.setAccessible(true);
        type.set(player, EntityTypes.PLAYER);
        return player;
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(((Unsafe) field.get(null)).allocateInstance(type));
    }

    private static final class TestPlayer extends RemotePlayer {
        private GameType mode;

        private TestPlayer() {
            super(null, new GameProfile(UUID.randomUUID(), "test"));
        }

        @Override public GameType gameMode() { return mode; }
    }
}
