/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.sky;

import com.nvidia.rtxremix.HardcodedVertex;
import com.nvidia.rtxremix.InstanceCategoryBit;
import com.nvidia.rtxremix.MaterialInfo;
import com.nvidia.rtxremix.MaterialInfoOpaqueEXT;
import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.TextureFormat;
import com.nvidia.rtxremix.TexturePurpose;
import com.nvidia.rtxremix.Transform;
import com.nvidia.rtxremix.minecraft.asset.RemixAssetBase;
import com.nvidia.rtxremix.minecraft.asset.RemixMaterial;
import com.nvidia.rtxremix.minecraft.asset.RemixMaterialStore;
import com.nvidia.rtxremix.minecraft.asset.RemixMesh;
import com.nvidia.rtxremix.minecraft.asset.RemixMeshStore;
import com.nvidia.rtxremix.minecraft.asset.RemixMeshSurface;
import com.nvidia.rtxremix.minecraft.asset.RemixTexture;
import com.nvidia.rtxremix.minecraft.asset.RemixTextureStore;
import com.nvidia.rtxremix.minecraft.asset.SceneSpace;
import com.nvidia.rtxremix.minecraft.util.DirectBufferPool;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The high sky layers drawn over the dome like the stars: a cirrus layer and aurora
 * curtains. Both are emissive meshes in the sky category, added onto the sky around the
 * camera, so terrain and clouds always stand in front of them. Their textures are
 * generated on a worker and their brightness follows the sky: cirrus is lit like the
 * sky it sits in, the aurora shows at night.
 *
 * <p>Render thread only, apart from the texture generation it hands to a worker.
 */
final class SkyLayers {

    private static final Logger LOGGER = LoggerFactory.getLogger(SkyLayers.class);

    static final int CIRRUS_SIZE = 2048;
    private static final int CIRRUS_RINGS = 32;
    private static final int CIRRUS_SEGMENTS = 96;
    /** Inside the stars' 110-block radius, so the layers are drawn over them. */
    private static final float CIRRUS_RADIUS = 104.0f;
    /** Cirrus added over the sky, relative to the sky's mean brightness. */
    private static final float CIRRUS_SKY_GAIN = 1.5f;
    /** Share of the cirrus brightness taken away at night, where only the moon lights it. */
    private static final float CIRRUS_NIGHT_DIMMING = 0.65f;
    /** Slow turn of the cirrus layer around the zenith, which reads as drift in the high wind. */
    private static final float CIRRUS_DEGREES_PER_SECOND = 0.12f;
    private static final long CIRRUS_SEED = 0x43495252L;

    private static final int AURORA_CURTAINS = 3;
    private static final int AURORA_WIDTH = 2048;
    private static final int AURORA_BAND_HEIGHT = 256;
    private static final int AURORA_SEGMENTS = 96;
    private static final float AURORA_SPAN_DEGREES = 120.0f;
    private static final float AURORA_HEIGHT_DEGREES = 28.0f;
    private static final float AURORA_RADIANCE = 0.4f;
    private static final long AURORA_SEED = 0x4155524FL;
    /** Seconds for the aurora to come in or fade out as the camera enters or leaves a cold biome. */
    private static final float AURORA_BIOME_FADE_SECONDS = 5.0f;
    /** Biome base temperature at or below which snow falls; the aurora's home by default. */
    private static final float COLD_BIOME_TEMPERATURE = 0.15f;

    private static final float INTENSITY_UPDATE_RATIO = 0.02f;

    private RemixTexture cirrusTexture;
    private RemixMaterial cirrusMaterial;
    private RemixMesh cirrusMesh;
    private CompletableFuture<byte[]> pendingCirrus;
    private float pendingCoverage = -1.0f;
    private float builtCoverage = -1.0f;
    private float lastCirrusIntensity = -1.0f;
    /** The last cirrus texture built, shared with the Unbound clouds; null when there is none. */
    private byte[] cirrusPixels;
    private int cirrusVersion;

