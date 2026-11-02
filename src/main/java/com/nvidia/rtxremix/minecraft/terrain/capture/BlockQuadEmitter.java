/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.capture;

import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.minecraft.light.BlockLightRuleManager;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import com.nvidia.rtxremix.minecraft.light.LightEntry;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.fabricmc.fabric.api.client.renderer.v1.Renderer;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.sprite.SpriteFinder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;
import org.joml.Vector3fc;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

/**
 * Per-block mesh emission for RTX Remix, designed to be driven from
 * {@link com.nvidia.rtxremix.minecraft.mixin.SectionBuilderMixin} redirects
 * that piggyback on MC's own block iteration loop.
 *
 * <p>Lifecycle per section:
 * <ol>
 *   <li>{@link #beginSection}: reset state, called at HEAD of build()</li>
 *   <li>{@link #processBlock}: opacity and lights, called per non-air block from the isSolidRender redirect</li>
 *   <li>{@link #emitBlock}: block model quads for MODEL blocks, called from the same
 *       isOpaqueFullCube redirect; vanilla's renderBlock never runs because captured
 *       blocks are reported invisible</li>
 *   <li>{@link #emitFluid}: fluid quads, called from renderFluid redirect</li>
 *   <li>{@link #finishSection}: build SectionBlockData, called at RETURN</li>
 * </ol>
 *
 * <p>One instance per worker thread via {@code ThreadLocal}.
 */
public final class BlockQuadEmitter {

    private static final Direction[] HORIZONTAL_DIRECTIONS = {
        Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST
    };
    private static final Direction[] CULLED_DIRECTIONS = Direction.values();

    /**
     * Block units a see-through block's face moves into a neighbour that draws the face
     * it meets; see {@link #meetsNeighbourFace}.
     */
    static final float SHARED_FACE_PUSH = 1f / 512f;

    private static final int HEIGHT_NOT_READ = Integer.MIN_VALUE;
    private static final int HEIGHT_UNKNOWN = Integer.MIN_VALUE + 1;

    private static final ThreadLocal<BlockQuadEmitter> INSTANCE = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> threadNamed =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    private final SingleThreadedRandomSource random = new SingleThreadedRandomSource(42);
    /** The model's parts for the block being emitted, reused so the vanilla path allocates nothing. */
    private final List<BlockStateModelPart> parts = new ObjectArrayList<>();
    private final BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos neighborPos = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos fluidPos = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos fluidCheckPos = new BlockPos.MutableBlockPos();
    private final DirectQuadWriter writer = new DirectQuadWriter();
    private final ModelSampling modelSampling = new ModelSampling();
    private final List<LightEntry> lights = new ArrayList<>();

    private static final int TINT_CACHE_SIZE = 8;
    private static final int SECTION_SIZE = 16;
    private static final int LOCAL_BLOCK_COUNT = SECTION_SIZE * SECTION_SIZE * SECTION_SIZE;
    private static final int WATER_COLOR_CACHE_SIZE = 4 * 2 * 4;
    private static final float WATER_CEILING_CULL_MIN_HEIGHT = 8.0f / 9.0f;
    private static final float WATER_HEIGHT_EPSILON = 0.0001f;
    private static final int WATER_TOP_CULL_RADIUS = 2;
    private static final int WATER_TOP_CULL_WIDTH = WATER_TOP_CULL_RADIUS * 2 + 1;
    private static final int WATER_TOP_CULL_CELL_COUNT = WATER_TOP_CULL_WIDTH * WATER_TOP_CULL_WIDTH;
    private static final int WATER_TOP_CACHE_WIDTH = SECTION_SIZE + WATER_TOP_CULL_RADIUS * 2;
    private static final int WATER_TOP_CACHE_CELL_COUNT =
            WATER_TOP_CACHE_WIDTH * SECTION_SIZE * WATER_TOP_CACHE_WIDTH;
    // BlockState render type is immutable; cache by raw state id to avoid a per-block virtual call.
    private static volatile RenderShape[] renderTypeByStateId = new RenderShape[0];

    private final float[] tempPositions = new float[12];
    private final float[] tempUvs = new float[8];
    private final float[] tempNormal = new float[3];
    private final int[] tintCache = new int[TINT_CACHE_SIZE];
    private final int[] waterColorCache = new int[WATER_COLOR_CACHE_SIZE];
    private final byte[] waterTopCellCache = new byte[WATER_TOP_CACHE_CELL_COUNT];
    private final int[] waterTopCullStack = new int[WATER_TOP_CULL_CELL_COUNT];
    private final FluidTopOcclusion.CellClassifier waterTopCellClassifier =
            this::classifyWaterTopCellAtOffset;
    private final BlockState[] blockStateCache = new BlockState[LOCAL_BLOCK_COUNT];
    private final FluidState[] fluidStateCache = new FluidState[LOCAL_BLOCK_COUNT];

    /**
     * Results of {@link Block#shouldRenderFace}, keyed like vanilla's own cache by (state,
     * neighbour state, side) and therefore position-independent to the same degree. Vanilla's
     * cache allocates its lookup key on every opaque-neighbour face; this direct-mapped table
     * of packed state ids allocates nothing on a hit and defers to vanilla on a miss.
     */
    private static final int FACE_CULL_CACHE_BITS = 13;
    private static final byte FACE_CULL_UNKNOWN = 0;
    private static final byte FACE_CULL_HIDDEN = 1;
    private static final byte FACE_CULL_DRAWN = 2;
    private final long[] faceCullKeys = new long[1 << FACE_CULL_CACHE_BITS];
    private final byte[] faceCullResults = new byte[1 << FACE_CULL_CACHE_BITS];

    /**
     * Model-randomisation seed for the block being emitted, mirroring vanilla's
     * {@code state.getSeed(pos)}. Set once per block in {@link #emitBlock}.
     *
     * <p>A multi-variant block picks its variant from this, so a constant makes
     * every instance identical. Where the variants are rotations of one model
     * that is not merely a repeated texture but misplaced geometry: a sea
     * pickle cluster rotates its pickles around the block.
     */
    private long modelRandomSeed = 42L;

    // Per-section state set by beginSection / processBlock
    /**
     * Whether {@link #beginSection} has run without a matching {@link #finishSection}.
     * Capture is gated on it, and a build that never reaches its RETURN inject leaves it
     * set, so the next build on this thread releases the writer's grown buffers before
     * it starts.
     */
    private boolean active;
    private BlockTextureManager texManager;
    private BlockStateModelSet blockModelSet;
    private FluidStateModelSet fluidModelSet;
    /** Vanilla's Cutout Leaves option for this build; off draws leaves on the solid layer. */
    private boolean cutoutLeaves;
    private BlockColors blockColors;
    private int baseX, baseY, baseZ;
    private final long[] opacity = new long[64];
    private final long[] blockStateKnown = new long[64];
    private final long[] fluidStateKnown = new long[64];
    private int blockCount, fluidCount;
    private int tintValidMask;
    /** Sides of the current block {@link #meetsNeighbourFace} has decided, and those it found shared. */
    private int sharedSidesTested, sharedSides;
    /** Added to every position staged for the current face; non-zero only on a shared side. */
    private float facePushX, facePushY, facePushZ;

    // Where rain reaches: the level's heightmaps and biomes.
    /** The level the build reads, for heightmaps and biomes; null when it is unknown. */
    private @Nullable ClientLevel level;
    /** The top motion-blocking block per column of this section, read on first use. */
    private final int[] motionBlockingTops = new int[SECTION_SIZE * SECTION_SIZE];
    private boolean motionBlockingTopsCleared;
    private final BlockPos.MutableBlockPos rainPos = new BlockPos.MutableBlockPos();
    private int waterColorKnownMask;
    private FluidState waterTopCellCacheFluid;
    private BlockAndTintGetter waterTopCullWorld;
    private FluidState waterTopCullReferenceFluid;
    private int waterTopCullOriginX, waterTopCullOriginY, waterTopCullOriginZ;

