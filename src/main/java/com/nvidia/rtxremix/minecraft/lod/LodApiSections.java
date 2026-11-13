/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.lod;

import com.nvidia.rtxremix.minecraft.profiling.PerformanceTimer;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LOD sections this mod chooses and builds itself from an LOD mod's block data, for an LOD mod
 * whose renderer it does not capture. The sections follow {@link LodSelection} around the camera;
 * each is read from the {@link Terrain}, meshed by {@link LodColumnMesher} into the LOD mod's
 * vertex layout and handed to {@link LodTerrain} as a {@link LodSection}, which installs it as it
 * installs captured ones. A section replaced at another detail is closed once every section
 * over its ground has been built, so the ground changes detail without a hole.
 */
public final class LodApiSections {

    private static final Logger LOGGER = LoggerFactory.getLogger(LodApiSections.class);

    /**
     * Blocks out to which sections are built, at most: the LOD mod hands out only full-detail
     * columns, so every 64 blocks of ground is read whole once, and the LOD mod's own range of
     * kilometres would take minutes on every join.
     */
    static final int MAX_RADIUS = Integer.getInteger("rtxremix.lod.apiRadius", 4096);
    private static final int BUILD_THREADS = Math.max(1, Integer.getInteger("rtxremix.lod.apiThreads", 2));
    /** Builds handed to the pool at once: enough to keep it busy at the few frames a second of a window in the background. */
    private static final int MAX_IN_FLIGHT = 4 * BUILD_THREADS;
    /** Blocks the camera moves, or frames that pass, before the sections are chosen again. */
    private static final double RESELECT_BLOCKS = 16.0;
    private static final int RESELECT_FRAMES = 60;
    /** Frames a replaced section stays while the sections over its ground are built. */
    private static final int MAX_RETIRE_FRAMES = 1200;
    /** Frames between applying the LOD mod's changed chunks. */
    private static final int MODIFIED_FRAMES = 20;
    /** Frames a section whose data was not there waits before it is read again. */
    private static final int RETRY_FRAMES = 120;

    /** An LOD mod's block data. */
    public interface Terrain {
        /** The LOD mod and its version, for log lines. */
        String describe();

        /** The LOD mod's own render radius, in blocks; 0 before it has started. */
        int radiusBlocks();

        /** Render thread. Follows the client's level; false while the LOD mod has none for it. */
        boolean bind(ClientLevel level);

        /** Render thread. Lets go of the level. */
        void unbind();

        /** The level's floor, the height column runs are measured from. */
        int minY();

        /**
         * Whether the level has a ceiling, as the Nether does: no sky light reaches its air, so
         * nothing of it would be drawn, and reading it only waits on the LOD mod's generation.
         */
        boolean ceiling();

        /** Whether the sky's light reaches the level's air; without it, air hides nothing. */
        boolean skyLit();

        /** The next chunk whose data the LOD mod changed, as {x, z}; null when none waits. Render thread. */
        long @Nullable [] pollModifiedChunk();

        /** A builder thread's reader; null when the LOD mod's data is not available now. */
        @Nullable Reader reader();
    }

    /** Reads columns of the bound level. One builder thread. */
    public interface Reader {
        /**
         * Reads the 64 by 64 block columns of the finest section at {@code sectionX, sectionZ}
         * (block positions over 64), calling {@link ColumnSink#begin} with each column's
         * offset; false when the data is not there.
         */
        boolean area64(int sectionX, int sectionZ, ColumnSink sink);

        /** Reads one column into cell {@code cx, cz}; false when the data is not there. */
        boolean column(int blockX, int blockZ, ColumnSink sink, int cx, int cz);
    }

    /** Takes a column's runs of one block, from the bottom up or in any order. */
    public interface ColumnSink {
        void begin(int cx, int cz);

        /** A run of {@code state} from {@code bottomY} up to {@code topY}, world heights. */
        void run(BlockState state, @Nullable Object biome, int bottomY, int topY, int dhMaterial, boolean liquid);

        /** A run of air from {@code bottomY} up to {@code topY}, with the sky light it holds. */
        void air(int bottomY, int topY, int skyLight);
    }

    private static final class Node {
        final long key;
        final LodSection section;
        boolean wanted = true;
        /** Whether a build of it has published geometry. */
        boolean built;
        boolean queued;
        boolean inFlight;
        /** A rebuild was asked for while one was in flight. */
        boolean again;
        /** The frame before which a section whose data was not there is not read again. */
        int retryAt;
        int retireSince = -1;

        Node(long key, int minY) {
            this.key = key;
            int width = LodSelection.width(LodSelection.detail(key));
            section = new LodSection(LodSelection.minX(key), minY, LodSelection.minZ(key), width);
        }
    }

    private record Finished(long key, int generation, long nanos, boolean ok) {}

