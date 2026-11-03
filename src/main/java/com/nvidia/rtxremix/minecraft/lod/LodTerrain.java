/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.lod;

import com.nvidia.rtxremix.InstanceCategoryBit;
import com.nvidia.rtxremix.InstanceInfoBlendEXT;
import com.nvidia.rtxremix.MaterialInfo;
import com.nvidia.rtxremix.MaterialInfoOpaqueEXT;
import com.nvidia.rtxremix.MaterialInfoTranslucentEXT;
import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.TextureFormat;
import com.nvidia.rtxremix.TexturePurpose;
import com.nvidia.rtxremix.minecraft.ClientViewDistance;
import com.nvidia.rtxremix.minecraft.asset.RemixInstance;
import com.nvidia.rtxremix.minecraft.asset.RemixInstanceStore;
import com.nvidia.rtxremix.minecraft.asset.RemixMaterial;
import com.nvidia.rtxremix.minecraft.asset.RemixMaterialStore;
import com.nvidia.rtxremix.minecraft.asset.RemixMesh;
import com.nvidia.rtxremix.minecraft.asset.RemixMeshStore;
import com.nvidia.rtxremix.minecraft.asset.RemixTexture;
import com.nvidia.rtxremix.minecraft.asset.RemixTextureStore;
import com.nvidia.rtxremix.minecraft.lod.dh.DistantHorizonsCompat;
import com.nvidia.rtxremix.minecraft.profiling.PerformanceTimer;
import com.nvidia.rtxremix.minecraft.terrain.ChunkManager;
import com.nvidia.rtxremix.minecraft.terrain.TerrainAssetRetirementQueue;
import com.nvidia.rtxremix.minecraft.terrain.TerrainNativeCallStats;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import com.nvidia.rtxremix.minecraft.util.DirectBufferPool;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Far terrain from an LOD mod, as retained Remix instances beyond Minecraft's
 * own terrain.
 *
 * <p>The LOD mod decides which sections exist, at what detail, and which are
 * drawn; {@link LodSection} carries those decisions over. Here, on the render
 * thread, a section's copied geometry is sent to the builder threads with the
 * current clip, and the finished mesh is installed under a frame budget as one
 * instance, submitted where it is created and again only when the mod shows or
 * hides it. Moving into a new chunk rebuilds just the sections whose clipped
 * chunks change.
 *
 * <p>The mod swaps sections of one detail for those of another only once the new
 * ones are uploaded, but a mesh here is built later, on a builder thread. So a
 * section the mod hides lingers, still drawn, while a section it shows over the
 * same ground awaits its mesh, and a built section is held hidden while one it
 * overlaps lingers: the ground changes detail at once rather than through a hole,
 * or after {@link #MAX_LINGER_FRAMES} at the latest.
 */
public final class LodTerrain {

    private static final Logger LOGGER = LoggerFactory.getLogger(LodTerrain.class);

    private static final boolean DISTANT_HORIZONS =
            FabricLoader.getInstance().isModLoaded("distanthorizons");
    private static final long INSTALL_BUDGET_NS = 1_000_000L;
    /** Common glass, as Minecraft's own glass takes it. */
    private static final float GLASS_IOR = 1.52f;
    /** A glass face's thickness; its transmittance is measured over the same, so a face passes its tint once. */
    private static final float GLASS_WALL = 0.05f;
    /** Share of the terrain's emission at which blocks that give off light, lava aside, glow. */
    private static final float LAMP_EMISSION = Math.max(0f,
            Float.parseFloat(System.getProperty("rtxremix.lod.lampEmission", "0.2")));
    /**
     * Low-priority threads building meshes. One fell some eighty sections behind a camera
     * flying across the city, and the sections it had not reached showed LOD ground over
     * Minecraft's or none at all.
     */
    private static final int BUILD_THREADS = Math.max(1, Integer.getInteger("rtxremix.lod.buildThreads", 3));
    private static final int MAX_BUILDS_IN_FLIGHT = 2 * BUILD_THREADS;
    private static final int MAX_EVENTS_PER_FRAME = 4096;
    /**
     * Minecraft's terrain is clipped this many chunks inside its render
     * distance, so its unbuilt edge shows LOD ground rather than a hole.
     */
    /** Frames between clips while columns keep changing, so sections near loading chunks finish. */
    private static final int CLIP_INTERVAL_FRAMES = 4;
    /**
     * The LOD Memory setting, the mesh data the LOD terrain may hold, in MiB. Remix's buffers
     * and acceleration structures take about twice the mesh data. A budget worked out from
     * the card's memory, a sixteenth of it, held an 8 GB card to 512 MiB and drew Distant
     * Horizons' higher qualities only around the player, where 2 GiB had run on it.
     */
    public static final int DEFAULT_MEMORY_MIB = 2048;
    public static final int MIN_MEMORY_MIB = 256;
    public static final int MAX_MEMORY_MIB = 4096;
    public static final int MEMORY_STEP_MIB = 256;
    /** The slider's last step, past {@link #MAX_MEMORY_MIB}: no budget at all. */
    public static final int UNLIMITED_MEMORY_MIB = MAX_MEMORY_MIB + MEMORY_STEP_MIB;
    private static final InstanceInfoBlendEXT BLEND = InstanceInfoBlendEXT.textureTimesVertexColor();
    /** Frames a hidden section may linger while the sections replacing it are built. */
    private static final int MAX_LINGER_FRAMES = 600;

    static final byte SWAP_NONE = 0;
    /** Shown by the mod, its mesh not yet installed. */
    static final byte SWAP_AWAITED = 1;
    /** Hidden by the mod, still drawn while a section over the same ground is awaited. */
    static final byte SWAP_LINGERING = 2;
    /** Shown by the mod and installed, held hidden while a section it overlaps lingers. */
    static final byte SWAP_HELD = 3;

    private record BuildResult(LodSection section, int serial, int epoch, long signature,
                               RemixMesh mesh, Throwable failure) {}

    private final ExecutorService builder = Executors.newFixedThreadPool(BUILD_THREADS, runnable -> {
        Thread thread = new Thread(runnable, "RTX LOD Mesh Builder");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    private final ConcurrentLinkedQueue<BuildResult> completed = new ConcurrentLinkedQueue<>();
    private final TerrainAssetRetirementQueue retirement =
            new TerrainAssetRetirementQueue(new TerrainNativeCallStats());
    private final List<LodSection> live = new ArrayList<>();
    private final ArrayDeque<LodSection> buildQueue = new ArrayDeque<>();
    private final List<LodSection> awaited = new ArrayList<>();
    private final List<LodSection> lingering = new ArrayList<>();
    private final List<LodSection> held = new ArrayList<>();
    private final PerformanceTimer timer;
    private final int cntAwaited;
    private final int cntLingering;
    private final int cntHeld;

    private boolean enabled = true;
    private int frame;
    private boolean capturing;
    private int epoch;
    private int buildsInFlight;
    private long installedBytes;
    private int memoryMib = DEFAULT_MEMORY_MIB;
    private long maxInstalledBytes = (long) DEFAULT_MEMORY_MIB << 20;
    private boolean budgetWarned;
    /**
     * Blocks from the camera past which no section is drawn, shrunk from infinity when the mesh
     * budget fills so that it is spent on the ground nearest the camera.
     */
    private double budgetRadius = Double.POSITIVE_INFINITY;
    private double cameraX;
    private double cameraZ;
    private final List<LodSection> farthest = new ArrayList<>();
    private final java.util.Comparator<LodSection> fartherFirst =
            java.util.Comparator.comparingDouble(this::distance).reversed();
    private LodMeshBuilder.Clip clip = LodMeshBuilder.Clip.NONE;
    private long clipGeneration = -1;
    private int clipFrame;
    private int clipCenterX = Integer.MIN_VALUE;
    private int clipCenterZ = Integer.MIN_VALUE;
    private RemixMaterial groundMaterial;
    private RemixTexture palette;
    private RemixMaterial glassMaterial;
    private RemixMaterial glowMaterial;
    private RemixMaterial lampMaterial;

    public LodTerrain(PerformanceTimer timer) {
        this.timer = timer;
        cntAwaited = timer.registerCounter("Lod.Awaited");
        cntLingering = timer.registerCounter("Lod.Lingering");
        cntHeld = timer.registerCounter("Lod.Held");
        if (DISTANT_HORIZONS) DistantHorizonsCompat.bindEvents();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** The LOD Memory setting, in MiB. */
    public int getMemoryMib() {
        return memoryMib;
    }

    /**
     * Render thread. A smaller budget gives up the farthest sections at once; a larger one
     * builds again the sections it had given up.
     */
    public void setMemoryMib(int mib) {
        memoryMib = mib;
        long budget = mib >= UNLIMITED_MEMORY_MIB ? Long.MAX_VALUE : (long) mib << 20;
        boolean grew = budget > maxInstalledBytes;
        maxInstalledBytes = budget;
        budgetWarned = false;
        if (!capturing) return;
        if (installedBytes > budget) {
            makeRoom(0.0, 0);
        } else if (grew) {
            budgetRadius = Double.POSITIVE_INFINITY;
            revisitStarved();
        }
    }

    /** Radius of the LOD terrain being drawn, in blocks, or 0 when there is none. */
    public float radiusBlocks() {
        return capturing ? (float) Math.min(DistantHorizonsCompat.lodRadiusChunks() * 16.0, budgetRadius) : 0f;
    }

    /**
     * Render thread, once per frame. {@code active} is whether Remix draws the
     * world this frame; while it is false the LOD mod draws for itself.
     */
    public void update(RemixAPI api, Vec3 camera, BlockTextureManager textures, boolean active, ChunkManager terrain) {
        boolean wanted = DISTANT_HORIZONS && enabled && active && terrain != null;
        if (wanted != capturing) setCapturing(wanted);
        boolean eventsPending = drainEvents(api);
        // Also frees what finishes after a stop: a stop makes every build stale.
        installBuilds(api);
        if (!capturing) return;

        frame++;
        cameraX = camera.x;
        cameraZ = camera.z;
        resolveSwaps(api, eventsPending);
        updateClip(camera, terrain);
        startBuilds(textures);
        retirement.drainFrame();
        timer.count(cntAwaited, awaited.size());
        timer.count(cntLingering, lingering.size());
        timer.count(cntHeld, held.size());
    }

    /**
     * Drops every Remix object before materials are rebuilt, keeping the copied
     * geometry so each section reinstalls with the new materials.
     */
    public void clear() {
        epoch++;
        dropSwaps();
        budgetRadius = Double.POSITIVE_INFINITY;
        clipGeneration = -1;
        for (LodSection section : live) {
            retireInstance(section);
            section.installed = false;
            section.starved = false;
            section.clipSignature = 0;
            requestBuild(section);
            if (section.enabled) setSwap(section, SWAP_AWAITED);
        }
        retirement.clear();
        // Every instance is gone, so the emission strength can change with the reload.
        destroyPaletteMaterials();
    }

    /** Drops everything when the world goes; the next world's sections arrive afresh. */
    public void reset() {
        if (capturing) setCapturing(false);
    }

    public void destroy() {
        reset();
        builder.shutdownNow();
        if (groundMaterial != null && !groundMaterial.isDeleted()) groundMaterial.destroy();
        groundMaterial = null;
        destroyPaletteMaterials();
        if (palette != null && !palette.isDeleted()) palette.destroy();
        palette = null;
    }

    private void destroyPaletteMaterials() {
        if (glassMaterial != null && !glassMaterial.isDeleted()) glassMaterial.destroy();
        if (glowMaterial != null && !glowMaterial.isDeleted()) glowMaterial.destroy();
        if (lampMaterial != null && !lampMaterial.isDeleted()) lampMaterial.destroy();
        glassMaterial = null;
        glowMaterial = null;
        lampMaterial = null;
    }

    private void setCapturing(boolean on) {
        capturing = on;
        LodSection.capturing = on;
        if (!on) {
            epoch++;
            dropSwaps();
            budgetRadius = Double.POSITIVE_INFINITY;
            for (LodSection section : live) {
                retireInstance(section);
                releaseGeometry(section);
                section.installed = false;
                section.liveIndex = -1;
                section.inBuildQueue = false;
            }
            live.clear();
            buildQueue.clear();
            // Nothing drains the queue while stopped, so the scene must lose them now.
            retirement.clear();
        }
        LOGGER.info("LOD terrain capture {}", on ? "started" : "stopped");
        // Starting asks the mod to rebuild every section so the copies arrive;
        // stopping hands it back its own shading and drawing.
        DistantHorizonsCompat.setCapturing(on);
    }

    /** Whether events are left for the next frame. */
    private boolean drainEvents(RemixAPI api) {
        for (int i = 0; i < MAX_EVENTS_PER_FRAME; i++) {
            LodSection section = LodSection.pollDirty();
            if (section == null) return false;

            LodGeometry geometry = section.takePending();
            if (section.closed || !capturing) {
                if (geometry != null) geometry.release();
                if (section.liveIndex >= 0) forget(section);
                continue;
            }
            if (geometry != null) {
                releaseGeometry(section);
                section.geometry = geometry;
                if (section.liveIndex < 0) {
                    section.liveIndex = live.size();
                    live.add(section);
                }
                requestBuild(section);
            }
            if (section.liveIndex >= 0) applyVisibility(section, api);
        }
        return true;
    }

    private void forget(LodSection section) {
        // A closed section keeps its instance while it lingers, and gives it up when the swap ends.
        if (section.shown && section.instance != null) {
            setSwap(section, SWAP_LINGERING);
        } else {
            setSwap(section, SWAP_NONE);
            retireInstance(section);
        }
        releaseGeometry(section);
        int index = section.liveIndex;
        LodSection last = live.remove(live.size() - 1);
        if (last != section) {
            live.set(index, last);
            last.liveIndex = index;
        }
        section.liveIndex = -1;
        // A queued build for it is skipped when it reaches the front.
        section.serial++;
    }

    private void applyVisibility(LodSection section, RemixAPI api) {
        if (section.enabled) {
            if (section.swap == SWAP_LINGERING) {
                // Wanted again while still drawn.
                setSwap(section, SWAP_NONE);
            } else if (!section.installed) {
                setSwap(section, SWAP_AWAITED);
            } else if (overlapsAny(section, lingering)) {
                setSwap(section, SWAP_HELD);
                setShown(section, false, api);
            } else {
                setSwap(section, SWAP_NONE);
                setShown(section, true, api);
            }
        } else if (section.shown && section.instance != null) {
            // Every hidden section lingers until the frame's events are in, since the mod may
            // hide it before it shows the sections replacing it.
            setSwap(section, SWAP_LINGERING);
        } else {
            setSwap(section, SWAP_NONE);
            setShown(section, false, api);
        }
    }

    /**
     * Ends the swaps whose awaited sections are all installed, or that have lingered too long.
     * {@code eventsPending} is whether the frame left events for the next, which may show what
     * replaces a lingering section.
     */
    private void resolveSwaps(RemixAPI api, boolean eventsPending) {
        // Backwards, since ending one moves the last of its list into its place.
        for (int i = lingering.size() - 1; i >= 0; i--) {
            LodSection section = lingering.get(i);
            // A lingering mesh clipped for where the camera was lies over Minecraft's terrain.
            if (frame - section.lingerSince < MAX_LINGER_FRAMES && !overlapsTerrain(section)
                    && (eventsPending || overlapsAny(section, awaited))) continue;
            setSwap(section, SWAP_NONE);
            if (section.liveIndex < 0) {
                retireInstance(section);
            } else {
                setShown(section, false, api);
            }
        }
        for (int i = held.size() - 1; i >= 0; i--) {
            LodSection section = held.get(i);
            if (overlapsAny(section, lingering)) continue;
            setSwap(section, SWAP_NONE);
            setShown(section, section.enabled, api);
        }
    }

    /** Ends every swap, giving up the instances of closed sections that lingered. */
    private void dropSwaps() {
        for (int i = lingering.size() - 1; i >= 0; i--) {
            LodSection section = lingering.get(i);
            setSwap(section, SWAP_NONE);
            if (section.liveIndex < 0) retireInstance(section);
        }
        while (!awaited.isEmpty()) setSwap(awaited.get(awaited.size() - 1), SWAP_NONE);
        while (!held.isEmpty()) setSwap(held.get(held.size() - 1), SWAP_NONE);
    }

    private void setSwap(LodSection section, byte state) {
        if (section.swap == state) return;
        List<LodSection> from = swapList(section.swap);
        if (from != null) {
            LodSection last = from.remove(from.size() - 1);
            if (last != section) {
                from.set(section.swapIndex, last);
                last.swapIndex = section.swapIndex;
            }
            section.swapIndex = -1;
        }
        List<LodSection> to = swapList(state);
        if (to != null) {
            section.swapIndex = to.size();
            to.add(section);
        }
        section.swap = state;
        if (state == SWAP_LINGERING) section.lingerSince = frame;
    }

    private List<LodSection> swapList(byte state) {
        return switch (state) {
            case SWAP_AWAITED -> awaited;
            case SWAP_LINGERING -> lingering;
            case SWAP_HELD -> held;
            default -> null;
        };
    }

    private static boolean overlapsAny(LodSection section, List<LodSection> others) {
        for (int i = 0; i < others.size(); i++) {
            LodSection other = others.get(i);
            if (section.minX < other.minX + other.width && other.minX < section.minX + section.width
                    && section.minZ < other.minZ + other.width && other.minZ < section.minZ + section.width) {
                return true;
            }
        }
        return false;
    }

    private static void setShown(LodSection section, boolean shown, RemixAPI api) {
        if (section.instance == null || section.shown == shown) return;
        section.instance.setCategoryFlags(shown ? 0 : InstanceCategoryBit.HIDDEN);
        section.instance.drawFast(api);
        section.shown = shown;
    }

    /**
     * Follows the columns Minecraft's terrain draws: on any change, every few frames while
     * chunks load, sections whose share of them changed are rebuilt first, and those wholly
     * drawn give way at once.
     */
    private void updateClip(Vec3 camera, ChunkManager terrain) {
        int centerX = ((int) Math.floor(camera.x)) >> 4;
        int centerZ = ((int) Math.floor(camera.z)) >> 4;
        long generation = terrain.drawnGeneration();
        boolean moved = centerX != clipCenterX || centerZ != clipCenterZ;
        if (!moved && (generation == clipGeneration || frame - clipFrame < CLIP_INTERVAL_FRAMES)) return;
        clipGeneration = generation;
        clipFrame = frame;
        clipCenterX = centerX;
        clipCenterZ = centerZ;
        // Minecraft draws no chunk past its render distance, give or take one.
        clip = LodMeshBuilder.Clip.of(centerX, centerZ, ClientViewDistance.chunks() + 2, terrain::isColumnDrawn);
        if (moved) revisitStarved();
        for (LodSection section : live) {
            long signature = clip.signature(section.minX, section.minZ, section.width);
            if (signature == section.clipSignature) continue;
            if (clip.covers(section.minX, section.minZ, section.width)) {
                giveWayToTerrain(section, signature);
            } else {
                // It meets Minecraft's terrain, over it or short of it, until rebuilt.
                requestBuild(section, true);
            }
        }
    }

    /**
     * Drops a section wholly where Minecraft's own terrain is drawn, whose mesh would be empty:
     * the old one lay over that terrain as a dark or bright square until a rebuild came round
     * the queue.
     */
    private void giveWayToTerrain(LodSection section, long signature) {
        section.serial++;
        section.clipSignature = signature;
        section.installed = true;
        setSwap(section, SWAP_NONE);
        retireInstance(section);
    }

    private void requestBuild(LodSection section) {
        requestBuild(section, false);
    }

    private void requestBuild(LodSection section, boolean first) {
        section.serial++;
        if (section.inBuildQueue) {
            if (!first || !buildQueue.remove(section)) return;
        }
        section.inBuildQueue = true;
        if (first) {
            buildQueue.addFirst(section);
        } else {
            buildQueue.add(section);
        }
    }

    /**
     * Once the camera has moved a chunk, lets the budget's radius grow back while a quarter of
     * the budget is free, and builds the sections it has starved that now lie within it.
     */
    private void revisitStarved() {
        if (budgetRadius < Double.POSITIVE_INFINITY && installedBytes < maxInstalledBytes / 4 * 3) {
            budgetRadius *= 1.1;
        }
        for (LodSection section : live) {
            if (section.starved && distance(section) < budgetRadius) {
                section.starved = false;
                requestBuild(section);
            }
        }
    }

    /** Blocks across the ground from the camera to the nearest edge of a section. */
    private double distance(LodSection section) {
        double dx = Math.max(0.0, Math.max(section.minX - cameraX, cameraX - (section.minX + section.width)));
        double dz = Math.max(0.0, Math.max(section.minZ - cameraZ, cameraZ - (section.minZ + section.width)));
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Gives up installed sections farther than {@code distance}, farthest first, until
     * {@code bytes} more fit with a tenth of the budget to spare, and draws none from the
     * nearest given up outward; returns whether they fit. The mod's own range far outruns the
     * budget, and sections installed in arrival order filled it with distant ground, so the
     * ground near the camera, rebuilt as it moves, found no room and left a ring of holes.
     */
    private boolean makeRoom(double distance, long bytes) {
        farthest.clear();
        for (LodSection section : live) {
            if (section.instance != null && distance(section) > distance) farthest.add(section);
        }
        farthest.sort(fartherFirst);
        long target = maxInstalledBytes - maxInstalledBytes / 10;
        for (int i = 0; i < farthest.size() && installedBytes + bytes > target; i++) {
            LodSection section = farthest.get(i);
            budgetRadius = Math.min(budgetRadius, distance(section));
            setSwap(section, SWAP_NONE);
            retireInstance(section);
            section.starved = true;
        }
        farthest.clear();
        if (!budgetWarned) {
            budgetWarned = true;
            LOGGER.info("LOD terrain filled its {} MiB budget; drawing it to {} blocks",
                    maxInstalledBytes >> 20, Math.round(budgetRadius));
        }
        return installedBytes + bytes <= maxInstalledBytes;
    }

    /** Whether a section's mesh still holds chunks the clip now gives to Minecraft's terrain. */
    private boolean overlapsTerrain(LodSection section) {
        return clip.intersects(section.minX, section.minZ, section.width)
                && clip.signature(section.minX, section.minZ, section.width) != section.clipSignature;
    }

    private void startBuilds(BlockTextureManager textures) {
        if (buildQueue.isEmpty() || buildsInFlight >= MAX_BUILDS_IN_FLIGHT) return;
        RemixMaterial ground = groundMaterial();
        RemixMaterial water = textures != null ? textures.getWaterSurfaceMaterial() : null;
        if (ground == null) return;
        RemixMaterial glass = glassMaterial();
        RemixMaterial glow = textures != null ? glowMaterial(textures.terrainEmissionIntensity()) : null;
        RemixMaterial lamp = textures != null ? lampMaterial(textures.terrainEmissionIntensity()) : null;

        while (buildsInFlight < MAX_BUILDS_IN_FLIGHT && !buildQueue.isEmpty()) {
            LodSection section = buildQueue.poll();
            section.inBuildQueue = false;
            if (section.liveIndex < 0 || section.geometry == null) continue;
            if (distance(section) >= budgetRadius) {
                starve(section);
                continue;
            }

            LodGeometry geometry = section.geometry;
            geometry.retain();
            LodMeshBuilder.Clip buildClip = clip;
            int serial = section.serial;
            int buildEpoch = epoch;
            long signature = buildClip.signature(section.minX, section.minZ, section.width);
            buildsInFlight++;
            builder.execute(() -> {
                RemixMesh mesh = null;
                Throwable failure = null;
                try {
                    mesh = LodMeshBuilder.build(geometry, buildClip, section.minX, section.minZ,
                            ground, water, glass, glow, lamp);
                } catch (Throwable t) {
                    failure = t;
                } finally {
                    geometry.release();
                }
                completed.add(new BuildResult(section, serial, buildEpoch, signature, mesh, failure));
            });
        }
    }

    private void installBuilds(RemixAPI api) {
        long deadline = System.nanoTime() + INSTALL_BUDGET_NS;
        BuildResult result;
        while ((result = completed.poll()) != null) {
            buildsInFlight--;
            LodSection section = result.section();
            if (result.failure() != null) {
                LOGGER.error("LOD mesh build failed at {}, {}", section.minX, section.minZ, result.failure());
                continue;
            }
            // A build overtaken by a newer request still stands nearer the truth than what it
            // replaces, and the newer one follows from the queue; dropped, sections next to
            // chunks that kept loading never finished.
            if (result.epoch() != epoch || section.liveIndex < 0) {
                if (result.mesh() != null) result.mesh().disposeData();
                continue;
            }
            // Built against a clip that has moved on. The clip may since have given the whole
            // section to Minecraft's terrain, which queues no rebuild, and the stale mesh stayed
            // over that terrain for good.
            long current = clip.signature(section.minX, section.minZ, section.width);
            if (result.signature() != current && clip.covers(section.minX, section.minZ, section.width)) {
                if (result.mesh() != null) result.mesh().disposeData();
                giveWayToTerrain(section, current);
                continue;
            }
            install(section, result.mesh(), result.signature(), api);
            if (result.signature() != current) requestBuild(section, true);
            if (System.nanoTime() >= deadline) return;
        }
    }

    private void install(LodSection section, RemixMesh mesh, long signature, RemixAPI api) {
        retireInstance(section);
        section.clipSignature = signature;
        // Even an empty or over-budget mesh ends the wait: nothing more will come to draw.
        section.installed = true;
        if (section.swap == SWAP_AWAITED) setSwap(section, SWAP_NONE);
        if (section.enabled && section.swap == SWAP_NONE && overlapsAny(section, lingering)) {
            setSwap(section, SWAP_HELD);
        }
        if (mesh == null) return;

        long bytes = mesh.estimatedNativeBytes();
        double distance = distance(section);
        if (distance >= budgetRadius
                || (installedBytes + bytes > maxInstalledBytes && !makeRoom(distance, bytes))) {
            mesh.disposeData();
            starve(section);
            return;
        }
        RemixInstance instance = null;
        try {
            RemixMeshStore.get().create(mesh);
            boolean visible = section.swap == SWAP_LINGERING || (section.enabled && section.swap != SWAP_HELD);
            instance = new RemixInstance(mesh)
                    .setWorldTranslation(section.minX, section.minY, section.minZ)
                    .setCategoryFlags(visible ? 0 : InstanceCategoryBit.HIDDEN)
                    .setBlendExt(BLEND)
                    .setDoubleSided(true);
            RemixInstanceStore.get().create(instance);
            instance.drawFast(api);
            section.instance = instance;
            section.shown = visible;
            section.installedBytes = bytes;
            installedBytes += bytes;
        } catch (RuntimeException e) {
            LOGGER.error("LOD terrain install failed at {}, {}", section.minX, section.minZ, e);
            if (instance != null) {
                retirement.retire(instance);
            } else if (!mesh.isDeleted()) {
                retirement.retire(mesh);
            }
            mesh.disposeData();
        }
    }

    /** Leaves a section unbuilt past the budget's radius, with nothing for its swap to wait on. */
    private void starve(LodSection section) {
        section.starved = true;
        section.installed = true;
        if (section.swap == SWAP_AWAITED) setSwap(section, SWAP_NONE);
    }

    private void retireInstance(LodSection section) {
        if (section.instance == null) return;
        retirement.retire(section.instance);
        installedBytes -= section.installedBytes;
        section.instance = null;
        section.installedBytes = 0;
        section.shown = false;
    }

    private static void releaseGeometry(LodSection section) {
        if (section.geometry != null) {
            section.geometry.release();
            section.geometry = null;
        }
    }

    /** One white texel, so the vertex colour the LOD mod baked is the whole albedo. */
    private RemixMaterial groundMaterial() {
        if (groundMaterial != null && !groundMaterial.isDeleted()) return groundMaterial;
        ByteBuffer pixel = DirectBufferPool.acquire(4);
        pixel.put(0, (byte) -1).put(1, (byte) -1).put(2, (byte) -1).put(3, (byte) -1).limit(4);
        try {
            RemixTexture texture = RemixTextureStore.get().create(new RemixTexture(
                    1, 1, TextureFormat.RGBA8_UNORM, TexturePurpose.COLOR, pixel));
            MaterialInfo info = MaterialInfo.builder()
                    .albedoTexture(texture.getHandle())
                    .filterMode(0)
                    .opaqueExt(MaterialInfoOpaqueEXT.builder()
                            .albedoConstant(1f, 1f, 1f)
                            .roughnessConstant(0.9f)
                            .metallicConstant(0f)
                            .build())
                    .build();
            groundMaterial = RemixMaterialStore.get().create(new RemixMaterial(info, List.of(texture), null));
        } catch (RuntimeException e) {
            LOGGER.error("Failed to create the LOD terrain material", e);
            groundMaterial = null;
        }
        return groundMaterial;
    }

    private RemixTexture palette() {
        if (palette != null && !palette.isDeleted()) return palette;
        ByteBuffer pixels = LodPalette.fill(DirectBufferPool.acquire(LodPalette.WIDTH * LodPalette.HEIGHT * 4));
        try {
            palette = RemixTextureStore.get().create(new RemixTexture(LodPalette.WIDTH, LodPalette.HEIGHT,
                    TextureFormat.RGBA8_UNORM, TexturePurpose.COLOR, pixels));
        } catch (RuntimeException e) {
            LOGGER.error("Failed to create the LOD colour palette", e);
            palette = null;
        }
        return palette;
    }

    /**
     * Glass, ice and the LOD mod's other translucent blocks: each face lets its
     * palette tint through, as a thin wall, so a pane neither refracts by a whole
     * block nor darkens with the block's depth.
     */
    private RemixMaterial glassMaterial() {
        if (glassMaterial != null && !glassMaterial.isDeleted()) return glassMaterial;
        RemixTexture texture = palette();
        if (texture == null) return null;
        try {
            MaterialInfo info = MaterialInfo.builder()
                    .albedoTexture(texture.getHandle())
                    .filterMode(0)
                    .translucentExt(MaterialInfoTranslucentEXT.builder()
                            .refractiveIndex(GLASS_IOR)
                            .transmittanceTexture(texture.getHandle())
                            .transmittanceMeasurementDistance(GLASS_WALL)
                            .thinWall(GLASS_WALL)
                            .build())
                    .build();
            glassMaterial = RemixMaterialStore.get().create(new RemixMaterial(info, List.of(texture), null));
        } catch (RuntimeException e) {
            LOGGER.error("Failed to create the LOD glass material", e);
            glassMaterial = null;
        }
        return glassMaterial;
    }

    /** Lava glows in its own colour at the terrain's emission. */
    private RemixMaterial glowMaterial(float intensity) {
        if (glowMaterial != null && !glowMaterial.isDeleted()) return glowMaterial;
        glowMaterial = paletteGlow(intensity, "glow");
        return glowMaterial;
    }

    /**
     * Other blocks that give off light glow at {@link #LAMP_EMISSION} of the terrain's emission.
     * The LOD mod draws an area of many blocks as one of them, so a rooftop torch became a box
     * several blocks across, which at the full emission lit the roofs around and bloomed to
     * white (#23); the block's own texture glows only where its flame is.
     */
    private RemixMaterial lampMaterial(float intensity) {
        if (lampMaterial != null && !lampMaterial.isDeleted()) return lampMaterial;
        lampMaterial = paletteGlow(intensity * LAMP_EMISSION, "lamp");
        return lampMaterial;
    }

    private RemixMaterial paletteGlow(float intensity, String name) {
        RemixTexture texture = palette();
        if (texture == null) return null;
        try {
            MaterialInfo info = MaterialInfo.builder()
                    .albedoTexture(texture.getHandle())
                    .emissiveTexture(texture.getHandle())
                    .emissiveIntensity(intensity)
                    .filterMode(0)
                    .opaqueExt(MaterialInfoOpaqueEXT.builder()
                            .albedoConstant(1f, 1f, 1f)
                            .roughnessConstant(0.9f)
                            .metallicConstant(0f)
                            .build())
                    .build();
            return RemixMaterialStore.get().create(new RemixMaterial(info, List.of(texture), null));
        } catch (RuntimeException e) {
            LOGGER.error("Failed to create the LOD {} material", name, e);
            return null;
        }
    }
}