    private RemixTexture auroraTexture;
    private final RemixMaterial[] auroraMaterials = new RemixMaterial[AURORA_CURTAINS];
    private final RemixMesh[] auroraMeshes = new RemixMesh[AURORA_CURTAINS];
    private CompletableFuture<byte[]> pendingAurora;
    private final float[] lastAuroraIntensity = new float[AURORA_CURTAINS];
    /** How strongly the aurora showed last frame, 0 to its intensity setting. Render thread only. */
    static float auroraVisibility;

    private final Transform transform = new Transform();
    private final BlockPos.MutableBlockPos biomePos = new BlockPos.MutableBlockPos();
    private float coldBiome;
    private long lastNanos;
    private double clock;
    private boolean failed;
    private int meshGeneration;

    /** Counts the meshes this has created, so a mesh that must be drawn over them can follow. */
    int meshGeneration() {
        return meshGeneration;
    }

    /**
     * Draws the layers this frame.
     *
     * @param night how dark the sky is, 0 by day to 1 at night, as the stars' visibility gives it
     * @param weatherVisibility how much of the high sky the weather leaves visible, 0 to 1
     * @param skyLuminance the mean luminance of the upper sky
     * @param drawCirrus false while the Unbound clouds draw the cirrus behind themselves
     */
    void render(RemixAPI api, ClientLevel world, Vec3 cameraPos, long blendPNext,
                float night, float weatherVisibility, float skyLuminance, boolean drawCirrus) {
        if (failed) return;
        long now = System.nanoTime();
        float seconds = lastNanos == 0 ? 0.0f : Math.min(0.25f, (now - lastNanos) / 1.0e9f);
        lastNanos = now;
        clock += seconds;
        try {
            renderCirrus(api, cameraPos, blendPNext,
                    weatherVisibility * (1.0f - CIRRUS_NIGHT_DIMMING * night), skyLuminance, drawCirrus);
            renderAurora(api, world, cameraPos, blendPNext, night * weatherVisibility, seconds);
        } catch (RuntimeException e) {
            LOGGER.error("Failed to draw the high sky layers; turning them off", e);
            destroy();
            failed = true;
        }
    }

    private void renderCirrus(RemixAPI api, Vec3 cameraPos, long blendPNext,
                              float visibility, float skyLuminance, boolean draw) {
        float coverage = SkyLook.getCirrusCoverage();
        if (coverage != builtCoverage && coverage != pendingCoverage) {
            pendingCoverage = coverage;
            pendingCirrus = CompletableFuture.supplyAsync(
                    () -> SkyLayerTextures.cirrus(CIRRUS_SIZE, coverage, CIRRUS_SEED));
        }
        if (pendingCirrus != null && pendingCirrus.isDone()) {
            byte[] pixels = pendingCirrus.join();
            float coverageBuilt = pendingCoverage;
            pendingCirrus = null;
            pendingCoverage = -1.0f;
            destroyCirrus();
            builtCoverage = coverageBuilt;
            cirrusPixels = coverageBuilt > 0.0f ? pixels : null;
            cirrusVersion++;
            if (coverageBuilt > 0.0f) {
                cirrusTexture = createTexture(pixels, CIRRUS_SIZE, CIRRUS_SIZE);
                cirrusMaterial = createMaterial(cirrusTexture, 0.0f);
                cirrusMesh = createCirrusMesh(cirrusMaterial);
                meshGeneration++;
                lastCirrusIntensity = 0.0f;
            }
        }
        if (cirrusMesh == null || !draw) return;

        float intensity = SkyLook.getCirrusBrightness() * CIRRUS_SKY_GAIN * skyLuminance * visibility;
        updateIntensity(cirrusMaterial, cirrusTexture, intensity, lastCirrusIntensity);
        if (changed(intensity, lastCirrusIntensity)) lastCirrusIntensity = intensity;
        if (intensity <= 0.0f) return;
        setTransform(cirrusAngle(), cameraPos);
        api.drawInstance(cirrusMesh.getHandle(), transform.matrix, InstanceCategoryBit.SKY, 1, blendPNext);
    }

