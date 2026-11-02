/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import sun.misc.Unsafe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.mojang.blaze3d.platform.NativeImage;

/**
 * Pins how glyph pages are served beside the snapshot cache and which snapshots survive
 * the world clear. A page has no snapshot to capture: it is read live from its mirror,
 * and it belongs to the font rather than to a world. A snapshot follows its texture: it
 * survives while the texture is registered and goes when the texture does.
 */
class DynamicTextureCacheTest {

    @Test
    void aGlyphPageIsReadLiveAndOutlivesClear() {
        Identifier id = Identifier.fromNamespaceAndPath("minecraft", "default/0");
        GlyphPageMirror page = new GlyphPageMirror(256, 256, false);
        DynamicTextureCache.onRegisterGlyphPage(id, newTexture(), page);

        assertTrue(DynamicTextureCache.isGlyphPage(id));
        DynamicTextureCache.Pixels empty = DynamicTextureCache.get(id);
        assertSame(page.data(), empty.data());
        assertEquals(256, empty.width());
        assertEquals(256, empty.height());
        assertEquals(0L, empty.version());

        page.blit(0, 0, 1, 1, 4, ByteBuffer.wrap(new byte[] {1, 2, 3, 4}), 4);
        DynamicTextureCache.Pixels written = DynamicTextureCache.get(id);
        assertSame(page.data(), written.data(), "the mirror is read through, not copied");
        assertEquals(page.version(), written.version());

        DynamicTextureCache.clear();
        assertTrue(DynamicTextureCache.isGlyphPage(id));
        assertSame(page.data(), DynamicTextureCache.get(id).data());
    }

    @Test
    void aPageReRegisteredUnderItsIdReplacesItsPredecessor() {
        Identifier id = Identifier.fromNamespaceAndPath("minecraft", "default/1");
        GlyphPageMirror replaced = new GlyphPageMirror(256, 256, false);
        GlyphPageMirror fresh = new GlyphPageMirror(256, 256, true);
        DynamicTextureCache.onRegisterGlyphPage(id, newTexture(), replaced);

        DynamicTextureCache.onRegisterGlyphPage(id, newTexture(), fresh);

        assertSame(fresh.data(), DynamicTextureCache.get(id).data());
    }

    @Test
    void clearKeepsTheSnapshotAndRegistrationOfALiveTexture() {
        Identifier id = Identifier.fromNamespaceAndPath("minecraft", "skins/live");
        AbstractTexture texture = newTexture();
        DynamicTextureCache.onRegisterTexture(id, texture, null);
        try (NativeImage image = new NativeImage(1, 1, true)) {
            assertTrue(DynamicTextureCache.captureFromUpload(texture, image));
        }
        DynamicTextureCache.Pixels captured = DynamicTextureCache.get(id);
        assertNotNull(captured);

        DynamicTextureCache.clear();

        DynamicTextureCache.Pixels kept = DynamicTextureCache.get(id);
        assertNotNull(kept, "a skin is uploaded once per session and cannot be re-captured");
        assertSame(captured.data(), kept.data());
        assertEquals(captured.version(), kept.version());
        try (NativeImage image = new NativeImage(1, 1, true)) {
            assertTrue(DynamicTextureCache.captureFromUpload(texture, image),
                    "a registration belongs to the live texture, not to the cleared snapshots");
        }
    }

    @Test
    void clearDropsAClosedImageTextureWhileItIsStillReachable() {
        // The texture stays strongly reachable, as one the TextureManager keeps
        // registered is, so only its closed image can tell the cache it is gone.
        Identifier id = Identifier.fromNamespaceAndPath("minecraft", "dynamic/closed-map");
        DynamicTexture texture = imageTexture(new NativeImage(1, 1, true));
        DynamicTextureCache.onRegisterTexture(id, texture, texture.getPixels());
        assertNotNull(DynamicTextureCache.get(id));

        texture.close();
        assertTrue(texture.getPixels().isClosed());
        DynamicTextureCache.clear();

        assertNull(DynamicTextureCache.get(id));
    }

    @Test
    void clearKeepsALiveImageTextureWhenAClosedTextureSharesItsId() {
        Identifier id = Identifier.fromNamespaceAndPath("minecraft", "dynamic/replaced-map");
        DynamicTexture closed = imageTexture(new NativeImage(1, 1, true));
        DynamicTextureCache.onRegisterTexture(id, closed, closed.getPixels());
        closed.close();
        try (DynamicTexture live = imageTexture(new NativeImage(1, 1, true))) {
            DynamicTextureCache.onRegisterTexture(id, live, live.getPixels());
            DynamicTextureCache.Pixels captured = DynamicTextureCache.get(id);

            DynamicTextureCache.clear();

            assertSame(captured, DynamicTextureCache.get(id));
        }
    }

    @Test
    void capacityPruningDropsClosedImageTexturesWithoutWaitingForCollection() {
        Identifier closedId = Identifier.fromNamespaceAndPath("minecraft", "dynamic/pruned-map");
        DynamicTexture closed = imageTexture(new NativeImage(1, 1, true));
        DynamicTextureCache.onRegisterTexture(closedId, closed, closed.getPixels());
        closed.close();
        List<AbstractTexture> live = new ArrayList<>();
        for (int i = 0; i < DynamicTextureCache.capacity(); i++) {
            AbstractTexture texture = newTexture();
            live.add(texture);
            Identifier id = Identifier.fromNamespaceAndPath("minecraft", "skins/closed-map-neighbour-" + i);
            try (NativeImage image = new NativeImage(1, 1, true)) {
                DynamicTextureCache.onRegisterTexture(id, texture, image);
            }
        }

        assertTrue(closed.getPixels().isClosed());
        assertNull(DynamicTextureCache.get(closedId));
        assertEquals(DynamicTextureCache.capacity(), live.size());
    }