    private BlockQuadEmitter() {}

    /** Returns the per-thread instance. */
    public static BlockQuadEmitter threadLocal() {
        BlockQuadEmitter emitter = INSTANCE.get();
        if (emitter == null) {
            emitter = new BlockQuadEmitter();
            INSTANCE.set(emitter);
        }
        return emitter;
    }

    /** Returns the current per-thread instance without allocating one. */
    public static BlockQuadEmitter current() {
        return INSTANCE.get();
    }

    /** Releases a retiring build worker's buffers, without creating an emitter. */
    public static void releaseCurrent() {
        BlockQuadEmitter emitter = INSTANCE.get();
        if (emitter == null) return;
        emitter.abortSection();
        emitter.writer.releaseBuffers();
        INSTANCE.remove();
    }

    public static int getWaterTopOcclusionRadius() {
        return WATER_TOP_CULL_RADIUS;
    }

    // --- Section lifecycle (called from mixin) ---

    /**
     * Initialises emission state for a new section. Called from the
     * SectionBuilderMixin HEAD inject before MC's block loop starts.
     */
    public void beginSection(SectionPos sectionPos,
                             BlockTextureManager tex,
                             BlockStateModelSet blockModelSet,
                             FluidStateModelSet fluidModelSet,
                             boolean cutoutLeaves,
                             @Nullable ClientLevel level) {
        if (!threadNamed.get()) {
            RemixAPI.tracyThreadName("Worker " + Thread.currentThread().getName());
            threadNamed.set(Boolean.TRUE);
        }
        if (active) {
            writer.releaseBuffers();
        }
        this.active = true;
        this.texManager = tex;
        this.blockModelSet = blockModelSet;
        this.fluidModelSet = fluidModelSet;
        this.cutoutLeaves = cutoutLeaves;
        this.level = level;
        this.motionBlockingTopsCleared = false;
        this.baseX = sectionPos.minBlockX();
        this.baseY = sectionPos.minBlockY();
        this.baseZ = sectionPos.minBlockZ();

        Minecraft client = Minecraft.getInstance();
        this.blockColors = (client != null) ? client.getBlockColors() : null;

        writer.beginSection(baseX, baseY, baseZ);
        lights.clear();
        Arrays.fill(opacity, 0);
        Arrays.fill(blockStateKnown, 0);
        Arrays.fill(fluidStateKnown, 0);
        // Clear the water classification cache lazily on the first eligible
        // surface so sections without covered water pay no cache-reset cost.
        waterTopCellCacheFluid = null;
        waterColorKnownMask = 0;
        blockCount = 0;
        fluidCount = 0;
    }

    public boolean isActive() { return active; }

    /** Releases an unfinished build immediately, including cancellation without a result. */
    public void abortSection() {
        if (!active) return;
        active = false;
        writer.releaseBuffers();
        lights.clear();
        parts.clear();
        frapiState = null;
        frapiWorld = null;
    }
    public int getBlockCount() { return blockCount; }
    public int getFluidCount() { return fluidCount; }

    public static RenderShape getRenderTypeCached(BlockState state) {
        int id = Block.getId(state);
        RenderShape[] cache = renderTypeByStateId;
        if (id < cache.length) {
            RenderShape renderType = cache[id];
            if (renderType != null) {
                return renderType;
            }
        }

        RenderShape renderType = state.getRenderShape();
        cache = ensureRenderTypeCacheCapacity(id);
        cache[id] = renderType;
        return renderType;
    }

    private static RenderShape[] ensureRenderTypeCacheCapacity(int id) {
        RenderShape[] cache = renderTypeByStateId;
        if (id < cache.length) {
            return cache;
        }

        synchronized (BlockQuadEmitter.class) {
            cache = renderTypeByStateId;
            if (id >= cache.length) {
                int newSize = Math.max(id + 1, Math.max(1024, cache.length * 2));
                renderTypeByStateId = Arrays.copyOf(cache, newSize);
            }
            return renderTypeByStateId;
        }
    }

    /**
     * Captures the opacity bitmap and light-emitting blocks. Called once per non-air
     * block from the isSolidRender redirect; the section starts out all transparent,
     * which is what air is.
     */
    public void processBlock(BlockState state, BlockGetter world, BlockPos pos, boolean opaque) {
        int lx = SectionPos.sectionRelative(pos.getX());
        int ly = SectionPos.sectionRelative(pos.getY());
        int lz = SectionPos.sectionRelative(pos.getZ());

        int bitIdx = (ly << 8) | (lz << 4) | lx;
        cacheLocalBlockState(bitIdx, state);

        long bit = 1L << (bitIdx & 63);
        int word = bitIdx >> 6;
        if (opaque) {
            opacity[word] |= bit;
        } else {
            opacity[word] &= ~bit;
        }

        if (state.isAir()) return;
        if (!BlockLightRuleManager.hasMatchingRules(state)) return;

        // The sampler needs the same seed the mesh uses, so a rule reading
        // model geometry sees the variant this position actually draws.
        //
        // Lights resolve against the unoffset block position, and the sampler
        // reads the unoffset baked model, while emitBlock folds
        // state.getModelOffset into the geometry origin. No vanilla block
        // combines an AbstractBlock.OffsetType with a luminance, so the two
        // frames agree for every vanilla block; a modded or resource-pack rule
        // on an offset block would need the same offset passed into begin() and
        // into the LightEntry positions.
        modelSampling.begin(blockModelSet, state, state.getSeed(pos));
        List<LightEntry> resolvedLights = BlockLightRuleManager.resolve(state, pos, modelSampling);
        lights.addAll(resolvedLights);
    }

    /**
     * Emits block model quads for the current block. Called from the
     * isSolidRender redirect for blocks vanilla would hand to its block
     * renderer, a call the build then skips by reporting the block invisible.
     * Vanilla models bypass FRAPI/Indigo and write directly to the
     * DirectQuadWriter.
     */
    public void emitBlock(BlockState state, BlockPos pos, BlockAndTintGetter world) {
        blockCount++;
        mutablePos.set(pos);
        tintValidMask = 0;
        sharedSidesTested = 0;
        sharedSides = 0;
        setFacePush(null);

        // Vanilla derives model randomisation from the block position, in the
        // same ModelBlockRenderer.tesselateBlock this emitter replaces, and
        // seeds once before collecting the model's parts, as the paths below do.
        modelRandomSeed = state.getSeed(pos);

        float lx = SectionPos.sectionRelative(pos.getX());
        float ly = SectionPos.sectionRelative(pos.getY());
        float lz = SectionPos.sectionRelative(pos.getZ());

        // Vanilla applies the per-position model offset (BlockBehaviour.OffsetType
        // XZ/XYZ: flowers, saplings, grass, dripstone) inside
        // ModelBlockRenderer.tesselateBlock, which this emitter replaces wholesale.
        // Applying it here is what keeps Remix geometry on the same spot as
        // vanilla's.
        //
        // Folded into the section-local origin rather than applied per vertex:
        // both model paths already add that origin to every position, so the
        // offset rides along for free. hasOffsetFunction() is a bare null check on
        // the block's offsetter, while getOffset() allocates a Vec3, so the gate
        // keeps that allocation off a path that runs for every block in every
        // section build.
        //
        // This moves geometry only. Block lights are resolved in processBlock
        // against the unoffset grid position; see the note there before adding a
        // light rule to a block that offsets.
        if (state.hasOffsetFunction()) {
            Vec3 modelOffset = state.getOffset(pos);
            lx += (float) modelOffset.x;
            ly += (float) modelOffset.y;
            lz += (float) modelOffset.z;
        }

        BlockStateModel model = blockModelSet.get(state);
        String layerOverride = blockLayerOverride(state, cutoutLeaves);

        random.setSeed(modelRandomSeed);
        model.collectParts(random, parts);
        try {
            if (isVanillaModel(model, parts)) {
                emitVanillaBlock(world, state, layerOverride, lx, ly, lz);
            } else {
                emitFrapiBlock(world, state, model, layerOverride, lx, ly, lz);
            }
        } finally {
            parts.clear();
        }
    }