    private void renderAurora(RemixAPI api, ClientLevel world, Vec3 cameraPos, long blendPNext,
                              float darkSky, float seconds) {
        float strength = SkyLook.isAuroraEnabled() ? SkyLook.getAuroraIntensity() : 0.0f;
        boolean cold = SkyLook.isAuroraEverywhere() || isColdBiome(world, cameraPos);
        float step = seconds / AURORA_BIOME_FADE_SECONDS;
        coldBiome = Math.clamp(coldBiome + (cold ? step : -step), 0.0f, 1.0f);
        float visibility = strength * darkSky * coldBiome;
        auroraVisibility = auroraTexture == null ? 0.0f : visibility;
        if (visibility <= 0.0f && auroraTexture == null) return;

        if (auroraTexture == null) {
            if (pendingAurora == null) {
                pendingAurora = CompletableFuture.supplyAsync(() -> SkyLayerTextures.aurora(
                        AURORA_WIDTH, AURORA_BAND_HEIGHT, AURORA_CURTAINS, AURORA_SEED));
            }
            if (!pendingAurora.isDone()) return;
            byte[] pixels = pendingAurora.join();
            pendingAurora = null;
            auroraTexture = createTexture(pixels, AURORA_WIDTH, AURORA_BAND_HEIGHT * AURORA_CURTAINS);
            for (int i = 0; i < AURORA_CURTAINS; i++) {
                auroraMaterials[i] = createMaterial(auroraTexture, 0.0f);
                auroraMeshes[i] = createAuroraMesh(auroraMaterials[i], i);
                lastAuroraIntensity[i] = 0.0f;
            }
            meshGeneration++;
        }

        for (int i = 0; i < AURORA_CURTAINS; i++) {
            // Each curtain brightens and dims on its own slow beat, and sways a little.
            float flicker = (0.7f + 0.3f * (float) Math.sin(clock * 0.6 + i * 2.1))
                    * (0.9f + 0.1f * (float) Math.sin(clock * 2.3 + i * 1.7));
            float intensity = AURORA_RADIANCE * visibility * flicker;
            updateIntensity(auroraMaterials[i], auroraTexture, intensity, lastAuroraIntensity[i]);
            if (changed(intensity, lastAuroraIntensity[i])) lastAuroraIntensity[i] = intensity;
            if (intensity <= 0.0f) continue;
            float sway = (float) Math.toRadians(4.0 * Math.sin(clock * 0.05 + i * 1.3));
            setTransform(sway, cameraPos);
            api.drawInstance(auroraMeshes[i].getHandle(), transform.matrix, InstanceCategoryBit.SKY, 1, blendPNext);
        }
    }

    /** The last cirrus texture built, RGBA {@link #CIRRUS_SIZE} square, or null when there is none. */
    byte[] cirrusPixels() {
        return cirrusPixels;
    }

    /** Changes whenever {@link #cirrusPixels} does. */
    int cirrusVersion() {
        return cirrusVersion;
    }

    /** Radians the cirrus layer has turned about the vertical. */
    float cirrusAngle() {
        return (float) Math.toRadians(clock * CIRRUS_DEGREES_PER_SECOND);
    }

    private boolean isColdBiome(ClientLevel world, Vec3 cameraPos) {
        biomePos.set(cameraPos.x, cameraPos.y, cameraPos.z);
        return world.getBiome(biomePos).value().getBaseTemperature() <= COLD_BIOME_TEMPERATURE;
    }

    private static boolean changed(float intensity, float last) {
        return Math.abs(intensity - last) > INTENSITY_UPDATE_RATIO * Math.max(last, 1.0e-3f);
    }

    private static void updateIntensity(RemixMaterial material, RemixTexture texture, float intensity, float last) {
        if (changed(intensity, last)) {
            RemixMaterialStore.get().update(material, materialInfo(texture, intensity));
        }
    }

    /** A camera-centred transform turned {@code angle} radians around the vertical. */
    private void setTransform(float angle, Vec3 cameraPos) {
        float cos = (float) Math.cos(angle);
        float sin = (float) Math.sin(angle);
        float[] m = transform.matrix;
        m[0] = cos;  m[1] = 0.0f; m[2] = sin;   m[3] = SceneSpace.x(cameraPos.x);
        m[4] = 0.0f; m[5] = 1.0f; m[6] = 0.0f;  m[7] = SceneSpace.y(cameraPos.y);
        m[8] = -sin; m[9] = 0.0f; m[10] = cos;  m[11] = SceneSpace.z(cameraPos.z);
    }

