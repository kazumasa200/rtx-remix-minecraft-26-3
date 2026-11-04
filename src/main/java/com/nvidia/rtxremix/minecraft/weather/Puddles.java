/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.weather;

import com.nvidia.rtxremix.HardcodedVertex;
import com.nvidia.rtxremix.InstanceCategoryBit;
import com.nvidia.rtxremix.InstanceInfoBlendEXT;
import com.nvidia.rtxremix.MaterialInfo;
import com.nvidia.rtxremix.MaterialInfoOpaqueEXT;
import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.TextureBatchEntry;
import com.nvidia.rtxremix.TextureFormat;
import com.nvidia.rtxremix.TexturePatch;
import com.nvidia.rtxremix.TexturePatchInfo;
import com.nvidia.rtxremix.TexturePurpose;
import com.nvidia.rtxremix.minecraft.asset.RemixAssetBase;
import com.nvidia.rtxremix.minecraft.asset.RemixInstance;
import com.nvidia.rtxremix.minecraft.asset.RemixInstanceStore;
import com.nvidia.rtxremix.minecraft.asset.RemixMaterial;
import com.nvidia.rtxremix.minecraft.asset.RemixMaterialStore;
import com.nvidia.rtxremix.minecraft.asset.RemixMesh;
import com.nvidia.rtxremix.minecraft.asset.RemixMeshStore;
import com.nvidia.rtxremix.minecraft.asset.RemixMeshSurface;
import com.nvidia.rtxremix.minecraft.asset.RemixTexture;
import com.nvidia.rtxremix.minecraft.asset.RemixTextureStore;
import com.nvidia.rtxremix.minecraft.terrain.capture.BlockQuadEmitter;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import com.nvidia.rtxremix.minecraft.texture.WaterNormals;
import com.nvidia.rtxremix.minecraft.util.DirectBufferPool;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import net.fabricmc.fabric.api.client.renderer.v1.Renderer;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.sprite.SpriteFinder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Puddles on open ground in the rain, laid on the block tops as a decal the runtime folds into
 * the ground beneath, blending the ground's colour, reflectance, roughness and normal toward
 * its own by its opacity. Its colour is the ground's own, copied from the block's top texture
 * and tint and a little darker, so the decal shades the ground only slightly: a decal of any
 * other colour darkens the ground by its opacity. Its opacity is how wet the ground is, over
 * most open ground; its roughness texture and metal share bring the reflection where water
 * stands, a smaller part of it, from wet ground's sheen at a puddle's edge to a mirror over its
 * middle, and its normals the raindrop rings. The metal, by the Puddle Reflection setting, is
 * there because water's 4% head-on reflection showed almost nothing over bright ground; it is
 * coloured and darkened as metal is, since lightening it to keep the ground's brightness blew
 * the colour out to white. A clear-water sheet over the
 * ground reflected by its Fresnel term alone, which at a grazing view is strong whatever the
 * index, so it began on a sharp edge however its materials were stepped.
 *
 * <p>Where water stands is the {@link PuddleMask} noise, read at a grid on every block top
 * and recessed from ground that takes no water, under a roof or past the edge of a drop, so a
 * puddle never ends on a straight line where the shelter begins.
 *
 * <p>The decals cover the blocks within {@link #RADIUS} of the camera, in tiles of {@link #TILE}
 * blocks, each with its own texture, material and mesh. They are relaid as the camera moves and
 * soon after a block nearby changes, and a few tiles a frame are retraced as the ground gets
 * wetter or dries, so an edge creeps rather than jumps; past that radius the rain's fog hides
 * them.
 *
 * <p>Render thread only.
 */
public final class Puddles {

    private static final Logger LOGGER = LoggerFactory.getLogger(Puddles.class);

    private static final int RADIUS = 48;
    /** Columns beyond the radius whose shelter still pushes a puddle's edge back. */
    private static final int MARGIN = 2;
    private static final int SPAN = 2 * (RADIUS + MARGIN) + 1;
    private static final int MAX_BLOCKS = (2 * RADIUS + 1) * (2 * RADIUS + 1);
    /** Blocks the camera may move before the decals are relaid around it. */
    private static final int RELAY_DISTANCE = 8;
    /** Nanoseconds between relays while the camera stands still. */
    private static final long RELAY_INTERVAL_NANOS = 10_000_000_000L;
    /** Shortest wait after a block change before relaying, so a busy area relays at a bounded rate. */
    private static final long CHANGE_RELAY_NANOS = 500_000_000L;
    /** Seconds for soaked ground to form in a steady rain, and for it to dry once the rain stops. */
    private static final float WET_SECONDS = 30.0f;
    private static final float DRY_SECONDS = 120.0f;
    /** Change in wetness since a tile was traced that retraces it. */
    private static final float WETNESS_STEP = 1.0f / 128.0f;
    /** Blocks across a tile, the span the puddles' ring normals repeat over. */
    private static final int TILE = WaterNormals.PUDDLE_TILE_BLOCKS;
    /** Tiles across the radius's square at most, part tiles at its sides included. */
    private static final int TILES_ACROSS = (2 * RADIUS + TILE) / TILE + 1;
    private static final int MAX_TILES = TILES_ACROSS * TILES_ACROSS;
    /**
     * Nanoseconds of a frame the retracing may take before it leaves the rest for the next: a
     * soaked tile takes about a millisecond, so one at a time can run past it.
     */
    private static final long RETRACE_BUDGET_NANOS = 1_000_000L;
    /** Texels of a tile's textures per block, along each side. */
    static final int TEXELS = 32;
    private static final int SIZE = TILE * TEXELS;
    /** Depths in the mask over which the ground grows wet, from dry to soaked: most open ground. */
    private static final float WET_EDGE = -1.0f;
    private static final float WET_FULL = 0.3f;
    /**
     * Depths over which water stands, from wet ground's sheen to a mirror: about a fifth of open
     * ground at the default amount. Water over every wet place covered nearly all of it.
     */
    private static final float WATER_EDGE = 0.6f;
    private static final float WATER_FULL = 1.1f;
    /** Depth the Puddle Amount setting moves both bands by, from its middle to either end. */
    private static final float AMOUNT_RANGE = 0.8f;
    /** Roughness wet ground takes where no water stands, a faint sheen. */
    private static final float WET_ROUGHNESS = 0.7f;
    /** Cells across a block top. */
    private static final int GRID = 4;
    private static final int GRID_POINTS = (GRID + 1) * (GRID + 1);
    /** Blocks over which a puddle's edge recedes from sheltered or lower ground. */
    private static final float SHELTER_FADE = 1.25f;
    private static final long SEED = 0x50554444L;
    /** Opacity from the texture's alpha, which the runtime folds into the ground beneath as a decal. */
    private static final InstanceInfoBlendEXT BLEND = InstanceInfoBlendEXT.textureTimesVertexColorAlphaBlend(0);
    private static final int NO_WATER = Integer.MIN_VALUE;
    public static final float DEFAULT_REFLECTION = 0.4f;
    public static final float DEFAULT_AMOUNT = 0.5f;
    /** Share of the ground's colour soaked ground keeps: wet ground reads darker, a thin shade over the wet. */
    private static final float WET_DARKENING = 0.85f;
    /** A tile's textures: its colour and opacity, its roughness, and its metal share. */
    private static final int COLOR = 0;
    private static final int ROUGHNESS = 1;
    private static final int METALLIC = 2;

    private static float reflection = DEFAULT_REFLECTION;
    private static float amount = DEFAULT_AMOUNT;
    private static boolean maskShown;
    private static boolean keptWhenDry;

    private static int changedMinX;
    private static int changedMinZ;
    private static int changedMaxX;
    private static int changedMaxZ;
    private static boolean changed;

    private final float[] noiseSum = PuddleMask.noiseSum(SEED);
    /** The top of each column around the origin that rain can pool on, or {@link #NO_WATER}. */
    private final int[] columnTop = new int[SPAN * SPAN];
    private final int[] blockX = new int[MAX_BLOCKS];
    private final int[] blockZ = new int[MAX_BLOCKS];
    private final int[] blockTop = new int[MAX_BLOCKS];
    private final float[] blockMinSum = new float[MAX_BLOCKS];
    private final float[] blockMaxSum = new float[MAX_BLOCKS];
    /** The noise sum, less the shelter's pull, at each grid point of each top, x fastest. */
    private final float[] blockSum = new float[MAX_BLOCKS * GRID_POINTS];
    /**
     * Per block, the copy of its top: the top texture's samples, or null when it has none or is
     * not yet looked up, its tint, and the map from the top to the texture (see {@link #groundMap}).
     */
    private final int[][] blockSamples = new int[MAX_BLOCKS][];
    private final int[] blockTint = new int[MAX_BLOCKS];
    private final float[] blockMap = new float[MAX_BLOCKS * 6];
    private final boolean[] blockLooked = new boolean[MAX_BLOCKS];
    private int blocks;

    /** Per tile: its coordinates in tiles, its blocks' range, the wetness it was traced at or -1, and its decal. */
    private final int[] tileX = new int[MAX_TILES];
    private final int[] tileZ = new int[MAX_TILES];
    private final int[] tileFirst = new int[MAX_TILES];
    private final int[] tileEnd = new int[MAX_TILES];
    private final float[] tileWetness = new float[MAX_TILES];
    private final RemixMesh[] tileMesh = new RemixMesh[MAX_TILES];
    private final RemixInstance[] tileInstance = new RemixInstance[MAX_TILES];
    private final RemixTexture[][] tileTexture = new RemixTexture[MAX_TILES][];
    private final RemixMaterial[] tileMaterial = new RemixMaterial[MAX_TILES];
    private int tiles;
    /** The tile the next frame's retracing starts its search at. */
    private int nextTile;
    // The tiles before a relay, so a tile kept across it keeps its decal until it is retraced.
    private final int[] keptX = new int[MAX_TILES];
    private final int[] keptZ = new int[MAX_TILES];
    private final RemixMesh[] keptMesh = new RemixMesh[MAX_TILES];
    private final RemixInstance[] keptInstance = new RemixInstance[MAX_TILES];
    private final RemixTexture[][] keptTexture = new RemixTexture[MAX_TILES][];
    private final RemixMaterial[] keptMaterial = new RemixMaterial[MAX_TILES];
    /** Textures and materials of tiles no longer laid, for the next new ones. */
    private final RemixTexture[][] spareTexture = new RemixTexture[MAX_TILES][];
    private final RemixMaterial[] spareMaterial = new RemixMaterial[MAX_TILES];
    private int spares;

    /** Per texture of a tile, its texels, a buffer to send them from, and the patch that does. */
    private final byte[][] texels = {new byte[SIZE * SIZE * 4], new byte[SIZE * SIZE], new byte[SIZE * SIZE]};
    private final ByteBuffer[] staging = new ByteBuffer[3];
    private final TexturePatch[] patches = new TexturePatch[3];
    private final TextureBatchEntry[] entries = new TextureBatchEntry[3];
    private final TexturePatchInfo update = new TexturePatchInfo();
    private final int[] indices = new int[TILE * TILE * 6];
    private final List<BlockStateModelPart> parts = new ObjectArrayList<>();
    private final SingleThreadedRandomSource random = new SingleThreadedRandomSource(0L);
    private final float[] faceX = new float[3];
    private final float[] faceZ = new float[3];
    private final float[] faceS = new float[3];
    private final float[] faceT = new float[3];

    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    /** The ring normals the materials were made with; a new one means the water was rebuilt. */
    private RemixTexture normals;
    private float wetness;
    /** The settings the decals were made with; a change rebuilds them. */
    private float builtReflection = -1.0f;
    private float builtOffset;
    private boolean builtMask;
    private int originX;
    private int originZ;
    private long lastRelayNanos;
    private long lastNanos;
    private ClientLevel meshLevel;
    private boolean failed;

    public Puddles() {
        for (int k = 0; k < 3; k++) {
            int bytes = k == COLOR ? 4 : 1;
            staging[k] = ByteBuffer.allocateDirect(texels[k].length);
            patches[k] = new TexturePatch();
            patches[k].width = SIZE;
            patches[k].height = SIZE;
            patches[k].rowPitch = SIZE * bytes;
            patches[k].format = k == COLOR ? TextureFormat.RGBA8_UNORM : TextureFormat.R8_UNORM;
            entries[k] = new TextureBatchEntry();
            entries[k].patches = new TexturePatch[] {patches[k]};
        }
        update.entries = entries;
    }

    /**
     * How strongly puddles reflect, 0 to 1: the share of standing water that is metal, coloured
     * as the ground beneath, which also darkens it by as much. At 0 a puddle reflects as water
     * does, 4% head on, which bright ground swamps. Lightening the colour to keep the ground's
     * brightness blew it out to white once the share grew.
     */
    public static float getReflection() { return reflection; }
    public static void setReflection(float value) { reflection = Math.clamp(value, 0.0f, 1.0f); }

    /** How much of open ground puddles cover, 0 to 1, by moving where the mask's bands start. */
    public static float getAmount() { return amount; }
    public static void setAmount(float value) { amount = Math.clamp(value, 0.0f, 1.0f); }

    /** Whether the decals show their opacity in red over the ground, a debug view of the mask. */
    public static boolean isMaskShown() { return maskShown; }
    public static void setMaskShown(boolean shown) { maskShown = shown; }

    /** Whether the ground stays soaked without rain, so puddles stand in dry weather too (#24). */
    public static boolean isKeptWhenDry() { return keptWhenDry; }
    public static void setKeptWhenDry(boolean kept) { keptWhenDry = kept; }

    /**
     * Notes that blocks in the given columns changed, so decals over them are relaid. Called
     * from the client's block updates on the render thread.
     */
    public static void blocksChanged(int minX, int minZ, int maxX, int maxZ) {
        if (!changed) {
            changedMinX = minX;
            changedMinZ = minZ;
            changedMaxX = maxX;
            changedMaxZ = maxZ;
            changed = true;
            return;
        }
        changedMinX = Math.min(changedMinX, minX);
        changedMinZ = Math.min(changedMinZ, minZ);
        changedMaxX = Math.max(changedMaxX, maxX);
        changedMaxZ = Math.max(changedMaxZ, maxZ);
    }

    /** Follows the rain and lays the puddles around the camera. */
    public void render(RemixAPI api, ClientLevel level, Vec3 camera, float partialTick, BlockTextureManager textures) {
        if (failed || api == null) return;
        long now = System.nanoTime();
        float seconds = lastNanos == 0 ? 0.0f : Math.min(0.25f, (now - lastNanos) / 1.0e9f);
        lastNanos = now;
        float rain = keptWhenDry ? 1.0f : Math.clamp(level.getRainLevel(partialTick), 0.0f, 1.0f);
        wetness = rain > wetness
                ? Math.min(rain, wetness + seconds / WET_SECONDS)
                : Math.max(rain, wetness - seconds / DRY_SECONDS);
        try {
            RemixTexture ringNormals = textures.getPuddleNormalTexture();
            if (wetness <= 0.0f || ringNormals == null || !ringNormals.isAlive()) {
                destroy();
                return;
            }
            float offset = (DEFAULT_AMOUNT - amount) * 2.0f * AMOUNT_RANGE;
            if (ringNormals != normals || reflection != builtReflection || offset != builtOffset
                    || maskShown != builtMask) {
                destroy();
                normals = ringNormals;
                builtReflection = reflection;
                builtOffset = offset;
                builtMask = maskShown;
            }
            int cameraX = (int) Math.floor(camera.x);
            int cameraZ = (int) Math.floor(camera.z);
            boolean relay = level != meshLevel
                    || Math.max(Math.abs(cameraX - originX), Math.abs(cameraZ - originZ)) >= RELAY_DISTANCE
                    || now - lastRelayNanos >= RELAY_INTERVAL_NANOS
                    || (changedNearby() && now - lastRelayNanos >= CHANGE_RELAY_NANOS);
            if (relay) {
                changed = false;
                meshLevel = level;
                originX = cameraX;
                originZ = cameraZ;
                lastRelayNanos = now;
                gatherColumns(level);
                gatherTiles();
            }
            retraceSome(api, level, textures);
        } catch (RuntimeException e) {
            LOGGER.error("Failed to draw puddles; turning them off", e);
            destroy();
            failed = true;
        }
    }

    private boolean changedNearby() {
        int reach = RADIUS + MARGIN;
        return changed && changedMaxX >= originX - reach && changedMinX <= originX + reach
                && changedMaxZ >= originZ - reach && changedMinZ <= originZ + reach;
    }

    /** Records the top of every column around the origin that rain can pool on. */
    private void gatherColumns(ClientLevel level) {
        int seaLevel = level.getSeaLevel();
        int reach = RADIUS + MARGIN;
        for (int dz = -reach; dz <= reach; dz++) {
            for (int dx = -reach; dx <= reach; dx++) {
                int x = originX + dx;
                int z = originZ + dz;
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
                pos.set(x, top, z);
                boolean rains = level.getBiome(pos).value().getPrecipitationAt(pos, seaLevel)
                        == Biome.Precipitation.RAIN;
                pos.set(x, top - 1, z);
                columnTop[(dz + reach) * SPAN + dx + reach] =
                        rains && holdsPuddles(level.getBlockState(pos)) ? top : NO_WATER;
            }
        }
    }

    /**
     * Lays the tiles over the radius's square and fills the block arrays, tile by tile, with the
     * tops in it that could ever hold water. A tile that was laid before keeps its decal, to be
     * retraced soon; one that is no longer laid loses its mesh and leaves its texture spare.
     */
    private void gatherTiles() {
        int kept = tiles;
        System.arraycopy(tileX, 0, keptX, 0, kept);
        System.arraycopy(tileZ, 0, keptZ, 0, kept);
        System.arraycopy(tileMesh, 0, keptMesh, 0, kept);
        System.arraycopy(tileInstance, 0, keptInstance, 0, kept);
        System.arraycopy(tileTexture, 0, keptTexture, 0, kept);
        System.arraycopy(tileMaterial, 0, keptMaterial, 0, kept);
        float lowest = PuddleMask.sumThreshold(WET_EDGE + builtOffset, 1.0f);
        int count = 0;
        int t = 0;
        for (int tz = Math.floorDiv(originZ - RADIUS, TILE); tz <= Math.floorDiv(originZ + RADIUS, TILE); tz++) {
            for (int tx = Math.floorDiv(originX - RADIUS, TILE); tx <= Math.floorDiv(originX + RADIUS, TILE); tx++) {
                tileX[t] = tx;
                tileZ[t] = tz;
                tileFirst[t] = count;
                tileMesh[t] = null;
                tileInstance[t] = null;
                tileTexture[t] = null;
                tileMaterial[t] = null;
                tileWetness[t] = -1.0f;
                for (int k = 0; k < kept; k++) {
                    if (keptX[k] == tx && keptZ[k] == tz) {
                        tileMesh[t] = keptMesh[k];
                        tileInstance[t] = keptInstance[k];
                        tileTexture[t] = keptTexture[k];
                        tileMaterial[t] = keptMaterial[k];
                        keptMesh[k] = null;
                        keptInstance[k] = null;
                        keptTexture[k] = null;
                        keptMaterial[k] = null;
                        break;
                    }
                }
                int minX = Math.max(tx * TILE, originX - RADIUS) - originX;
                int maxX = Math.min(tx * TILE + TILE - 1, originX + RADIUS) - originX;
                int minZ = Math.max(tz * TILE, originZ - RADIUS) - originZ;
                int maxZ = Math.min(tz * TILE + TILE - 1, originZ + RADIUS) - originZ;
                for (int dz = minZ; dz <= maxZ; dz++) {
                    for (int dx = minX; dx <= maxX; dx++) {
                        if (gatherBlock(count, dx, dz, lowest)) count++;
                    }
                }
                tileEnd[t] = count;
                t++;
            }
        }
        tiles = t;
        blocks = count;
        nextTile = 0;
        for (int k = 0; k < kept; k++) {
            destroyAsset(keptInstance[k]);
            destroyAsset(keptMesh[k]);
            if (keptTexture[k] != null) {
                spareTexture[spares] = keptTexture[k];
                spareMaterial[spares++] = keptMaterial[k];
            }
            keptMesh[k] = null;
            keptInstance[k] = null;
            keptTexture[k] = null;
            keptMaterial[k] = null;
        }
    }

    /**
     * Records the top of column (dx, dz) from the origin as block {@code count}, if it could ever
     * hold water; returns whether it did.
     */
    private boolean gatherBlock(int count, int dx, int dz, float lowest) {
        int top = columnTop[(dz + RADIUS + MARGIN) * SPAN + dx + RADIUS + MARGIN];
        if (top == NO_WATER) return false;
        int x = originX + dx;
        int z = originZ + dz;
        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        for (int g = 0; g < GRID_POINTS; g++) {
            float fx = (g % (GRID + 1)) / (float) GRID;
            float fz = (g / (GRID + 1)) / (float) GRID;
            float sum = sumAt(x, z, g % (GRID + 1), g / (GRID + 1))
                    - PuddleMask.MAX_SUM * shelter(dx, dz, top, fx, fz);
            blockSum[count * GRID_POINTS + g] = sum;
            min = Math.min(min, sum);
            max = Math.max(max, sum);
        }
        if (max <= lowest) return false;
        blockX[count] = x;
        blockZ[count] = z;
        blockTop[count] = top;
        blockMinSum[count] = min;
        blockMaxSum[count] = max;
        blockSamples[count] = null;
        blockLooked[count] = false;
        return true;
    }

    /**
     * How strongly the point (fx, fz) on the top of column (dx, dz) from the origin is pulled
     * dry, 0 to 1: fully on the boundary of a column that takes no water or lies lower, which
     * the water would run off to, fading out {@link #SHELTER_FADE} blocks away.
     */
    private float shelter(int dx, int dz, int top, float fx, float fz) {
        float nearest = SHELTER_FADE;
        int reach = RADIUS + MARGIN;
        for (int nz = -MARGIN; nz <= MARGIN; nz++) {
            for (int nx = -MARGIN; nx <= MARGIN; nx++) {
                if (nx == 0 && nz == 0) continue;
                int neighbour = columnTop[(dz + nz + reach) * SPAN + dx + nx + reach];
                if (neighbour != NO_WATER && neighbour >= top) continue;
                float distanceX = nx > 0 ? nx - fx : nx < 0 ? fx - (nx + 1) : 0.0f;
                float distanceZ = nz > 0 ? nz - fz : nz < 0 ? fz - (nz + 1) : 0.0f;
                nearest = Math.min(nearest, (float) Math.sqrt(distanceX * distanceX + distanceZ * distanceZ));
            }
        }
        float t = 1.0f - nearest / SHELTER_FADE;
        return t * t * (3.0f - 2.0f * t);
    }

    /** The noise sum at grid point (gx, gz) of the top of column (x, z). */
    private float sumAt(int x, int z, int gx, int gz) {
        int size = PuddleMask.SIZE;
        int tx = Math.floorMod(Math.floorMod(x, PuddleMask.TILE_BLOCKS) * PuddleMask.TEXELS_PER_BLOCK
                + gx * PuddleMask.TEXELS_PER_BLOCK / GRID, size);
        int tz = Math.floorMod(Math.floorMod(z, PuddleMask.TILE_BLOCKS) * PuddleMask.TEXELS_PER_BLOCK
                + gz * PuddleMask.TEXELS_PER_BLOCK / GRID, size);
        return noiseSum[tz * size + tx];
    }

    /**
     * Retraces, in turn and within {@link #RETRACE_BUDGET_NANOS}, the tiles whose decals were
     * traced at a wetness at least {@link #WETNESS_STEP} away from the current one, or never.
     */
    private void retraceSome(RemixAPI api, ClientLevel level, BlockTextureManager textures) {
        long deadline = System.nanoTime() + RETRACE_BUDGET_NANOS;
        for (int k = 0; k < tiles; k++) {
            int t = (nextTile + k) % tiles;
            if (tileWetness[t] >= 0.0f && Math.abs(wetness - tileWetness[t]) < WETNESS_STEP) continue;
            retrace(api, level, textures, t);
            nextTile = (t + 1) % tiles;
            if (System.nanoTime() >= deadline) return;
        }
    }

    /**
     * Replaces tile {@code t}'s decal with one traced at the current wetness: its texture, the
     * tops' copies with the opacity the mask gives at this wetness, and its mesh, a quad over
     * every top that shows any water.
     */
    private void retrace(RemixAPI api, ClientLevel level, BlockTextureManager textures, int t) {
        tileWetness[t] = wetness;
        for (byte[] layer : texels) Arrays.fill(layer, (byte) 0);
        int cornerX = tileX[t] * TILE;
        int cornerZ = tileZ[t] * TILE;
        int quads = 0;
        for (int i = tileFirst[t]; i < tileEnd[t]; i++) {
            if (!showsWater(i)) continue;
            if (!blockLooked[i]) lookUpGround(level, textures, i);
            if (blockSamples[i] == null) continue;
            fillBlock(i, blockX[i] - cornerX, blockZ[i] - cornerZ);
            int first = quads * 4;
            int at = quads * 6;
            indices[at] = first;
            indices[at + 1] = first + 1;
            indices[at + 2] = first + 2;
            indices[at + 3] = first;
            indices[at + 4] = first + 2;
            indices[at + 5] = first + 3;
            quads++;
        }
        RemixMesh previous = tileMesh[t];
        tileMesh[t] = null;
        if (quads > 0) {
            if (!acquire(t)) {
                for (int k = 0; k < 3; k++) {
                    staging[k].clear();
                    staging[k].put(texels[k]).flip();
                    patches[k].pixelData = staging[k];
                    entries[k].handle = tileTexture[t][k].getHandle();
                }
                try {
                    api.updateTextureFromMemory(update);
                } finally {
                    for (TexturePatch patch : patches) patch.pixelData = null;
                }
            }
            tileMesh[t] = createMesh(t, cornerX, cornerZ, quads);
        }
        if (tileMesh[t] == null) {
            destroyAsset(tileInstance[t]);
            tileInstance[t] = null;
        } else {
            if (tileInstance[t] == null) {
                tileInstance[t] = RemixInstanceStore.get().create(new RemixInstance(tileMesh[t])
                        .setCategoryFlags(InstanceCategoryBit.DECAL_STATIC)
                        .setBlendExt(BLEND)
                        .setDoubleSided(true));
            }
            tileInstance[t].setMesh(tileMesh[t]).setWorldTranslation(cornerX, 0.0, cornerZ).drawFast(api);
        }
        destroyAsset(previous);
    }

    /** Whether any of block {@code i}'s top shows water at the current wetness. */
    private boolean showsWater(int i) {
        return PuddleMask.depth(blockMaxSum[i], wetness) > WET_EDGE + builtOffset;
    }

    /**
     * Looks up what block {@code i}'s top shows: the first upward face of the model variant
     * vanilla draws there, its texture's samples, its tint, and the map onto them.
     */
    private void lookUpGround(ClientLevel level, BlockTextureManager textures, int i) {
        blockLooked[i] = true;
        pos.set(blockX[i], blockTop[i] - 1, blockZ[i]);
        BlockState state = level.getBlockState(pos);
        Minecraft client = Minecraft.getInstance();
        random.setSeed(state.getSeed(pos));
        BlockStateModel model = client.getModelManager().getBlockStateModelSet().get(state);
        model.collectParts(random, parts);
        try {
            // A modded model, such as Continuity's connected textures, shows its own sprite for
            // each position only through FRAPI; its parts give the base sprite, and Patrix's base
            // grass top reads "enable connected textures", which the puddles then copied.
            if (!BlockQuadEmitter.isVanillaModel(model, parts)) {
                lookUpModdedGround(level, textures, i, state, model);
                return;
            }
            for (int p = 0; p < parts.size(); p++) {
                for (BakedQuad quad : parts.get(p).getQuads(Direction.UP)) {
                    BakedQuad.MaterialInfo material = quad.materialInfo();
                    TextureAtlasSprite sprite = material.sprite();
                    int[] samples = sprite != null ? textures.spriteSamples(sprite, TEXELS) : null;
                    if (samples == null) continue;
                    float du = sprite.getU1() - sprite.getU0();
                    float dv = sprite.getV1() - sprite.getV0();
                    for (int v = 0; v < 3; v++) {
                        Vector3fc position = quad.position(v);
                        long uv = quad.packedUV(v);
                        faceX[v] = position.x();
                        faceZ[v] = position.z();
                        faceS[v] = (UVPair.unpackU(uv) - sprite.getU0()) / du;
                        faceT[v] = (UVPair.unpackV(uv) - sprite.getV0()) / dv;
                    }
                    if (!groundMap(faceX, faceZ, faceS, faceT, blockMap, i * 6)) continue;
                    blockSamples[i] = samples;
                    blockTint[i] = material.isTinted() ? tint(client, level, state, material.tintIndex()) : 0xFFFFFF;
                    return;
                }
            }
        } finally {
            parts.clear();
        }
    }

    private int tint(Minecraft client, ClientLevel level, BlockState state, int tintIndex) {
        BlockTintSource source = client.getBlockColors().getTintSource(state, tintIndex);
        return source != null ? source.colorInWorld(state, level, pos) : 0xFFFFFF;
    }

    // The block a modded model is emitting its quads for, while it does.
    private QuadEmitter groundEmitter;
    private SpriteFinder groundFinder;
    private ClientLevel groundLevel;
    private BlockTextureManager groundTextures;
    private BlockState groundState;
    private int groundIndex;

    /** {@link #lookUpGround} through the model's own quads, as FRAPI emits them for this position. */
    private void lookUpModdedGround(ClientLevel level, BlockTextureManager textures, int i,
                                    BlockState state, BlockStateModel model) {
        if (groundEmitter == null) groundEmitter = Renderer.get().quadEmitter(this::captureGround);
        groundFinder = BlockQuadEmitter.blockSpriteFinder();
        if (groundFinder == null) return;
        groundLevel = level;
        groundTextures = textures;
        groundState = state;
        groundIndex = i;
        random.setSeed(state.getSeed(pos));
        try {
            model.emitQuads(groundEmitter, level, pos, state, random, direction -> false);
        } finally {
            groundLevel = null;
            groundTextures = null;
            groundState = null;
        }
    }

    private void captureGround(MutableQuadView quad) {
        int i = groundIndex;
        if (blockSamples[i] != null || quad.faceNormal().y() < 0.99f) return;
        TextureAtlasSprite sprite = groundFinder.find(quad);
        int[] samples = sprite != null ? groundTextures.spriteSamples(sprite, TEXELS) : null;
        if (samples == null) return;
        float du = sprite.getU1() - sprite.getU0();
        float dv = sprite.getV1() - sprite.getV0();
        for (int v = 0; v < 3; v++) {
            faceX[v] = quad.x(v);
            faceZ[v] = quad.z(v);
            faceS[v] = (quad.u(v) - sprite.getU0()) / du;
            faceT[v] = (quad.v(v) - sprite.getV0()) / dv;
        }
        if (!groundMap(faceX, faceZ, faceS, faceT, blockMap, i * 6)) return;
        blockSamples[i] = samples;
        blockTint[i] = quad.tintIndex() >= 0
                ? tint(Minecraft.getInstance(), groundLevel, groundState, quad.tintIndex()) : 0xFFFFFF;
    }

    /**
     * Solves the affine map from a top's (x, z) to a texture's (s, t) through three corners of a
     * face and writes it at {@code at} as s = a x + b z + c, t = d x + e z + f; returns false when
     * the corners do not span the top.
     */
    static boolean groundMap(float[] x, float[] z, float[] s, float[] t, float[] out, int at) {
        float dx1 = x[1] - x[0];
        float dz1 = z[1] - z[0];
        float dx2 = x[2] - x[0];
        float dz2 = z[2] - z[0];
        float det = dx1 * dz2 - dx2 * dz1;
        if (Math.abs(det) < 1.0e-6f) return false;
        float sa = ((s[1] - s[0]) * dz2 - (s[2] - s[0]) * dz1) / det;
        float sb = ((s[2] - s[0]) * dx1 - (s[1] - s[0]) * dx2) / det;
        float ta = ((t[1] - t[0]) * dz2 - (t[2] - t[0]) * dz1) / det;
        float tb = ((t[2] - t[0]) * dx1 - (t[1] - t[0]) * dx2) / det;
        out[at] = sa;
        out[at + 1] = sb;
        out[at + 2] = s[0] - sa * x[0] - sb * z[0];
        out[at + 3] = ta;
        out[at + 4] = tb;
        out[at + 5] = t[0] - ta * x[0] - tb * z[0];
        return true;
    }

    /** How wet the ground is at a depth in the mask, the decal's opacity: from dry to soaked. */
    static float wet(float depth) {
        return smoothstep(WET_EDGE, WET_FULL, depth);
    }

    /** How much water stands at a depth in the mask: from wet ground's sheen to a mirror. */
    static float water(float depth) {
        return smoothstep(WATER_EDGE, WATER_FULL, depth);
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        float s = Math.clamp((x - edge0) / (edge1 - edge0), 0.0f, 1.0f);
        return s * s * (3.0f - 2.0f * s);
    }

    /**
     * Writes block {@code i}'s texels at (localX, localZ) in its tile: its top's copy, tinted and
     * a little darker, with the wetness as opacity, and the roughness and metal share of the water
     * standing there, all from the mask's sum, less the shelter's pull, taken bilinearly between
     * the block's grid points. Every texel of the block gets the copy, so mips fade to the
     * ground's colour rather than to black.
     */
    private void fillBlock(int i, int localX, int localZ) {
        int[] samples = blockSamples[i];
        int tint = blockTint[i];
        int tintR = (tint >> 16) & 0xFF;
        int tintG = (tint >> 8) & 0xFF;
        int tintB = tint & 0xFF;
        int m = i * 6;
        byte[] color = texels[COLOR];
        byte[] roughness = texels[ROUGHNESS];
        byte[] metallic = texels[METALLIC];
        boolean full = PuddleMask.depth(blockMinSum[i], wetness) - builtOffset >= WATER_FULL;
        int row = GRID + 1;
        int base = i * GRID_POINTS;
        for (int tz = 0; tz < TEXELS; tz++) {
            float fz = (tz + 0.5f) / TEXELS;
            float gz = fz * GRID;
            int gz0 = Math.min((int) gz, GRID - 1);
            float wz = gz - gz0;
            int at = (localZ * TEXELS + tz) * SIZE + localX * TEXELS;
            for (int tx = 0; tx < TEXELS; tx++, at++) {
                int out = at * 4;
                float fx = (tx + 0.5f) / TEXELS;
                int s = Math.clamp((int) Math.floor((blockMap[m] * fx + blockMap[m + 1] * fz + blockMap[m + 2]) * TEXELS),
                        0, TEXELS - 1);
                int t = Math.clamp((int) Math.floor((blockMap[m + 3] * fx + blockMap[m + 4] * fz + blockMap[m + 5]) * TEXELS),
                        0, TEXELS - 1);
                int sample = samples[t * TEXELS + s];
                color[out] = (byte) Math.round(((sample >> 16) & 0xFF) * tintR / 255 * WET_DARKENING);
                color[out + 1] = (byte) Math.round(((sample >> 8) & 0xFF) * tintG / 255 * WET_DARKENING);
                color[out + 2] = (byte) Math.round((sample & 0xFF) * tintB / 255 * WET_DARKENING);
                float alpha = 1.0f;
                float standing = 1.0f;
                if (!full) {
                    float gx = fx * GRID;
                    int gx0 = Math.min((int) gx, GRID - 1);
                    float wx = gx - gx0;
                    int g = base + gz0 * row + gx0;
                    float top = blockSum[g] + (blockSum[g + 1] - blockSum[g]) * wx;
                    float bottom = blockSum[g + row] + (blockSum[g + row + 1] - blockSum[g + row]) * wx;
                    float depth = PuddleMask.depth(top + (bottom - top) * wz, wetness) - builtOffset;
                    alpha = wet(depth);
                    standing = water(depth);
                }
                color[out + 3] = (byte) Math.round(255.0f * alpha);
                roughness[at] = (byte) Math.round(255.0f * WET_ROUGHNESS * (1.0f - standing));
                metallic[at] = (byte) Math.round(255.0f * builtReflection * standing);
                if (builtMask) {
                    color[out] = (byte) 255;
                    color[out + 1] = 0;
                    color[out + 2] = 0;
                }
            }
        }
    }

    /**
     * Gives tile {@code t} its textures and their material if it has none, spare ones if any;
     * returns whether the textures were made now, holding {@link #texels} already.
     */
    private boolean acquire(int t) {
        if (tileTexture[t] != null) return false;
        if (spares > 0) {
            spares--;
            tileTexture[t] = spareTexture[spares];
            tileMaterial[t] = spareMaterial[spares];
            spareTexture[spares] = null;
            spareMaterial[spares] = null;
            return false;
        }
        RemixTexture[] textures = new RemixTexture[3];
        for (int k = 0; k < 3; k++) {
            ByteBuffer pixels = DirectBufferPool.acquire(texels[k].length);
            pixels.put(texels[k]).flip();
            textures[k] = RemixTextureStore.get().create(new RemixTexture(SIZE, SIZE,
                    k == COLOR ? TextureFormat.RGBA8_UNORM : TextureFormat.R8_UNORM,
                    k == COLOR ? TexturePurpose.COLOR : TexturePurpose.DATA, pixels));
        }
        tileTexture[t] = textures;
        // Nearest filtering, as the terrain's own atlas, so the copy shows the same texels.
        tileMaterial[t] = RemixMaterialStore.get().create(new RemixMaterial(MaterialInfo.builder()
                .albedoTexture(textures[COLOR].getHandle())
                .normalTexture(normals.getHandle())
                .filterMode(0)
                .wrapMode(MaterialInfo.WRAP_MODE_CLAMP, MaterialInfo.WRAP_MODE_CLAMP)
                .opaqueExt(MaterialInfoOpaqueEXT.builder()
                        .albedoConstant(1.0f, 1.0f, 1.0f)
                        .roughnessTexture(textures[ROUGHNESS].getHandle())
                        .metallicTexture(textures[METALLIC].getHandle())
                        .roughnessConstant(1.0f)
                        .metallicConstant(1.0f)
                        .useDrawCallAlphaState(true)
                        .build())
                .build(), List.of(textures[COLOR], textures[ROUGHNESS], textures[METALLIC], normals), null));
        return true;
    }

    /** The tile's tops that show water, one quad each in the order {@link #retrace} indexed them. */
    private RemixMesh createMesh(int t, int cornerX, int cornerZ, int quads) {
        ByteBuffer vertices = DirectBufferPool.acquire(quads * 4 * HardcodedVertex.STRIDE);
        for (int i = tileFirst[t]; i < tileEnd[t]; i++) {
            if (!showsWater(i) || blockSamples[i] == null) continue;
            float x = blockX[i] - cornerX;
            float z = blockZ[i] - cornerZ;
            float y = blockTop[i];
            for (int corner = 0; corner < 4; corner++) {
                float fx = corner == 1 || corner == 2 ? 1.0f : 0.0f;
                float fz = corner >= 2 ? 1.0f : 0.0f;
                RemixMeshSurface.putVertex(vertices, x + fx, y, z + fz, 0.0f, 1.0f, 0.0f,
                        (x + fx) / TILE, (z + fz) / TILE, 0xFFFFFFFF, 0, 0);
            }
        }
        vertices.flip();
        return RemixMeshStore.get().create(new RemixMesh(new RemixMeshSurface[] {
                new RemixMeshSurface(tileMaterial[t])
                        .setVertexData(vertices, quads * 4)
                        .setIndexDataFrom(indices, quads * 6)}));
    }

    /**
     * Whether water can stand on top of the block: a full, opaque top that is not sand,
     * which drains, nor snow or ice, which the rain does not reach as water.
     */
    static boolean holdsPuddles(BlockState state) {
        return state.isSolidRender()
                && !state.is(BlockTags.SAND)
                && !state.is(BlockTags.SNOW)
                && !state.is(BlockTags.ICE)
                && !state.is(Blocks.MAGMA_BLOCK);
    }

    /** Destroys the decals; the next wet frame rebuilds them. The ring normals are not ours. */
    public void destroy() {
        for (int t = 0; t < tiles; t++) {
            destroyAsset(tileInstance[t]);
            destroyAsset(tileMesh[t]);
            destroyAsset(tileMaterial[t]);
            destroyTextures(tileTexture[t]);
            tileInstance[t] = null;
            tileMesh[t] = null;
            tileMaterial[t] = null;
            tileTexture[t] = null;
        }
        for (int k = 0; k < spares; k++) {
            destroyAsset(spareMaterial[k]);
            destroyTextures(spareTexture[k]);
            spareMaterial[k] = null;
            spareTexture[k] = null;
        }
        spares = 0;
        tiles = 0;
        blocks = 0;
        normals = null;
        meshLevel = null;
    }

    private static void destroyTextures(RemixTexture[] textures) {
        if (textures == null) return;
        for (RemixTexture texture : textures) destroyAsset(texture);
    }

    private static void destroyAsset(RemixAssetBase asset) {
        if (asset != null && !asset.isDeleted()) asset.destroy();
    }
}