    /**
     * Emits fluid quads for the current block. Called from the
     * renderFluid redirect.
     */
    public void emitFluid(BlockPos pos, BlockAndTintGetter world, FluidState fluidState) {
        fluidCount++;
        int lx = SectionPos.sectionRelative(pos.getX());
        int ly = SectionPos.sectionRelative(pos.getY());
        int lz = SectionPos.sectionRelative(pos.getZ());
        cacheLocalFluidState(localIndex(lx, ly, lz), fluidState);
        emitFluidQuads(world, fluidState, lx, ly, lz);
    }

    /**
     * Finalises the section, producing a {@link SectionBlockData} with
     * all accumulated mesh/light/mask data. Deactivates the emitter.
     */
    public SectionBlockData finishSection(SectionPos sectionPos) {
        try (ProfileScope finishScope = ProfileScope.begin("finishSection")) {
            finishScope.blockCount(blockCount).fluidCount(fluidCount);
            SectionMeshData meshData = writer.buildMeshData();
            SectionBlockData result = new SectionBlockData(
                    sectionPos, meshData, List.copyOf(lights),
                    boundaryMask(opacity, Direction.UP), boundaryMask(opacity, Direction.DOWN),
                    boundaryMask(opacity, Direction.EAST), boundaryMask(opacity, Direction.WEST),
                    boundaryMask(opacity, Direction.SOUTH), boundaryMask(opacity, Direction.NORTH),
                    Arrays.copyOf(opacity, opacity.length));
            lights.clear();
            return result;
        } finally {
            active = false;
        }
    }

    /**
     * The section's boundary on {@code face} as a bit per 2x2 patch, set where any block of
     * the patch on that face is not opaque; the bit order is the one {@code TerrainState}'s
     * flood reads. It is read from the opacity bitmap rather than gathered block by block,
     * because vanilla never hands air to the capture hook, and a boundary of air alone would
     * otherwise read as sealed.
     */
    static long boundaryMask(long[] opacity, Direction face) {
        long mask = 0L;
        for (int a = 0; a < 16; a++) {
            for (int b = 0; b < 16; b++) {
                boolean open = switch (face) {
                    case UP -> !opaqueAt(opacity, a, 15, b);
                    case DOWN -> !opaqueAt(opacity, a, 0, b);
                    case EAST -> !opaqueAt(opacity, 15, a, b);
                    case WEST -> !opaqueAt(opacity, 0, a, b);
                    case SOUTH -> !opaqueAt(opacity, b, a, 15);
                    case NORTH -> !opaqueAt(opacity, b, a, 0);
                };
                if (open) {
                    // UP and DOWN index (z, x), so row b; the sides index (y, the other axis), so row a.
                    int bit = face.getAxis() == Direction.Axis.Y
                            ? (b >> 1) * 8 + (a >> 1)
                            : (a >> 1) * 8 + (b >> 1);
                    mask |= 1L << bit;
                }
            }
        }
        return mask;
    }

    private static boolean opaqueAt(long[] opacity, int lx, int ly, int lz) {
        int index = (ly << 8) | (lz << 4) | lx;
        return (opacity[index >> 6] & (1L << (index & 63))) != 0;
    }

    private void emitVanillaBlock(BlockAndTintGetter world, BlockState state,
                                   @Nullable String layerOverride,
                                   float lx, float ly, float lz) {
        // Vanilla's face loop: each culled side is tested once per block and
        // only when some part has quads on it, then every part's unculled quads.
        int sideTested = 0;
        int sideDrawn = 0;
        for (int p = 0, partCount = parts.size(); p < partCount; p++) {
            BlockStateModelPart part = parts.get(p);
            for (Direction dir : CULLED_DIRECTIONS) {
                List<BakedQuad> culledQuads = part.getQuads(dir);
                if (culledQuads.isEmpty()) {
                    continue;
                }
                int sideBit = 1 << dir.ordinal();
                if ((sideTested & sideBit) == 0) {
                    sideTested |= sideBit;
                    if (shouldDrawBlockSide(world, state, dir)) {
                        sideDrawn |= sideBit;
                    }
                }
                if ((sideDrawn & sideBit) == 0) {
                    continue;
                }
                setFacePush(meetsNeighbourFace(world, state, dir) ? dir : null);
                for (int q = 0, quadCount = culledQuads.size(); q < quadCount; q++) {
                    stageBakedQuad(culledQuads.get(q), layerOverride, state, world, lx, ly, lz);
                }
                setFacePush(null);
            }
            List<BakedQuad> unculledQuads = part.getQuads(null);
            for (int q = 0, quadCount = unculledQuads.size(); q < quadCount; q++) {
                stageBakedQuad(unculledQuads.get(q), layerOverride, state, world, lx, ly, lz);
            }
        }

        writer.flushBlock(texManager);
    }