    private static RemixTexture createTexture(byte[] pixels, int width, int height) {
        ByteBuffer owned = DirectBufferPool.acquire(pixels.length);
        owned.put(pixels).flip();
        RemixTexture texture = new RemixTexture(width, height, TextureFormat.RGBA8_UNORM, TexturePurpose.COLOR, owned);
        return RemixTextureStore.get().create(texture);
    }

    private static RemixMaterial createMaterial(RemixTexture texture, float intensity) {
        RemixMaterial material = new RemixMaterial(materialInfo(texture, intensity), List.of(texture), null);
        return RemixMaterialStore.get().create(material);
    }

    /** An emissive layer added onto the sky through the celestial blend. */
    private static MaterialInfo materialInfo(RemixTexture texture, float intensity) {
        MaterialInfoOpaqueEXT opaque = MaterialInfoOpaqueEXT.builder()
                .albedoConstant(1.0f, 1.0f, 1.0f)
                .opacityConstant(1.0f)
                .roughnessConstant(1.0f)
                .metallicConstant(0.0f)
                .useDrawCallAlphaState(true)
                .build();
        return MaterialInfo.builder()
                .albedoTexture(texture.getHandle())
                .emissiveTexture(texture.getHandle())
                .emissiveIntensity(intensity)
                .filterMode(1)
                .wrapMode(MaterialInfo.WRAP_MODE_CLAMP, MaterialInfo.WRAP_MODE_CLAMP)
                .opaqueExt(opaque)
                .build();
    }

    /** The upper sky as rings around the zenith, mapped onto the cirrus texture's polar layout. */
    private static RemixMesh createCirrusMesh(RemixMaterial material) {
        int ringVertices = CIRRUS_SEGMENTS + 1;
        HardcodedVertex[] vertices = new HardcodedVertex[1 + CIRRUS_RINGS * ringVertices];
        vertices[0] = HardcodedVertex.create(0, CIRRUS_RADIUS, 0, 0, -1, 0, 0.5f, 0.5f, 0xFFFFFFFF);
        float span = 90.0f - SkyLayerTextures.CIRRUS_MIN_ELEVATION_DEGREES;
        for (int ring = 1; ring <= CIRRUS_RINGS; ring++) {
            float r = ring / (float) CIRRUS_RINGS;
            double elevation = Math.toRadians(90.0f - r * span);
            float horizontal = (float) Math.cos(elevation);
            float y = (float) Math.sin(elevation);
            for (int s = 0; s <= CIRRUS_SEGMENTS; s++) {
                double azimuth = 2.0 * Math.PI * s / CIRRUS_SEGMENTS;
                float dx = (float) Math.sin(azimuth);
                float dz = (float) -Math.cos(azimuth);
                vertices[1 + (ring - 1) * ringVertices + s] = HardcodedVertex.create(
                        dx * horizontal * CIRRUS_RADIUS, y * CIRRUS_RADIUS, dz * horizontal * CIRRUS_RADIUS,
                        -dx * horizontal, -y, -dz * horizontal,
                        0.5f + 0.5f * r * dx, 0.5f + 0.5f * r * dz, 0xFFFFFFFF);
            }
        }
        int[] indices = new int[CIRRUS_SEGMENTS * 3 + (CIRRUS_RINGS - 1) * CIRRUS_SEGMENTS * 6];
        int n = 0;
        for (int s = 0; s < CIRRUS_SEGMENTS; s++) {
            indices[n++] = 0;
            indices[n++] = 1 + s;
            indices[n++] = 1 + s + 1;
        }
        for (int ring = 1; ring < CIRRUS_RINGS; ring++) {
            int inner = 1 + (ring - 1) * ringVertices;
            int outer = inner + ringVertices;
            for (int s = 0; s < CIRRUS_SEGMENTS; s++) {
                indices[n++] = inner + s;
                indices[n++] = outer + s;
                indices[n++] = outer + s + 1;
                indices[n++] = inner + s;
                indices[n++] = outer + s + 1;
                indices[n++] = inner + s + 1;
            }
        }
        return createMesh(material, vertices, indices);
    }