    private final Terrain terrain;
    private final ExecutorService pool;
    private final Long2ObjectOpenHashMap<Node> nodes = new Long2ObjectOpenHashMap<>();
    private final LongOpenHashSet desired = new LongOpenHashSet();
    private final List<Node> queue = new ArrayList<>();
    private final ConcurrentLinkedQueue<Finished> finished = new ConcurrentLinkedQueue<>();
    private final LongOpenHashSet modifiedChunks = new LongOpenHashSet();
    private final AtomicLong buildNanos = new AtomicLong();
    private final PerformanceTimer timer;
    private final int cntSections;
    private final int cntBuilds;
    private final int cntQueued;
    private final int cntModified;
    private int frameModified;
    private final ThreadLocal<Worker> workers = ThreadLocal.withInitial(Worker::new);
    /**
     * The builds handed to the pool and not yet finished. The LOD mod's reads wait on its own
     * generation, so a stop interrupts them: reads of a level just left held both threads for
     * minutes after the player came back.
     */
    private final List<Future<?>> running = new ArrayList<>();
    private volatile int generation;
    private volatile BlockTextureManager.SpriteColors sprites = BlockTextureManager.SpriteColors.EMPTY;
    private volatile int minY;
    private volatile boolean skyLit;
    private boolean ceilingLogged;
    private boolean active;
    private int inFlight;
    private int frame;
    private int selectFrame = Integer.MIN_VALUE / 2;
    private int modifiedFrame;
    private double selectX = Double.NaN;
    private double selectZ = Double.NaN;
    private double cameraX;
    private double cameraZ;
    private int frameBuilds;
    private int totalBuilds;
    private boolean startLogged;

    public LodApiSections(Terrain terrain, PerformanceTimer timer) {
        this.terrain = terrain;
        this.timer = timer;
        cntSections = timer.registerCounter("Lod.ApiSections");
        cntBuilds = timer.registerCounter("Lod.ApiBuilds");
        cntQueued = timer.registerCounter("Lod.ApiQueued");
        cntModified = timer.registerCounter("Lod.ApiModified");
        pool = Executors.newFixedThreadPool(BUILD_THREADS, runnable -> {
            Thread thread = new Thread(runnable, "RTX LOD Section Reader");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });
    }

    /** Blocks out to which sections are built. */
    public int radiusBlocks() {
        return Math.min(terrain.radiusBlocks(), MAX_RADIUS);
    }

    /**
     * Render thread, once a frame while the LOD terrain captures. {@code viewChunks} is
     * Minecraft's render distance: sections wholly inside it are not built.
     */
    public void update(Vec3 camera, @Nullable ClientLevel level, BlockTextureManager.SpriteColors colors, int viewChunks) {
        if (level == null || !terrain.bind(level)) {
            if (active) stop();
            return;
        }
        if (terrain.ceiling()) {
            if (active) stop();
            if (!ceilingLogged) {
                ceilingLogged = true;
                LOGGER.info("No LOD terrain from public data under a ceiling, as in {}", level.dimension().identifier());
            }
            return;
        }
        int floor = terrain.minY();
        if (active && floor != minY) stop();
        if (!active) {
            // Bound again after a stop, which let go of the level.
            if (!terrain.bind(level)) return;
            active = true;
            minY = floor;
            skyLit = terrain.skyLit();
            if (!startLogged) {
                startLogged = true;
                LOGGER.info("LOD terrain from {}'s public data, out to {} blocks", terrain.describe(), radiusBlocks());
            }
        }
        sprites = colors;
        frame++;
        cameraX = camera.x;
        cameraZ = camera.z;
        collectFinished();
        collectModified();
        boolean moved = Double.isNaN(selectX) || Math.abs(camera.x - selectX) > RESELECT_BLOCKS
                || Math.abs(camera.z - selectZ) > RESELECT_BLOCKS;
        if (moved || frame - selectFrame >= RESELECT_FRAMES) select(viewChunks);
        startBuilds();
        timer.count(cntSections, nodes.size());
        timer.count(cntBuilds, frameBuilds);
        timer.count(cntQueued, queue.size() + inFlight);
        timer.count(cntModified, frameModified);
        frameBuilds = 0;
        frameModified = 0;
    }

    /** Render thread. Closes every section, as when capture stops or the level goes. */
    public void stop() {
        generation++;
        for (Future<?> build : running) build.cancel(true);
        running.clear();
        for (Node node : nodes.values()) node.section.publishClosed();
        nodes.clear();
        queue.clear();
        inFlight = 0;
        finished.clear();
        modifiedChunks.clear();
        selectX = Double.NaN;
        terrain.unbind();
        active = false;
    }

    /** Render thread, after a resource reload: every section is read again with the new colours. */
    public void rebuildAll() {
        for (Node node : nodes.values()) request(node);
    }