    private void stageBakedQuad(BakedQuad bq, @Nullable String layerOverride,
                                 BlockState state, BlockAndTintGetter world,
                                 float lx, float ly, float lz) {
        for (int v = 0; v < 4; v++) {
            Vector3fc position = bq.position(v);
            long packedUv = bq.packedUV(v);
            tempPositions[v * 3]     = position.x() + lx + facePushX;
            tempPositions[v * 3 + 1] = position.y() + ly + facePushY;
            tempPositions[v * 3 + 2] = position.z() + lz + facePushZ;
            tempUvs[v * 2]     = UVPair.unpackU(packedUv);
            tempUvs[v * 2 + 1] = UVPair.unpackV(packedUv);
        }

        // Baked quads carry no normal; vanilla's own vertex normal comes from the
        // face direction, which a rotated element (a cross plant) does not follow.
        computeGeometricNormal();
        float nx = tempNormal[0], ny = tempNormal[1], nz = tempNormal[2];

        BakedQuad.MaterialInfo material = bq.materialInfo();
        int color = 0xFFFFFFFF;
        if (material.isTinted() && blockColors != null) {
            int tint = getBlockTint(state, world, material.tintIndex());
            int r = (tint >> 16) & 0xFF, g = (tint >> 8) & 0xFF, b = tint & 0xFF;
            color = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        String layerName = layerOverride != null ? layerOverride : layerName(material.layer());

        TextureAtlasSprite sprite = material.sprite();
        Identifier spriteId = sprite != null ? sprite.contents().name() : null;
        if (isWaterSprite(spriteId)) {
            emitBlockWaterQuad(nx, ny, nz, color, layerName);
            return;
        }
        if (layerOverride == null && material.layer() == ChunkSectionLayer.TRANSLUCENT
                && !readsAsTranslucent(spriteId)) {
            layerName = "cutout";
        }
        float su0 = sprite != null ? sprite.getU0() : 0;
        float sv0 = sprite != null ? sprite.getV0() : 0;
        float su1 = sprite != null ? sprite.getU1() : 1;
        float sv1 = sprite != null ? sprite.getV1() : 1;

        writer.stageQuad(tempPositions, tempUvs, nx, ny, nz, color,
                spriteId, su0, sv0, su1, sv1, layerName);
    }


    /**
     * The y of the highest block that stops rain in column {@code (lx, lz)} of this
     * section, or {@link #HEIGHT_UNKNOWN}. Read from the client chunk's heightmap, which
     * vanilla keeps from the server's chunk data, and never primed here: a missing map
     * reads as unknown rather than being computed on a worker.
     */
    private int motionBlockingTop(int lx, int lz) {
        if (!motionBlockingTopsCleared) {
            Arrays.fill(motionBlockingTops, HEIGHT_NOT_READ);
            motionBlockingTopsCleared = true;
        }
        int column = lz * SECTION_SIZE + lx;
        int top = motionBlockingTops[column];
        if (top == HEIGHT_NOT_READ) {
            top = HEIGHT_UNKNOWN;
            ClientLevel current = level;
            int x = baseX + lx, z = baseZ + lz;
            LevelChunk chunk = current == null ? null
                    : current.getChunkSource().getChunk(
                            SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z),
                            ChunkStatus.FULL, false);
            if (chunk != null && chunk.hasPrimedHeightmap(Heightmap.Types.MOTION_BLOCKING)) {
                top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x & 15, z & 15);
            }
            motionBlockingTops[column] = top;
        }
        return top;
    }

    /** Whether precipitation at the given block falls as rain, as vanilla decides it per biome and height. */
    private boolean rainsAt(int x, int y, int z) {
        ClientLevel current = level;
        if (current == null) return false;
        rainPos.set(x, y, z);
        return current.getBiome(rainPos).value().getPrecipitationAt(rainPos, current.getSeaLevel())
                == Biome.Precipitation.RAIN;
    }

    /**
     * Whether a quad vanilla put on the translucent layer stays translucent for Remix:
     * only when its sprite reads as a see-through surface
     * ({@link BlockTextureManager#readsAsTranslucent(int, int, int)}). A sprite the
     * manager has not scanned keeps vanilla's layer.
     */
    private boolean readsAsTranslucent(@Nullable Identifier spriteId) {
        BlockTextureManager.SpriteEntry entry = texManager != null && spriteId != null
                ? texManager.getSpriteById(spriteId) : null;
        return entry == null || entry.readsAsTranslucent;
    }

    /** Writes the unit normal of the quad in {@code tempPositions} into {@code tempNormal}. */
    private void computeGeometricNormal() {
        float e1x = tempPositions[3] - tempPositions[0], e1y = tempPositions[4] - tempPositions[1], e1z = tempPositions[5] - tempPositions[2];
        float e2x = tempPositions[6] - tempPositions[0], e2y = tempPositions[7] - tempPositions[1], e2z = tempPositions[8] - tempPositions[2];
        float nx = e1y * e2z - e1z * e2y;
        float ny = e1z * e2x - e1x * e2z;
        float nz = e1x * e2y - e1y * e2x;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len > 0.001f) { nx /= len; ny /= len; nz /= len; }
        tempNormal[0] = nx;
        tempNormal[1] = ny;
        tempNormal[2] = nz;
    }

    private static final Identifier WATER_STILL_SPRITE = Identifier.withDefaultNamespace("block/water_still");
    private static final Identifier WATER_FLOW_SPRITE = Identifier.withDefaultNamespace("block/water_flow");

    /** A block-model face painted with the water texture: the cauldron's water level is the vanilla case. */
    /**
     * Where block {@code block} starts within the water's world-space tile, 0 to 1. The water
     * normals play as a sprite sheet, and a coordinate outside one tile reaches into another
     * frame of the sheet, so each tile would show a different moment and its waves would not
     * meet its neighbours'.
     */
    static float waterTileCoordinate(int block, float tileSize) {
        return Math.floorMod(block, (int) tileSize) / tileSize;
    }

    static boolean isWaterSprite(Identifier spriteId) {
        return WATER_STILL_SPRITE.equals(spriteId) || WATER_FLOW_SPRITE.equals(spriteId);
    }

    /**
     * Emits the staged quad in {@code tempPositions} as RTX water instead of an
     * atlas-textured face, so a cauldron's water matches the lake outside. The
     * water material tiles in world space, so the UVs come from the vertex
     * positions along the face's dominant axes, exactly as the fluid path maps
     * its top and side faces.
     */
    private void emitBlockWaterQuad(float nx, float ny, float nz, int color, String layerName) {
        float tileSize = BlockTextureManager.getWaterTileSize();
        float ax = Math.abs(nx), ay = Math.abs(ny), az = Math.abs(nz);
        float minU = Float.MAX_VALUE, minW = Float.MAX_VALUE;
        for (int v = 0; v < 4; v++) {
            float wx = baseX + tempPositions[v * 3];
            float wy = baseY + tempPositions[v * 3 + 1];
            float wz = baseZ + tempPositions[v * 3 + 2];
            float u, w;
            if (ay >= ax && ay >= az) {
                u = wx; w = wz;
            } else if (ax >= az) {
                u = wz; w = wy;
            } else {
                u = wx; w = wy;
            }
            tempUvs[v * 2] = u;
            tempUvs[v * 2 + 1] = w;
            minU = Math.min(minU, u);
            minW = Math.min(minW, w);
        }
        // Measured from the block the face starts in, so the whole face stays inside one tile.
        int blockU = (int) Math.floor(minU + 1.0e-4f);
        int blockW = (int) Math.floor(minW + 1.0e-4f);
        float originU = waterTileCoordinate(blockU, tileSize);
        float originW = waterTileCoordinate(blockW, tileSize);
        for (int v = 0; v < 4; v++) {
            tempUvs[v * 2] = originU + (tempUvs[v * 2] - blockU) / tileSize;
            tempUvs[v * 2 + 1] = originW + (tempUvs[v * 2 + 1] - blockW) / tileSize;
        }
        writer.writeFluidQuad(tempPositions, tempUvs, nx, ny, nz, color,
                null, 0, 0, 1, 1, layerName, texManager);
    }

    // --- FRAPI model path ---

    private BlockState frapiState;
    private BlockAndTintGetter frapiWorld;
    private @Nullable String frapiLayerOverride;
    private float frapiX, frapiY, frapiZ;
    private SpriteFinder frapiSpriteFinder;

    /**
     * Receives every quad a FRAPI model emits for the block being meshed. Created
     * lazily, once per worker, so a session with no modded models never asks the
     * renderer for one.
     */
    private QuadEmitter frapiEmitter;

    private final Predicate<@Nullable Direction> frapiCullTest =
            dir -> dir != null && !shouldDrawBlockSide(frapiWorld, frapiState, dir);

    private void emitFrapiBlock(BlockAndTintGetter world, BlockState state,
                                 BlockStateModel model, @Nullable String layerOverride,
                                 float lx, float ly, float lz) {
        if (frapiEmitter == null) {
            frapiEmitter = Renderer.get().quadEmitter(this::captureFrapiQuad);
        }
        frapiState = state;
        frapiWorld = world;
        frapiLayerOverride = layerOverride;
        frapiX = lx;
        frapiY = ly;
        frapiZ = lz;
        frapiSpriteFinder = blockSpriteFinder();

        random.setSeed(modelRandomSeed);
        model.emitQuads(frapiEmitter, world, mutablePos, state, random, frapiCullTest);

        writer.flushBlock(texManager);
        frapiState = null;
        frapiWorld = null;
    }

    private void captureFrapiQuad(MutableQuadView quad) {
        // The cull test is only advisory to the model, so a quad on a culled side
        // can still arrive here.
        Direction cull = quad.cullFace();
        if (cull != null && frapiCullTest.test(cull)) {
            return;
        }

        setFacePush(cull != null && meetsNeighbourFace(frapiWorld, frapiState, cull) ? cull : null);
        for (int v = 0; v < 4; v++) {
            tempPositions[v * 3]     = quad.x(v) + frapiX + facePushX;
            tempPositions[v * 3 + 1] = quad.y(v) + frapiY + facePushY;
            tempPositions[v * 3 + 2] = quad.z(v) + frapiZ + facePushZ;
            tempUvs[v * 2]     = quad.u(v);
            tempUvs[v * 2 + 1] = quad.v(v);
        }

        Vector3fc faceNormal = quad.faceNormal();
        float nx = faceNormal.x(), ny = faceNormal.y(), nz = faceNormal.z();

        int color = quad.color(0);
        int tintIndex = quad.tintIndex();
        if (tintIndex >= 0 && blockColors != null) {
            int tint = getBlockTint(frapiState, frapiWorld, tintIndex);
            color = multiplyRgb(color, tint);
        }
        String layerName = frapiLayerOverride != null
                ? frapiLayerOverride
                : layerName(quad.chunkLayer());

        TextureAtlasSprite sprite = frapiSpriteFinder != null ? frapiSpriteFinder.find(quad) : null;
        Identifier spriteId = sprite != null ? sprite.contents().name() : null;
        if (frapiLayerOverride == null && quad.chunkLayer() == ChunkSectionLayer.TRANSLUCENT
                && !readsAsTranslucent(spriteId)) {
            layerName = "cutout";
        }
        float su0 = sprite != null ? sprite.getU0() : 0;
        float sv0 = sprite != null ? sprite.getV0() : 0;
        float su1 = sprite != null ? sprite.getU1() : 1;
        float sv1 = sprite != null ? sprite.getV1() : 1;

        writer.stageQuad(tempPositions, tempUvs, nx, ny, nz, color,
                spriteId, su0, sv0, su1, sv1, layerName);
    }

    private static SpriteFinder blockSpriteFinder() {
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            return null;
        }
        return client.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS) instanceof TextureAtlas atlas
                ? atlas.spriteFinder()
                : null;
    }

    private static int multiplyRgb(int argb, int tint) {
        int r = ((argb >> 16) & 0xFF) * ((tint >> 16) & 0xFF) / 255;
        int g = ((argb >> 8) & 0xFF) * ((tint >> 8) & 0xFF) / 255;
        int b = (argb & 0xFF) * (tint & 0xFF) / 255;
        return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    /**
     * Whether the model and every part it picked for this block are vanilla's own,
     * so their quads are exactly what {@link BlockStateModelPart#getQuads} returns.
     * A modded model or part may emit different quads through FRAPI.
     */
    private static boolean isVanillaModel(BlockStateModel model, List<BlockStateModelPart> parts) {
        if (!VANILLA_CLASS.get(model.getClass())) {
            return false;
        }
        for (int p = 0, partCount = parts.size(); p < partCount; p++) {
            if (!VANILLA_CLASS.get(parts.get(p).getClass())) {
                return false;
            }
        }
        return true;
    }

    private static final ClassValue<Boolean> VANILLA_CLASS = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return type.getName().startsWith("net.minecraft.");
        }
    };

    // --- Fluid geometry ---

    private void emitFluidQuads(BlockAndTintGetter world, FluidState fluidState,
                                 int lx, int ly, int lz) {
        FluidModel model = fluidModelSet.get(fluidState);

        fluidPos.set(baseX + lx, baseY + ly, baseZ + lz);

        boolean isLava = fluidState.is(FluidTags.LAVA);
        boolean isWater = !isLava;
        String layer = isLava ? "solid" : "translucent";
        int fluidColor = isWater
                ? getCachedWaterColor(model, world, lx, ly, lz)
                : fluidTint(model, world);
        int r = (fluidColor >> 16) & 0xFF, g = (fluidColor >> 8) & 0xFF, b = fluidColor & 0xFF;
        int color = 0xFF000000 | (r << 16) | (g << 8) | b;

        float h00 = getFluidHeight(world, fluidState, baseX + lx, baseY + ly, baseZ + lz);
        float h10 = getFluidHeight(world, fluidState, baseX + lx + 1, baseY + ly, baseZ + lz);
        float h01 = getFluidHeight(world, fluidState, baseX + lx, baseY + ly, baseZ + lz + 1);
        float h11 = getFluidHeight(world, fluidState, baseX + lx + 1, baseY + ly, baseZ + lz + 1);

        float tileSize = BlockTextureManager.getWaterTileSize();
        int wx = baseX + lx, wy = baseY + ly, wz = baseZ + lz;

        TextureAtlasSprite stillSprite = model.stillMaterial().sprite();
        TextureAtlasSprite flowSprite = model.flowingMaterial().sprite();

        Identifier stillId = stillSprite.contents().name();
        float su0 = stillSprite.getU0(), sv0 = stillSprite.getV0();
        float su1 = stillSprite.getU1(), sv1 = stillSprite.getV1();

        Identifier flowId = flowSprite.contents().name();
        float fu0 = flowSprite.getU0(), fv0 = flowSprite.getV0();
        float fu1 = flowSprite.getU1(), fv1 = flowSprite.getV1();

        fluidCheckPos.set(fluidPos.getX(), fluidPos.getY() + 1, fluidPos.getZ());
        FluidState aboveFluid = getCachedFluidState(world,
                fluidCheckPos.getX(), fluidCheckPos.getY(), fluidCheckPos.getZ());
        boolean submerged = aboveFluid.getType().isSame(fluidState.getType());
        boolean waterTopCovered = false;
        if (isWater && !submerged && fluidState.isSource()) {
            BlockState aboveState = getCachedBlockState(world,
                    fluidCheckPos.getX(), fluidCheckPos.getY(), fluidCheckPos.getZ());
            float topCoverageHeight = Math.min(Math.min(h00, h10), Math.min(h01, h11));
            if (isFluidTopCoveredByCeiling(world, topCoverageHeight, fluidCheckPos, aboveState)) {
                waterTopCovered = classifyWaterTopExposure(world, fluidState, wx, wy, wz)
                        == FluidTopOcclusion.Exposure.HIDDEN;
            }
        }

        // Top face
        if (!submerged && !waterTopCovered) {
            setPos4(tempPositions, lx, ly + h00, lz, lx + 1, ly + h10, lz,
                    lx + 1, ly + h11, lz + 1, lx, ly + h01, lz + 1);
            if (isWater) {
                float u0 = waterTileCoordinate(wx, tileSize), v0 = waterTileCoordinate(wz, tileSize);
                float u1 = u0 + 1 / tileSize, v1 = v0 + 1 / tileSize;
                setUv4(tempUvs, u0, v0, u1, v0, u1, v1, u0, v1);
                writer.writeFluidQuad(tempPositions, tempUvs, 0, 1, 0, color,
                        null, 0, 0, 1, 1, layer, texManager);
                setPos4(tempPositions, lx, ly + h01, lz + 1, lx + 1, ly + h11, lz + 1,
                        lx + 1, ly + h10, lz, lx, ly + h00, lz);
                writer.writeFluidQuad(tempPositions, tempUvs, 0, -1, 0, color,
                        null, 0, 0, 1, 1, layer, texManager);
            } else {
                // Vanilla FluidRenderer: a still surface shows the still sprite in
                // full; a moving one shows the centre quarter of the flowing sprite
                // (two tiles across, so a quarter is one tile) rotated to the flow.
                Vec3 flow = fluidState.getFlow(world, fluidPos);
                if (flow.x == 0.0 && flow.z == 0.0) {
                    setUv4(tempUvs, su0, sv0, su1, sv0, su1, sv1, su0, sv1);
                    writer.writeFluidQuad(tempPositions, tempUvs, 0, 1, 0, color,
                            stillId, su0, sv0, su1, sv1, layer, texManager);
                    setPos4(tempPositions, lx, ly + h01, lz + 1, lx + 1, ly + h11, lz + 1,
                            lx + 1, ly + h10, lz, lx, ly + h00, lz);
                    writer.writeFluidQuad(tempPositions, tempUvs, 0, -1, 0, color,
                            stillId, su0, sv0, su1, sv1, layer, texManager);
                } else {
                    float angle = (float) Mth.atan2(flow.z, flow.x) - (float) (Math.PI / 2.0);
                    float sin = Mth.sin(angle) * 0.25f;
                    float cos = Mth.cos(angle) * 0.25f;
                    // Vanilla's corners in its order (0,0) (0,1) (1,1) (1,0), reordered to
                    // this emitter's (0,0) (1,0) (1,1) (0,1).
                    float c00u = flowSprite.getU(0.5f - cos - sin), c00v = flowSprite.getV(0.5f - cos + sin);
                    float c01u = flowSprite.getU(0.5f - cos + sin), c01v = flowSprite.getV(0.5f + cos + sin);
                    float c11u = flowSprite.getU(0.5f + cos + sin), c11v = flowSprite.getV(0.5f + cos - sin);
                    float c10u = flowSprite.getU(0.5f + cos - sin), c10v = flowSprite.getV(0.5f - cos - sin);
                    setUv4(tempUvs, c00u, c00v, c10u, c10v, c11u, c11v, c01u, c01v);
                    writer.writeFluidQuad(tempPositions, tempUvs, 0, 1, 0, color,
                            flowId, fu0, fv0, fu1, fv1, layer, texManager);
                    setPos4(tempPositions, lx, ly + h01, lz + 1, lx + 1, ly + h11, lz + 1,
                            lx + 1, ly + h10, lz, lx, ly + h00, lz);
                    setUv4(tempUvs, c01u, c01v, c11u, c11v, c10u, c10v, c00u, c00v);
                    writer.writeFluidQuad(tempPositions, tempUvs, 0, -1, 0, color,
                            flowId, fu0, fv0, fu1, fv1, layer, texManager);
                }
            }
        }

        // Bottom face
        fluidCheckPos.set(fluidPos.getX(), fluidPos.getY() - 1, fluidPos.getZ());
        FluidState belowFluid = getCachedFluidState(world,
                fluidCheckPos.getX(), fluidCheckPos.getY(), fluidCheckPos.getZ());
        BlockState belowState = getCachedBlockState(world,
                fluidCheckPos.getX(), fluidCheckPos.getY(), fluidCheckPos.getZ());
        if (!belowFluid.getType().isSame(fluidState.getType()) && !belowState.isSolidRender()) {
            setPos4(tempPositions, lx, ly, lz + 1, lx + 1, ly, lz + 1,
                    lx + 1, ly, lz, lx, ly, lz);
            if (isWater) {
                float u0 = waterTileCoordinate(wx, tileSize), v0 = waterTileCoordinate(wz, tileSize);
                float u1 = u0 + 1 / tileSize, v1 = v0 + 1 / tileSize;
                setUv4(tempUvs, u0, v1, u1, v1, u1, v0, u0, v0);
                writer.writeFluidQuad(tempPositions, tempUvs, 0, -1, 0, color,
                        null, 0, 0, 1, 1, layer, texManager);
            } else {
                setUv4(tempUvs, su0, sv0, su1, sv0, su1, sv1, su0, sv1);
                writer.writeFluidQuad(tempPositions, tempUvs, 0, -1, 0, color,
                        stillId, su0, sv0, su1, sv1, layer, texManager);
            }
        }

        // Side faces
        for (Direction dir : HORIZONTAL_DIRECTIONS) {
            fluidCheckPos.set(
                fluidPos.getX() + dir.getStepX(),
                fluidPos.getY(),
                fluidPos.getZ() + dir.getStepZ());
            FluidState sideFluid = getCachedFluidState(world,
                    fluidCheckPos.getX(), fluidCheckPos.getY(), fluidCheckPos.getZ());
            BlockState sideState = getCachedBlockState(world,
                    fluidCheckPos.getX(), fluidCheckPos.getY(), fluidCheckPos.getZ());

            if (sideFluid.getType().isSame(fluidState.getType())) continue;
            if (sideState.isSolidRender()) continue;

            float sideH0, sideH1;
            switch (dir) {
                case NORTH -> {
                    sideH0 = h10; sideH1 = h00;
                    setPos4(tempPositions, lx + 1, ly, lz, lx, ly, lz,
                            lx, ly + sideH1, lz, lx + 1, ly + sideH0, lz);
                }
                case SOUTH -> {
                    sideH0 = h01; sideH1 = h11;
                    setPos4(tempPositions, lx, ly, lz + 1, lx + 1, ly, lz + 1,
                            lx + 1, ly + sideH1, lz + 1, lx, ly + sideH0, lz + 1);
                }
                case EAST -> {
                    sideH0 = h11; sideH1 = h10;
                    setPos4(tempPositions, lx + 1, ly, lz + 1, lx + 1, ly, lz,
                            lx + 1, ly + sideH1, lz, lx + 1, ly + sideH0, lz + 1);
                }
                case WEST -> {
                    sideH0 = h00; sideH1 = h01;
                    setPos4(tempPositions, lx, ly, lz, lx, ly, lz + 1,
                            lx, ly + sideH1, lz + 1, lx, ly + sideH0, lz);
                }
                default -> { continue; }
            }

            float fnx = dir.getStepX(), fny = 0, fnz = dir.getStepZ();
            if (isWater) {
                boolean xAxis = (dir == Direction.EAST || dir == Direction.WEST);
                float sU0, sU1, sV0, sV1;
                sU0 = waterTileCoordinate(xAxis ? wz : wx, tileSize);
                sU1 = sU0 + 1 / tileSize;
                sV0 = waterTileCoordinate(wy, tileSize);
                sV1 = sV0 + 1 / tileSize;
                setUv4(tempUvs, sU0, sV1, sU1, sV1, sU1, sV0, sU0, sV0);
                writer.writeFluidQuad(tempPositions, tempUvs, fnx, fny, fnz, color,
                        null, 0, 0, 1, 1, layer, texManager);
            } else {
                // Vanilla FluidRenderer side mapping: one tile is half the flowing
                // sprite, so U spans its left half and V runs from the fluid surface
                // ((1 - height) / 2) down to the half-way line. The full sprite over a
                // one-block face showed the texture at twice the density.
                float sideU0 = flowSprite.getU(0.0f);
                float sideU1 = flowSprite.getU(0.5f);
                float sideVBottom = flowSprite.getV(0.5f);
                float sideVTop0 = flowSprite.getV((1.0f - sideH0) * 0.5f);
                float sideVTop1 = flowSprite.getV((1.0f - sideH1) * 0.5f);
                setUv4(tempUvs, sideU0, sideVBottom, sideU1, sideVBottom, sideU1, sideVTop1, sideU0, sideVTop0);
                writer.writeFluidQuad(tempPositions, tempUvs, fnx, fny, fnz, color,
                        flowId, fu0, fv0, fu1, fv1, layer, texManager);
            }
        }
    }

    static boolean isFluidTopCoveredByCeiling(BlockGetter world, float fluidHeight,
                                              BlockPos neighborPos, BlockState neighborState) {
        if (fluidHeight + WATER_HEIGHT_EPSILON < WATER_CEILING_CULL_MIN_HEIGHT
                || !neighborState.canOcclude()) {
            return false;
        }
        if (neighborState.isSolidRender()) {
            return true;
        }

        VoxelShape neighborShape = neighborState.getOcclusionShape();
        return isFluidSideCovered(Direction.UP, 1.0f, true, neighborShape);
    }

    static boolean isFluidTopCoveredByCeiling(float fluidHeight,
                                              boolean neighborOpaque, VoxelShape neighborShape) {
        if (fluidHeight + WATER_HEIGHT_EPSILON < WATER_CEILING_CULL_MIN_HEIGHT) return false;

        return isFluidSideCovered(Direction.UP, 1.0f, neighborOpaque, neighborShape);
    }

    static boolean isFluidSideCovered(Direction direction, float fluidHeight,
                                      boolean neighborOpaque, VoxelShape neighborShape) {
        if (!neighborOpaque) return false;

        VoxelShape fluidShape = Shapes.box(0.0, 0.0, 0.0, 1.0, fluidHeight, 1.0);
        return Shapes.blockOccludes(fluidShape, neighborShape, direction);
    }

    private FluidTopOcclusion.Exposure classifyWaterTopExposure(
            BlockAndTintGetter world, FluidState referenceFluid, int worldX, int worldY, int worldZ) {
        if (getCachedBlockState(world, worldX, worldY, worldZ).canOcclude()) {
            return FluidTopOcclusion.Exposure.EXPOSED;
        }

        if (waterTopCellCacheFluid == null
                || !waterTopCellCacheFluid.getType().isSame(referenceFluid.getType())) {
            Arrays.fill(waterTopCellCache, FluidTopOcclusion.UNKNOWN);
            waterTopCellCacheFluid = referenceFluid;
        }

        int originCacheIndex = waterTopCellCacheIndex(worldX, worldY, worldZ);
        if (originCacheIndex >= 0) {
            // The caller already established that this still-fluid origin has
            // a covering ceiling, so do not repeat its shape comparison.
            waterTopCellCache[originCacheIndex] = FluidTopOcclusion.CONNECTED;
        }

        waterTopCullWorld = world;
        waterTopCullReferenceFluid = referenceFluid;
        waterTopCullOriginX = worldX;
        waterTopCullOriginY = worldY;
        waterTopCullOriginZ = worldZ;
        try {
            return FluidTopOcclusion.classifyConnectedOrigin(
                    WATER_TOP_CULL_RADIUS, waterTopCullStack, waterTopCellClassifier);
        } finally {
            waterTopCullWorld = null;
            waterTopCullReferenceFluid = null;
        }
    }

    private byte classifyWaterTopCellAtOffset(int xOffset, int zOffset) {
        int worldX = waterTopCullOriginX + xOffset;
        int worldY = waterTopCullOriginY;
        int worldZ = waterTopCullOriginZ + zOffset;
        int cacheIndex = waterTopCellCacheIndex(worldX, worldY, worldZ);
        if (cacheIndex < 0) {
            return classifyWaterTopCell(
                    waterTopCullWorld, waterTopCullReferenceFluid, worldX, worldY, worldZ);
        }

        byte classification = waterTopCellCache[cacheIndex];
        if (classification == FluidTopOcclusion.UNKNOWN) {
            classification = classifyWaterTopCell(
                    waterTopCullWorld, waterTopCullReferenceFluid, worldX, worldY, worldZ);
            waterTopCellCache[cacheIndex] = classification;
        }
        return classification;
    }

    private int waterTopCellCacheIndex(int worldX, int worldY, int worldZ) {
        int x = worldX - baseX + WATER_TOP_CULL_RADIUS;
        int y = worldY - baseY;
        int z = worldZ - baseZ + WATER_TOP_CULL_RADIUS;
        if ((x | y | z) < 0
                || x >= WATER_TOP_CACHE_WIDTH
                || y >= SECTION_SIZE
                || z >= WATER_TOP_CACHE_WIDTH) {
            return -1;
        }
        return (y * WATER_TOP_CACHE_WIDTH + z) * WATER_TOP_CACHE_WIDTH + x;
    }

    private byte classifyWaterTopCell(BlockAndTintGetter world, FluidState referenceFluid,
                                      int worldX, int worldY, int worldZ) {
        BlockState state = getCachedBlockState(world, worldX, worldY, worldZ);
        if (state.canOcclude()) {
            return FluidTopOcclusion.BLOCKED;
        }

        FluidState fluid = getCachedFluidState(world, worldX, worldY, worldZ);
        if (!fluid.getType().isSame(referenceFluid.getType()) || !fluid.isSource()) {
            return FluidTopOcclusion.EXPOSED;
        }

        FluidState aboveFluid = getCachedFluidState(world, worldX, worldY + 1, worldZ);
        if (aboveFluid.getType().isSame(referenceFluid.getType())) {
            return FluidTopOcclusion.BLOCKED;
        }

        fluidCheckPos.set(worldX, worldY + 1, worldZ);
        BlockState aboveState = getCachedBlockState(world, worldX, worldY + 1, worldZ);
        return isFluidTopCoveredByCeiling(world, fluid.getOwnHeight(), fluidCheckPos, aboveState)
                ? FluidTopOcclusion.CONNECTED
                : FluidTopOcclusion.EXPOSED;
    }

    private float getFluidHeight(BlockAndTintGetter world, FluidState referenceFluid,
                                  int worldX, int worldY, int worldZ) {
        float totalHeight = 0;
        int count = 0;

        for (int dx = -1; dx <= 0; dx++) {
            for (int dz = -1; dz <= 0; dz++) {
                fluidCheckPos.set(worldX + dx, worldY + 1, worldZ + dz);
                FluidState aboveFluid = getCachedFluidState(world,
                        fluidCheckPos.getX(), fluidCheckPos.getY(), fluidCheckPos.getZ());
                if (aboveFluid.getType().isSame(referenceFluid.getType())) {
                    return 1.0f;
                }

                fluidCheckPos.set(worldX + dx, worldY, worldZ + dz);
                FluidState fs = getCachedFluidState(world,
                        fluidCheckPos.getX(), fluidCheckPos.getY(), fluidCheckPos.getZ());
                if (fs.getType().isSame(referenceFluid.getType())) {
                    float h = fs.getOwnHeight();
                    totalHeight += h;
                    count++;
                } else if (!getCachedBlockState(world,
                        fluidCheckPos.getX(), fluidCheckPos.getY(), fluidCheckPos.getZ()).isSolid()) {
                    count++;
                }
            }
        }

        return count > 0 ? totalHeight / count : 0.5f;
    }

    private int getCachedWaterColor(FluidModel model, BlockAndTintGetter world,
                                    int lx, int ly, int lz) {
        int cell = ((ly >> 3) << 4) | ((lz >> 2) << 2) | (lx >> 2);
        int bit = 1 << cell;
        if ((waterColorKnownMask & bit) == 0) {
            waterColorCache[cell] = fluidTint(model, world);
            waterColorKnownMask |= bit;
        }
        return waterColorCache[cell];
    }

    /** The fluid model's tint at {@code fluidPos}, white for an untinted fluid such as lava. */
    private int fluidTint(FluidModel model, BlockAndTintGetter world) {
        BlockTintSource tintSource = model.tintSource();
        if (tintSource == null) {
            return 0xFFFFFFFF;
        }
        return tintSource.colorInWorld(getCachedBlockState(world,
                fluidPos.getX(), fluidPos.getY(), fluidPos.getZ()), world, fluidPos);
    }

    // --- Helpers ---

    private boolean shouldDrawBlockSide(BlockAndTintGetter world, BlockState state, Direction side) {
        return shouldRenderFace(state, neighbourState(world, side), side);
    }

    private BlockState neighbourState(BlockAndTintGetter world, Direction side) {
        return getCachedBlockState(world,
                mutablePos.getX() + side.getStepX(),
                mutablePos.getY() + side.getStepY(),
                mutablePos.getZ() + side.getStepZ());
    }

    /**
     * Whether the current block's face on {@code side} is see-through and lies on a face
     * its neighbour draws too, so the two share a plane.
     *
     * <p>Vanilla never sees such a pair fight: it culls back faces, so from either side
     * only the face toward the viewer is drawn. Remix draws terrain double-sided, and
     * looking through leaves or glass at a slab the see-through block's far face lies
     * exactly on the slab's side. When a ray meets a cut-out texel the runtime restarts
     * it a little beyond the surface, an offset that grows with the world position, so a
     * ray that reached the leaf's texel first carries straight past the slab and the side
     * shows as a hole, depending on the view. Moving the see-through face
     * {@link #SHARED_FACE_PUSH} into the neighbour puts the neighbour's face first from
     * this side, as vanilla shows it, and leaves the see-through face in front of anyone
     * looking from the neighbour's side. When both blocks are see-through each face moves
     * into the other, so from either side the far block's face is the one met first.
     */
    private boolean meetsNeighbourFace(BlockAndTintGetter world, BlockState state, Direction side) {
        int sideBit = 1 << side.ordinal();
        if ((sharedSidesTested & sideBit) == 0) {
            sharedSidesTested |= sideBit;
            if (!state.canOcclude() && meetsNeighbourFace(state, neighbourState(world, side), side)) {
                sharedSides |= sideBit;
            }
        }
        return (sharedSides & sideBit) != 0;
    }

    /**
     * Whether a drawn face of see-through {@code state} on {@code side} meets a face
     * {@code neighbour} draws. Cheap without a cache: {@code state} does not occlude, so
     * vanilla's test returns before it compares shapes.
     */
    static boolean meetsNeighbourFace(BlockState state, BlockState neighbour, Direction side) {
        return !state.canOcclude()
                && getRenderTypeCached(neighbour) == RenderShape.MODEL
                && Block.shouldRenderFace(neighbour, state, side.getOpposite());
    }

    private void setFacePush(@Nullable Direction side) {
        if (side == null) {
            facePushX = facePushY = facePushZ = 0f;
        } else {
            facePushX = side.getStepX() * SHARED_FACE_PUSH;
            facePushY = side.getStepY() * SHARED_FACE_PUSH;
            facePushZ = side.getStepZ() * SHARED_FACE_PUSH;
        }
    }

    private boolean shouldRenderFace(BlockState state, BlockState neighbor, Direction side) {
        long key = ((long) Block.getId(state) << 35)
                | ((long) Block.getId(neighbor) << 3)
                | side.ordinal();
        int slot = (int) ((key * 0x9E3779B97F4A7C15L) >>> (64 - FACE_CULL_CACHE_BITS));
        if (faceCullKeys[slot] == key && faceCullResults[slot] != FACE_CULL_UNKNOWN) {
            return faceCullResults[slot] == FACE_CULL_DRAWN;
        }

        boolean drawn = Block.shouldRenderFace(state, neighbor, side);
        faceCullKeys[slot] = key;
        faceCullResults[slot] = drawn ? FACE_CULL_DRAWN : FACE_CULL_HIDDEN;
        return drawn;
    }

    private BlockState getCachedBlockState(BlockGetter world, int worldX, int worldY, int worldZ) {
        int lx = worldX - baseX;
        int ly = worldY - baseY;
        int lz = worldZ - baseZ;
        if ((lx | ly | lz) < 0 || lx >= SECTION_SIZE || ly >= SECTION_SIZE || lz >= SECTION_SIZE) {
            neighborPos.set(worldX, worldY, worldZ);
            return world.getBlockState(neighborPos);
        }

        int idx = localIndex(lx, ly, lz);
        int word = idx >> 6;
        long bit = 1L << (idx & 63);
        if ((blockStateKnown[word] & bit) != 0) {
            return blockStateCache[idx];
        }

        neighborPos.set(worldX, worldY, worldZ);
        BlockState state = world.getBlockState(neighborPos);
        cacheLocalBlockState(idx, state);
        return state;
    }

    private FluidState getCachedFluidState(BlockGetter world, int worldX, int worldY, int worldZ) {
        int lx = worldX - baseX;
        int ly = worldY - baseY;
        int lz = worldZ - baseZ;
        if ((lx | ly | lz) < 0 || lx >= SECTION_SIZE || ly >= SECTION_SIZE || lz >= SECTION_SIZE) {
            neighborPos.set(worldX, worldY, worldZ);
            return world.getFluidState(neighborPos);
        }

        int idx = localIndex(lx, ly, lz);
        int word = idx >> 6;
        long bit = 1L << (idx & 63);
        if ((fluidStateKnown[word] & bit) != 0) {
            return fluidStateCache[idx];
        }

        FluidState fluidState = getCachedBlockState(world, worldX, worldY, worldZ).getFluidState();
        cacheLocalFluidState(idx, fluidState);
        return fluidState;
    }

    private void cacheLocalBlockState(int idx, BlockState state) {
        blockStateCache[idx] = state;
        blockStateKnown[idx >> 6] |= 1L << (idx & 63);
    }

    private void cacheLocalFluidState(int idx, FluidState fluidState) {
        fluidStateCache[idx] = fluidState;
        fluidStateKnown[idx >> 6] |= 1L << (idx & 63);
    }

    private static int localIndex(int lx, int ly, int lz) {
        return (ly << 8) | (lz << 4) | lx;
    }

    private int getBlockTint(BlockState state, BlockAndTintGetter world, int colorIndex) {
        if (colorIndex < 0 || colorIndex >= TINT_CACHE_SIZE) {
            return computeBlockTint(state, world, colorIndex);
        }

        int bit = 1 << colorIndex;
        if ((tintValidMask & bit) == 0) {
            tintCache[colorIndex] = computeBlockTint(state, world, colorIndex);
            tintValidMask |= bit;
        }
        return tintCache[colorIndex];
    }

    /** Vanilla's tint for one tint index, white where the block has no source for it. */
    private int computeBlockTint(BlockState state, BlockAndTintGetter world, int colorIndex) {
        BlockTintSource tintSource = blockColors.getTintSource(state, colorIndex);
        return tintSource != null ? tintSource.colorInWorld(state, world, mutablePos) : 0xFFFFFFFF;
    }

    /**
     * The terrain layer every quad of a block is bucketed under regardless of its
     * own, or null when each quad keeps its own layer.
     *
     * <p>Vanilla draws clear glass as a cutout: the texture is alpha 0 between
     * the frame pixels and the GL path simply discards those. Remix can refract
     * through it instead, so clear glass takes its own layer and a translucent
     * material with the frame as a diffuse layer. Stained glass is already
     * translucent and keeps vanilla's layer. Leaves follow vanilla's Cutout
     * Leaves option, which draws them solid when off.
     */
    static @Nullable String blockLayerOverride(BlockState state, boolean cutoutLeaves) {
        if (state.is(Blocks.GLASS) || state.is(Blocks.GLASS_PANE)) return "glass";
        if (ModelBlockRenderer.forceOpaque(cutoutLeaves, state)) return "solid";
        return null;
    }

    /** The {@link BlockTextureManager#layerType} name a quad on a vanilla chunk layer takes. */
    static String layerName(ChunkSectionLayer layer) {
        return switch (layer) {
            case SOLID -> "solid";
            case CUTOUT -> "cutout";
            case TRANSLUCENT -> "translucent";
        };
    }

    /**
     * The terrain layer a block's quads are bucketed under, by
     * {@link BlockTextureManager#layerType} name. A held block item asks the same
     * question, so it shades as its placed counterpart does.
     *
     * <p>Vanilla picks a layer per quad from its sprite since 26.1, so a whole
     * block is translucent when any of its quads is and shows a see-through sprite
     * there, as terrain decides quad by quad; otherwise cutout when it has translucent
     * quads or its particle sprite has transparent pixels, and solid otherwise.
     */
    public static String classifyBlockLayer(BlockState state) {
        Minecraft client = Minecraft.getInstance();
        String override = blockLayerOverride(state, client.options.cutoutLeaves().get());
        if (override != null) return override;
        BlockStateModel model = client.getModelManager().getBlockStateModelSet().get(state);
        if (model.hasMaterialFlag(BakedQuad.FLAG_TRANSLUCENT)) {
            BlockTextureManager textures = BlockTextureManager.get();
            return textures == null || textures.hasSeeThroughQuads(state, model) ? "translucent" : "cutout";
        }
        if (model.particleMaterial().sprite().transparency().hasTransparent()) return "cutout";
        return "solid";
    }

    private static void setPos4(float[] out,
                                float x0, float y0, float z0,
                                float x1, float y1, float z1,
                                float x2, float y2, float z2,
                                float x3, float y3, float z3) {
        out[0] = x0; out[1] = y0; out[2] = z0;
        out[3] = x1; out[4] = y1; out[5] = z1;
        out[6] = x2; out[7] = y2; out[8] = z2;
        out[9] = x3; out[10] = y3; out[11] = z3;
    }

    private static void setUv4(float[] out,
                                float u0, float v0, float u1, float v1,
                                float u2, float v2, float u3, float v3) {
        out[0] = u0; out[1] = v0; out[2] = u1; out[3] = v1;
        out[4] = u2; out[5] = v2; out[6] = u3; out[7] = v3;
    }
}
