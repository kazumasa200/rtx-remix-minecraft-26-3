/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.lod;

import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.MapColor;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The colours of blocks for LOD terrain this mod builds itself: each face's sprite averaged
 * ({@link BlockTextureManager#spriteColors}), times the block's tint where its model tints that
 * face, the tint taken for the biome the LOD mod recorded there. A tint source reads the biome
 * through the {@link BlockAndTintGetter} it is handed, as Minecraft's own do through a
 * {@link ColorResolver}, so a modded block's tint follows the biome too.
 *
 * <p>One per builder thread: its caches are not shared.
 */
final class LodBlockColors {

    /** A block's face sprites and their tint layers, or -1 for an untinted face. */
    private record Faces(int up, int side, int down, int upTint, int sideTint, int downTint,
                         boolean translucent, boolean skipped) {}

    private final BlockTextureManager.SpriteColors sprites;
    private final Map<BlockState, Faces> faces = new HashMap<>();
    private final Map<TintKey, int[]> tinted = new HashMap<>();
    private final List<BlockStateModelPart> parts = new ObjectArrayList<>();
    private final SingleThreadedRandomSource random = new SingleThreadedRandomSource(0L);
    private final BiomeTint biomeTint = new BiomeTint();
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    private record TintKey(BlockState state, Object biome) {}

    LodBlockColors(BlockTextureManager.SpriteColors sprites) {
        this.sprites = sprites;
    }

    BlockTextureManager.SpriteColors sprites() {
        return sprites;
    }

    /**
     * Whether the LOD leaves the block out: one with nothing to stand on and no light, such as
     * grass, flowers or a rail, which drawn as a whole cell would bury the ground in boxes.
     */
    boolean skipped(BlockState state) {
        return faces(state).skipped;
    }

    /** Whether the block's sprites read as see-through, as glass and ice do. */
    boolean translucent(BlockState state) {
        return faces(state).translucent;
    }

    /**
     * The block's top, side and bottom colours where it stands in {@code biome}, a
     * {@code Holder<Biome>} or a {@link Biome}; null when the biome is unknown, which leaves
     * tinted faces at their default tint.
     */
    int[] colors(BlockState state, @Nullable Object biome, int x, int z) {
        Faces f = faces(state);
        boolean tints = f.upTint >= 0 || f.sideTint >= 0 || f.downTint >= 0;
        TintKey key = new TintKey(state, tints ? biome : null);
        int[] cached = tinted.get(key);
        if (cached != null) return cached;
        Biome resolved = biome instanceof net.minecraft.core.Holder<?> holder && holder.value() instanceof Biome b
                ? b : biome instanceof Biome b ? b : null;
        pos.set(x, 64, z);
        int[] out = {
                tint(state, f.up, f.upTint, resolved),
                tint(state, f.side, f.sideTint, resolved),
                tint(state, f.down, f.downTint, resolved)};
        tinted.put(key, out);
        return out;
    }

    private int tint(BlockState state, int argb, int tintIndex, @Nullable Biome biome) {
        if (tintIndex < 0) return argb;
        BlockColors colors = Minecraft.getInstance().getBlockColors();
        BlockTintSource source = colors.getTintSource(state, tintIndex);
        if (source == null) return argb;
        int tint;
        try {
            biomeTint.biome = biome;
            tint = biome != null ? source.colorInWorld(state, biomeTint, pos) : source.color(state);
        } catch (RuntimeException e) {
            tint = source.color(state);
        }
        return multiply(argb, tint);
    }

    static int multiply(int argb, int tint) {
        int r = (argb >> 16 & 0xFF) * (tint >> 16 & 0xFF) / 255;
        int g = (argb >> 8 & 0xFF) * (tint >> 8 & 0xFF) / 255;
        int b = (argb & 0xFF) * (tint & 0xFF) / 255;
        return argb & 0xFF000000 | r << 16 | g << 8 | b;
    }

    private Faces faces(BlockState state) {
        Faces f = faces.get(state);
        if (f != null) return f;
        f = lookUp(state);
        faces.put(state, f);
        return f;
    }

    private Faces lookUp(BlockState state) {
        int fallback = 0xFF000000 | mapColor(state);
        int[] face = {fallback, fallback, fallback};
        int[] tints = {-1, -1, -1};
        boolean translucent = false;
        Direction[] wanted = {Direction.UP, Direction.NORTH, Direction.DOWN};
        boolean[] found = new boolean[3];
        try {
            BlockStateModel model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state);
            random.setSeed(42L);
            model.collectParts(random, parts);
            for (int w = 0; w < 3; w++) {
                for (int p = 0; p < parts.size() && !found[w]; p++) {
                    found[w] = take(parts.get(p).getQuads(wanted[w]), w, face, tints);
                }
            }
            // A model without culled faces, such as a slab's or a fence's, has its quads unsided.
            for (int p = 0; p < parts.size(); p++) {
                List<BakedQuad> loose = parts.get(p).getQuads(null);
                for (int w = 0; w < 3; w++) {
                    if (!found[w]) found[w] = take(loose, w, face, tints);
                }
            }
            for (int p = 0; p < parts.size() && !translucent; p++) {
                List<BakedQuad> quads = parts.get(p).getQuads(Direction.UP);
                if (!quads.isEmpty() && sprites.translucent().contains(sprite(quads.get(0)))) translucent = true;
            }
        } catch (RuntimeException e) {
            // A model that will not answer here keeps its map colour.
        } finally {
            parts.clear();
        }
        boolean skipped;
        try {
            skipped = state.getLightEmission() == 0
                    && state.getCollisionShape(BlockAndTintGetter.EMPTY, BlockPos.ZERO).isEmpty();
        } catch (RuntimeException e) {
            skipped = false;
        }
        return new Faces(face[0], face[1], face[2], tints[0], tints[1], tints[2], translucent, skipped);
    }

    private boolean take(List<BakedQuad> quads, int slot, int[] face, int[] tints) {
        for (BakedQuad quad : quads) {
            Integer argb = sprites.argb().get(sprite(quad));
            if (argb == null) continue;
            BakedQuad.MaterialInfo material = quad.materialInfo();
            face[slot] = argb | 0xFF000000;
            tints[slot] = material.isTinted() ? material.tintIndex() : -1;
            return true;
        }
        return false;
    }

    private static net.minecraft.resources.Identifier sprite(BakedQuad quad) {
        TextureAtlasSprite sprite = quad.materialInfo().sprite();
        return sprite != null ? sprite.contents().name() : null;
    }

    private static int mapColor(BlockState state) {
        try {
            MapColor color = state.getMapColor(BlockAndTintGetter.EMPTY, BlockPos.ZERO);
            return color != null ? color.col : 0x808080;
        } catch (RuntimeException e) {
            return 0x808080;
        }
    }

    /** Answers a tint source's biome lookups with one biome and nothing else. */
    private static final class BiomeTint implements BlockAndTintGetter {
        @Nullable Biome biome;

        @Override
        public int getBlockTint(BlockPos at, ColorResolver resolver) {
            return biome != null ? resolver.getColor(biome, at.getX(), at.getZ()) : -1;
        }

        @Override
        public CardinalLighting cardinalLighting() {
            return BlockAndTintGetter.EMPTY.cardinalLighting();
        }

        @Override
        public LevelLightEngine getLightEngine() {
            return LevelLightEngine.EMPTY;
        }

        @Override
        public @Nullable BlockEntity getBlockEntity(BlockPos at) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos at) {
            return BlockAndTintGetter.EMPTY.getBlockState(at);
        }

        @Override
        public FluidState getFluidState(BlockPos at) {
            return BlockAndTintGetter.EMPTY.getFluidState(at);
        }

        @Override
        public int getHeight() {
            return 0;
        }

        @Override
        public int getMinY() {
            return 0;
        }
    }
}