    public void shutdown() {
        stop();
        pool.shutdownNow();
    }

    private void select(int viewChunks) {
        selectFrame = frame;
        selectX = cameraX;
        selectZ = cameraZ;
        int radius = radiusBlocks();
        if (radius <= 0) return;
        int inner = Math.max(0, viewChunks - 3) * 16;
        desired.clear();
        LodSelection.select(cameraX, cameraZ, radius, inner, desired::add);
        for (Node node : nodes.values()) {
            if (!desired.contains(node.key) && node.wanted) {
                node.wanted = false;
                node.retireSince = frame;
            }
        }
        var keys = desired.iterator();
        while (keys.hasNext()) {
            long key = keys.nextLong();
            Node node = nodes.get(key);
            if (node == null) {
                node = new Node(key, minY);
                nodes.put(key, node);
                request(node);
            } else if (!node.wanted) {
                node.wanted = true;
                node.retireSince = -1;
            }
        }
        retire();
        // Nearest first, so the ground around the camera fills in before the horizon.
        queue.sort((a, b) -> Double.compare(distance(a), distance(b)));
    }

    /** Closes the replaced sections whose ground the new ones have covered, or that waited too long. */
    private void retire() {
        List<Node> gone = null;
        for (Node node : nodes.values()) {
            if (node.wanted) continue;
            if (node.built && frame - node.retireSince < MAX_RETIRE_FRAMES && !replacementsBuilt(node)) continue;
            if (gone == null) gone = new ArrayList<>();
            gone.add(node);
        }
        if (gone == null) return;
        for (Node node : gone) {
            node.section.publishClosed();
            nodes.remove(node.key);
            node.queued = false;
        }
        queue.removeIf(n -> !nodes.containsKey(n.key));
    }

    private boolean replacementsBuilt(Node old) {
        for (Node node : nodes.values()) {
            if (node.wanted && !node.built && LodSelection.overlaps(node.key, old.key)) return false;
        }
        return true;
    }

    private void request(Node node) {
        if (node.inFlight) {
            node.again = true;
            return;
        }
        if (node.queued) return;
        node.queued = true;
        queue.add(node);
    }

    private double distance(Node node) {
        long key = node.key;
        return LodSelection.distance(LodSelection.minX(key), LodSelection.minZ(key),
                LodSelection.width(LodSelection.detail(key)), cameraX, cameraZ);
    }

    private void collectFinished() {
        Finished done;
        boolean retireNow = false;
        while ((done = finished.poll()) != null) {
            if (done.generation != generation) continue;
            inFlight--;
            Node node = nodes.get(done.key);
            if (node == null) continue;
            node.inFlight = false;
            if (done.ok) {
                node.built = true;
                retireNow = true;
            }
            frameBuilds++;
            totalBuilds++;
            buildNanos.addAndGet(done.nanos);
            if (totalBuilds % 500 == 0) {
                LOGGER.info("LOD sections built from public data: {}, {} ms each on average",
                        totalBuilds, String.format("%.1f", buildNanos.get() / 1e6 / totalBuilds));
            }
            if (!done.ok) node.retryAt = frame + RETRY_FRAMES;
            if (node.again || !done.ok) {
                node.again = false;
                if (node.wanted) request(node);
            }
        }
        if (retireNow) retire();
    }

    private void collectModified() {
        long[] chunk;
        while ((chunk = terrain.pollModifiedChunk()) != null) {
            modifiedChunks.add(chunk[0] << 32 | chunk[1] & 0xFFFFFFFFL);
        }
        if (modifiedChunks.isEmpty() || frame - modifiedFrame < MODIFIED_FRAMES) return;
        modifiedFrame = frame;
        for (Node node : nodes.values()) {
            if (!node.wanted) continue;
            int x0 = LodSelection.minX(node.key) >> 4;
            int z0 = LodSelection.minZ(node.key) >> 4;
            int x1 = x0 + (LodSelection.width(LodSelection.detail(node.key)) >> 4);
            int z1 = z0 + (LodSelection.width(LodSelection.detail(node.key)) >> 4);
            var it = modifiedChunks.iterator();
            while (it.hasNext()) {
                long packed = it.nextLong();
                int cx = (int) (packed >> 32);
                int cz = (int) packed;
                // A changed chunk just outside still changes the section's edge.
                if (cx >= x0 - 1 && cx <= x1 && cz >= z0 - 1 && cz <= z1) {
                    request(node);
                    frameModified++;
                    break;
                }
            }
        }
        modifiedChunks.clear();
    }