    /**
     * One aurora curtain across the northern sky: a band from a wandering lower edge up
     * {@link #AURORA_HEIGHT_DEGREES}, mapped onto curtain {@code index}'s band of the texture.
     */
    private static RemixMesh createAuroraMesh(RemixMaterial material, int index) {
        float radius = CIRRUS_RADIUS - 2.0f - index;
        double centre = Math.toRadians((index - 1) * 25.0);
        double phase = index * 2.3;
        float bandTop = index / (float) AURORA_CURTAINS + 0.5f / (AURORA_BAND_HEIGHT * AURORA_CURTAINS);
        float bandBottom = (index + 1) / (float) AURORA_CURTAINS - 0.5f / (AURORA_BAND_HEIGHT * AURORA_CURTAINS);
        HardcodedVertex[] vertices = new HardcodedVertex[(AURORA_SEGMENTS + 1) * 2];
        for (int s = 0; s <= AURORA_SEGMENTS; s++) {
            float u = s / (float) AURORA_SEGMENTS;
            double azimuth = centre + Math.toRadians((u - 0.5f) * AURORA_SPAN_DEGREES);
            double base = Math.toRadians(10.0 + 3.0 * index + 6.0 * Math.sin(2.5 * azimuth + phase));
            double top = base + Math.toRadians(AURORA_HEIGHT_DEGREES);
            float dx = (float) Math.sin(azimuth);
            float dz = (float) -Math.cos(azimuth);
            vertices[s * 2] = skyVertex(dx, dz, base, radius, u, bandBottom);
            vertices[s * 2 + 1] = skyVertex(dx, dz, top, radius, u, bandTop);
        }
        int[] indices = new int[AURORA_SEGMENTS * 6];
        int n = 0;
        for (int s = 0; s < AURORA_SEGMENTS; s++) {
            int a = s * 2;
            indices[n++] = a;
            indices[n++] = a + 2;
            indices[n++] = a + 3;
            indices[n++] = a;
            indices[n++] = a + 3;
            indices[n++] = a + 1;
        }
        return createMesh(material, vertices, indices);
    }

    private static HardcodedVertex skyVertex(float dx, float dz, double elevation, float radius, float u, float v) {
        float horizontal = (float) Math.cos(elevation);
        float y = (float) Math.sin(elevation);
        return HardcodedVertex.create(dx * horizontal * radius, y * radius, dz * horizontal * radius,
                -dx * horizontal, -y, -dz * horizontal, u, v, 0xFFFFFFFF);
    }

    private static RemixMesh createMesh(RemixMaterial material, HardcodedVertex[] vertices, int[] indices) {
        RemixMeshSurface surface = new RemixMeshSurface(material).setVertices(vertices).setIndices(indices);
        return RemixMeshStore.get().create(new RemixMesh(new RemixMeshSurface[]{surface}));
    }

    private void destroyCirrus() {
        destroyAsset(cirrusMesh);
        destroyAsset(cirrusMaterial);
        destroyAsset(cirrusTexture);
        cirrusMesh = null;
        cirrusMaterial = null;
        cirrusTexture = null;
        builtCoverage = -1.0f;
        lastCirrusIntensity = -1.0f;
    }

    /** Destroys every asset; the next frame rebuilds them. */
    void destroy() {
        auroraVisibility = 0.0f;
        destroyCirrus();
        if (pendingCirrus != null) pendingCirrus.cancel(false);
        pendingCirrus = null;
        pendingCoverage = -1.0f;
        for (int i = 0; i < AURORA_CURTAINS; i++) {
            destroyAsset(auroraMeshes[i]);
            destroyAsset(auroraMaterials[i]);
            auroraMeshes[i] = null;
            auroraMaterials[i] = null;
        }
        destroyAsset(auroraTexture);
        auroraTexture = null;
        if (pendingAurora != null) pendingAurora.cancel(false);
        pendingAurora = null;
        failed = false;
    }

    private static void destroyAsset(RemixAssetBase asset) {
        if (asset != null && !asset.isDeleted()) asset.destroy();
    }
}