    /**
     * Relies on the JVM collecting an unreachable texture; the assumption below skips the
     * test rather than failing it when a bounded {@code System.gc()} loop does not.
     */
    @Test
    void clearDropsTheSnapshotOfATextureTheGameReleased() throws InterruptedException {
        Identifier id = Identifier.fromNamespaceAndPath("minecraft", "skins/released");
        AbstractTexture texture = newTexture();
        DynamicTextureCache.onRegisterTexture(id, texture, null);
        try (NativeImage image = new NativeImage(1, 1, true)) {
            assertTrue(DynamicTextureCache.captureFromUpload(texture, image));
        }
        assertNotNull(DynamicTextureCache.get(id));

        WeakReference<AbstractTexture> collected = new WeakReference<>(texture);
        texture = null;
        for (int attempt = 0; attempt < 20 && collected.get() != null; attempt++) {
            System.gc();
            Thread.sleep(10L);
        }
        assumeTrue(collected.get() == null, "the JVM did not collect the released texture");

        DynamicTextureCache.clear();

        assertNull(DynamicTextureCache.get(id),
                "a snapshot vanilla can no longer refresh has nothing to keep it alive");
    }

    @Test
    void unknownIdsHaveNoPixels() {
        Identifier id = Identifier.fromNamespaceAndPath("minecraft", "textures/entity/cow/cow.png");

        assertNull(DynamicTextureCache.get(id));
        assertFalse(DynamicTextureCache.isGlyphPage(id));
    }

    @Test
    void aResizedGlyphPageReportsActualDimensionsAndReleasesItsPixels() {
        Identifier id = Identifier.fromNamespaceAndPath("minecraft", "compat-test/resized-font");
        AbstractTexture texture = newTexture();
        GlyphPageMirror page = new GlyphPageMirror(1024, 512, false);
        DynamicTextureCache.onRegisterGlyphPage(id, texture, page);
        DynamicTextureCache.Pixels pixels = DynamicTextureCache.get(id);
        assertEquals(1024, pixels.width());
        assertEquals(512, pixels.height());
        assertSame(page.data(), pixels.data());
        long generation = DynamicTextureCache.glyphGeneration();

        DynamicTextureCache.onReleaseGlyphPage(texture, page);

        assertNull(DynamicTextureCache.get(id));
        assertFalse(DynamicTextureCache.isGlyphPage(id));
        assertTrue(DynamicTextureCache.glyphGeneration() > generation);
    }

    @Test
    void closingAnOldPageCannotUnregisterTheReplacementWithTheSameId() {
        Identifier id = Identifier.fromNamespaceAndPath("minecraft", "compat-test/reloaded-font");
        AbstractTexture oldTexture = newTexture();
        AbstractTexture newTexture = newTexture();
        GlyphPageMirror oldPage = new GlyphPageMirror(1024, 1024, true);
        GlyphPageMirror newPage = new GlyphPageMirror(256, 256, true);
        DynamicTextureCache.onRegisterGlyphPage(id, oldTexture, oldPage);
        long generation = DynamicTextureCache.glyphGeneration();
        DynamicTextureCache.onRegisterGlyphPage(id, newTexture, newPage);
        assertTrue(DynamicTextureCache.glyphGeneration() > generation);

        DynamicTextureCache.onReleaseGlyphPage(oldTexture, oldPage);
        DynamicTextureCache.onReleaseGlyphPage(oldTexture, oldPage);

        assertSame(newPage.data(), DynamicTextureCache.get(id).data());
        assertEquals(256, DynamicTextureCache.get(id).width());
        DynamicTextureCache.onReleaseGlyphPage(newTexture, newPage);
        assertNull(DynamicTextureCache.get(id));
    }

    /**
     * A downloaded skin uploads once per session, so a snapshot the capacity
     * bound dropped would be gone until restart. Every texture here stays
     * strongly referenced, so none has been released and none may be pruned.
     */
    @Test
    void capacityPruningNeverDropsTheSnapshotOfALiveTexture() {
        int count = DynamicTextureCache.capacity() + 8;
        List<AbstractTexture> live = new ArrayList<>(count);
        List<Identifier> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Identifier id = Identifier.fromNamespaceAndPath("minecraft", "skins/crowd-" + i);
            AbstractTexture texture = newTexture();
            live.add(texture);
            ids.add(id);
            DynamicTextureCache.onRegisterTexture(id, texture, null);
            try (NativeImage image = new NativeImage(1, 1, true)) {
                assertTrue(DynamicTextureCache.captureFromUpload(texture, image));
            }
        }

        for (Identifier id : ids) {
            assertNotNull(DynamicTextureCache.get(id), id + " was pruned while its texture is live");
        }
        assertEquals(count, live.size());
    }

    private static AbstractTexture newTexture() {
        return new AbstractTexture() {};
    }

    /**
     * A {@link DynamicTexture} holding {@code pixels}. Every constructor creates the GPU
     * texture on RenderSystem's device, which a unit test has none of, and the cache reads
     * nothing of the texture but its image, so the texture is allocated without running a
     * constructor and handed the image directly.
     */
    private static DynamicTexture imageTexture(NativeImage pixels) {
        try {
            Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            DynamicTexture texture = (DynamicTexture) ((Unsafe) theUnsafe.get(null))
                    .allocateInstance(DynamicTexture.class);
            Field image = DynamicTexture.class.getDeclaredField("pixels");
            image.setAccessible(true);
            image.set(texture, pixels);
            return texture;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