    private void startBuilds() {
        running.removeIf(Future::isDone);
        List<Node> later = null;
        int index = 0;
        while (inFlight < MAX_IN_FLIGHT && index < queue.size()) {
            Node node = queue.get(index++);
            node.queued = false;
            if (!nodes.containsKey(node.key) || node.inFlight) continue;
            if (node.retryAt > frame) {
                if (later == null) later = new ArrayList<>();
                later.add(node);
                continue;
            }
            node.inFlight = true;
            inFlight++;
            long key = node.key;
            LodSection section = node.section;
            int buildGeneration = generation;
            BlockTextureManager.SpriteColors colors = sprites;
            int floor = minY;
            boolean lit = skyLit;
            running.add(pool.submit(() -> {
                long start = System.nanoTime();
                boolean ok = false;
                try {
                    if (buildGeneration == generation) {
                        ok = workers.get().build(key, section, colors, floor, lit, buildGeneration);
                    }
                } catch (Throwable t) {
                    LOGGER.error("LOD section build from public data failed at {}, {}",
                            LodSelection.minX(key), LodSelection.minZ(key), t);
                }
                finished.add(new Finished(key, buildGeneration, System.nanoTime() - start, ok));
            }));
        }
        if (index > 0) queue.subList(0, index).clear();
        if (later != null) {
            for (Node node : later) {
                node.queued = true;
                queue.add(node);
            }
        }
    }

    /** A builder thread's buffers and colours. */
    private final class Worker implements ColumnSink {
        private final LodColumnMesher.Columns columns = new LodColumnMesher.Columns();
        private final LodColumnMesher.Quads opaque = new LodColumnMesher.Quads();
        private final LodColumnMesher.Quads translucent = new LodColumnMesher.Quads();
        private @Nullable LodBlockColors colors;
        private int floor;
        private int originX;
        private int originZ;
        private int cellSize;
        private int cellX;
        private int cellZ;
        private boolean lit;

        boolean build(long key, LodSection section, BlockTextureManager.SpriteColors sprites, int minY,
                      boolean skyLit, int buildGeneration) {
            Reader reader = terrain.reader();
            if (reader == null) return false;
            if (colors == null || colors.sprites() != sprites) colors = new LodBlockColors(sprites);
            int detail = LodSelection.detail(key);
            floor = minY;
            lit = skyLit;
            cellSize = 1 << detail;
            originX = LodSelection.minX(key);
            originZ = LodSelection.minZ(key);
            int cells = LodSelection.CELLS;
            columns.clear();
            if (detail == 0) {
                if (!reader.area64(LodSelection.ix(key), LodSelection.iz(key), this)) return false;
                for (int i = 0; i < cells; i++) {
                    if (generation != buildGeneration) return false;
                    reader.column(originX - 1, originZ + i, this, -1, i);
                    reader.column(originX + cells, originZ + i, this, cells, i);
                    reader.column(originX + i, originZ - 1, this, i, -1);
                    reader.column(originX + i, originZ + cells, this, i, cells);
                }
            } else {
                int half = cellSize / 2;
                for (int cz = -1; cz <= cells; cz++) {
                    if (generation != buildGeneration) return false;
                    for (int cx = -1; cx <= cells; cx++) {
                        boolean corner = (cx < 0 || cx == cells) && (cz < 0 || cz == cells);
                        if (corner) continue;
                        if (!reader.column(originX + cx * cellSize + half, originZ + cz * cellSize + half, this, cx, cz)
                                && cx >= 0 && cx < cells && cz >= 0 && cz < cells) {
                            return false;
                        }
                    }
                }
            }
            opaque.clear();
            translucent.clear();
            LodColumnMesher.mesh(columns, cellSize, opaque, translucent);
            section.publishGeometry(List.of(opaque.view()), List.of(translucent.view()));
            section.publishEnabled(true);
            return true;
        }

        @Override
        public void begin(int cx, int cz) {
            cellX = cx;
            cellZ = cz;
            columns.begin(cx, cz);
        }

        @Override
        public void run(BlockState state, @Nullable Object biome, int bottomY, int topY, int dhMaterial, boolean liquid) {
            LodBlockColors palette = colors;
            if (palette == null) return;
            byte kind;
            if (liquid) {
                kind = state.getFluidState().is(FluidTags.WATER) ? LodColumnMesher.WATER : LodColumnMesher.SOLID;
            } else {
                if (palette.skipped(state)) return;
                kind = palette.translucent(state) ? LodColumnMesher.TRANSLUCENT : LodColumnMesher.SOLID;
            }
            int[] c = palette.colors(state, biome, originX + cellX * cellSize, originZ + cellZ * cellSize);
            columns.add(bottomY - floor, topY - floor, c[0], c[1], c[2], dhMaterial, kind);
        }

        @Override
        public void air(int bottomY, int topY, int skyLight) {
            if (lit && skyLight == 0) columns.add(bottomY - floor, topY - floor, 0, 0, 0, 0, LodColumnMesher.DARK);
        }
    }
}
