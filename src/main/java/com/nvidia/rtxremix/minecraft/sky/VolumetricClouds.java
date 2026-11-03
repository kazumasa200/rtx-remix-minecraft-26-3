/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.sky;

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
import com.nvidia.rtxremix.minecraft.cloud.CloudRenderer;
import com.nvidia.rtxremix.minecraft.light.LightManager;
import com.nvidia.rtxremix.minecraft.util.DirectBufferPool;
import java.nio.ByteBuffer;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Unbound cloud style: volumetric clouds marched on the GPU into a panorama and shown
 * on a dome in the sky category, over the sun, moon, stars and high sky layers, out to the
 * horizon however far the view distance ends. On screen the lens draws the same clouds per
 * pixel in place of the dome's, and their shadows on the land, from what this class
 * publishes in {@link CloudField}; the panorama's opacity toward the sun dims the sun's disc
 * that glints on water.
 *
 * <p>Render thread only.
 */
final class VolumetricClouds {

    private static final Logger LOGGER = LoggerFactory.getLogger(VolumetricClouds.class);

    /** Inside the aurora's and the cirrus layer's radii. */
    private static final float RADIUS = 96.0f;
    /**
     * Rings over the panorama's upper rows, and over the rows below them to the nadir, enough
     * of them to follow those rows' quadratic spacing.
     */
    private static final int RINGS = 64;
    private static final int LOWER_RINGS = 24;
    private static final int SEGMENTS = 128;
    /**
     * Marches per second while the camera holds still; each refreshes half the panorama and
     * costs one readback and upload.
     */
    private static final long DISPATCH_INTERVAL_NANOS = 1_000_000_000L / 15;
    /**
     * Least time between marches while the camera moves: every other frame at sixty. The lens
     * draws the clouds on screen per pixel, so only their reflections and the view from under
     * water follow the panorama; marched every frame, its march, readback and upload cost about
     * a millisecond and a half of each frame on the move at 4K.
     */
    private static final long MOVING_DISPATCH_INTERVAL_NANOS = 30_000_000L;
    /** Blocks the camera moves between marches beyond which it is moving. */
    private static final double MOVING_BLOCKS = 0.001;
    /** Blocks above the clouds' base where a lightning bolt's light sits in them. */
    private static final float FLASH_HEIGHT_IN_CLOUD = 40.0f;
    /**
     * The irradiance a lightning flash gives a cloud right at the bolt, in the units of the
     * sun's: about what sunlight gives a cloud by day, so the cloud around the bolt shows as
     * brightly as a lit cloud.
     */
    private static final float FLASH_IRRADIANCE = 30.0f;
    /** Averages a texel over about seven marches, which at 7.5 a second follows the light within a second. */
    private static final float HISTORY_WEIGHT = 0.15f;
    /** A camera move this far between dispatches is a teleport, and restarts the running average. */
    private static final double RESET_DISTANCE = 48.0;
    /**
     * Blocks of camera travel between dispatches that add a whole march's weight to the running
     * average: a moving camera sees the clouds shift, so the average follows them faster
     * instead of trailing a second behind, and the land's march, which has no average, agrees.
     */
    private static final double MOTION_BLOCKS = 4.0;
    /** Blocks the clouds drift per tick at speed 1, along a fixed wind. */
    private static final double WIND_BLOCKS_PER_TICK = 0.15;
    private static final double WIND_X = 0.96;
    private static final double WIND_Z = 0.28;
    /**
     * Every noise period in cloud_field.glsl divides this at feature scale 1, so wrapping by it
     * shows no seam. At 18000, which most of them did not divide, the whole sky turned into
     * other clouds whenever the wind or the camera carried the frame across the wrap.
     */
    static final double WRAP_PERIOD = 180000.0;
    private static final float HAZE_DISTANCE = 9000.0f;
    private static final float GROUND_ALBEDO = 0.3f;
    /**
     * Seconds for the sun's disc in the sky's dome texture to follow a cloud moving over the
     * sun or away from it. Slow enough that the sun racing past cloud edges, as it does when
     * time is sped up, fades a glint on water gently instead of flashing it.
     */
    private static final float SHADOW_FADE_SECONDS = 2.0f;
    /** Texels on each side of the light's direction averaged for its occlusion. */
    private static final int SHADOW_SAMPLE_RADIUS = 2;
    /** Blocks over which the cloud at the camera is judged: one that stops most light over them surrounds it. */
    private static final float INSIDE_CLOUD_PATH = 100.0f;
    private static final InstanceInfoBlendEXT BLEND = premultipliedBlend();

    private final VolumetricCloudGenerator.Frame frame = new VolumetricCloudGenerator.Frame();
    private final TexturePatch patch = new TexturePatch();
    private final TextureBatchEntry entry = new TextureBatchEntry();
    private final TexturePatchInfo update = new TexturePatchInfo();
    private final Transform transform = new Transform();

    private VolumetricCloudGenerator generator;
    private RemixTexture texture;
    private RemixMaterial material;
    private RemixMesh mesh;
    private long blendPNext;
    private long lastDispatchNanos;
    private int dispatches;
    private boolean restart = true;
    private int seenTimeJump = SkyTimeJump.generation();
    private double lastCameraX;
    private double lastCameraY;
    private double lastCameraZ;
    private boolean failed;
    private int builtSkyMeshGeneration;
    private int uploadedCirrusVersion = -1;
    private float discVisibility = 1.0f;
    private float targetDiscVisibility = 1.0f;
    private long lastNanos;
    private final CloudWeather weather = new CloudWeather();

    VolumetricClouds() {
        patch.width = VolumetricCloudGenerator.WIDTH;
        patch.height = VolumetricCloudGenerator.HEIGHT;
        patch.rowPitch = VolumetricCloudGenerator.WIDTH * 4 * Short.BYTES;
        patch.format = TextureFormat.RGBA16_SFLOAT;
        entry.patches = new TexturePatch[]{patch};
        update.entries = new TextureBatchEntry[]{entry};
    }

    /**
     * Marches, publishes and draws the clouds for this frame, or releases them when the
     * Unbound style is not showing. Call it after everything else drawn in the sky.
     *
     * @param ambientTop the upper sky's mean radiance
     * @param rain the rain strength, 0 to 1
     * @param skyLayers the high sky layers, whose cirrus the clouds draw behind themselves
     * @param weatherVisibility how much of the high sky the weather leaves visible, 0 to 1
     */
    void render(RemixAPI api, ClientLevel world, Vec3 cameraPos, float tickDelta, CloudRenderer clouds,
                LightManager.SkyLightState light, float[] ambientTop, float rain, SkyLayers skyLayers,
                float weatherVisibility) {
        int skyMeshGeneration = skyLayers.meshGeneration();
        float base = clouds == null ? Float.NaN : clouds.unboundBaseHeight();
        if (failed || Float.isNaN(base)) {
            destroyAssets();
            return;
        }
        try {
            if (generator == null) {
                generator = new VolumetricCloudGenerator();
            }
            if (skyLayers.cirrusVersion() != uploadedCirrusVersion) {
                byte[] cirrus = skyLayers.cirrusPixels();
                generator.uploadCirrus(cirrus != null ? cirrus : new byte[4], cirrus != null ? SkyLayers.CIRRUS_SIZE : 1);
                uploadedCirrusVersion = skyLayers.cirrusVersion();
            }
            frame.cirrusAngle = skyLayers.cirrusAngle();
            frame.cirrusStrength = skyLayers.cirrusPixels() != null
                    ? SkyLook.getCirrusBrightness() * Math.clamp(weatherVisibility, 0.0f, 1.0f)
                    : 0.0f;
            long now = System.nanoTime();
            // The panorama is marched from where the camera stands, so as the camera moves the
            // clouds' parallax changes every frame. Marched only fifteen times a second, the sky
            // and its reflections stepped along with it; on the move it is marched thirty times.
            boolean moving = cameraPos.distanceToSqr(lastCameraX, lastCameraY, lastCameraZ)
                    > MOVING_BLOCKS * MOVING_BLOCKS;
            // After a jump in the time of day the panorama starts over from the new light.
            if (SkyTimeJump.generation() != seenTimeJump) {
                seenTimeJump = SkyTimeJump.generation();
                restart = true;
            }
            // A lightning flash lasts a few frames, so it is marched every frame while it changes.
            long interval = LightningFlash.changing() ? 0L
                    : moving ? MOVING_DISPATCH_INTERVAL_NANOS : DISPATCH_INTERVAL_NANOS;
            if ((restart || now - lastDispatchNanos >= interval)
                    && dispatch(world, cameraPos, tickDelta, clouds, base, light, ambientTop, rain)) {
                lastDispatchNanos = now;
            }
            publishField(world, cameraPos, tickDelta, clouds);
            ByteBuffer pixels = generator.consumeCompletedReadback();
            if (pixels != null) {
                // Inside a cloud the cloud toward the sun is mostly the one around the camera,
                // whose mist the lens already draws over everything, a glint included.
                float inside = 1.0f - (float) Math.exp(-generator.cameraExtinction() * INSIDE_CLOUD_PATH);
                targetDiscVisibility = 1.0f - (1.0f - inside) * opacityToward(pixels, frame.lightDirection);
                publish(api, pixels);
                CloudField.show(generator.shownPanoramaTexture());
            }
            float seconds = lastNanos == 0 ? 0.0f : Math.min(0.25f, (now - lastNanos) / 1.0e9f);
            lastNanos = now;
            discVisibility += (targetDiscVisibility - discVisibility) * Math.min(1.0f, seconds / SHADOW_FADE_SECONDS);
            LightManager.setSunDiscVisibility(discVisibility);
            // Remix draws sky meshes in the order they were created, not the order they are
            // drawn in, so the dome is rebuilt after any newer sky mesh to stay over it.
            if (mesh != null && skyMeshGeneration != builtSkyMeshGeneration) {
                destroyAsset(mesh);
                mesh = createMesh(material);
            }
            builtSkyMeshGeneration = skyMeshGeneration;
            if (mesh != null) {
                float[] m = transform.matrix;
                m[0] = 1.0f; m[1] = 0.0f; m[2] = 0.0f;  m[3] = SceneSpace.x(cameraPos.x);
                m[4] = 0.0f; m[5] = 1.0f; m[6] = 0.0f;  m[7] = SceneSpace.y(cameraPos.y);
                m[8] = 0.0f; m[9] = 0.0f; m[10] = 1.0f; m[11] = SceneSpace.z(cameraPos.z);
                api.drawInstance(mesh.getHandle(), m, InstanceCategoryBit.SKY, 1, blendPNext);
            }
        } catch (RuntimeException e) {
            LOGGER.error("Failed to draw the volumetric clouds; turning them off", e);
            destroyAssets();
            destroyGenerator();
            failed = true;
        }
    }

    /** Hands the lens this frame's camera in the clouds' frame, where it marches them per pixel. */
    private void publishField(ClientLevel world, Vec3 cameraPos, float tickDelta, CloudRenderer clouds) {
        double drift = (world.getGameTime() + (double) tickDelta) * WIND_BLOCKS_PER_TICK * clouds.getSpeed();
        double period = WRAP_PERIOD * clouds.getCloudSize();
        CloudField.publish(generator.noiseTexture(), generator.cirrusTexture(),
                wrap(cameraPos.x + drift * WIND_X, period), cameraPos.y, wrap(cameraPos.z + drift * WIND_Z, period),
                clouds.getUnboundShadow());
    }

    private boolean dispatch(ClientLevel world, Vec3 cameraPos, float tickDelta, CloudRenderer clouds, float base,
                             LightManager.SkyLightState light, float[] ambientTop, float rain) {
        float scale = clouds.getCloudSize();
        double drift = (world.getGameTime() + (double) tickDelta) * WIND_BLOCKS_PER_TICK * clouds.getSpeed();
        double period = WRAP_PERIOD * scale;
        frame.cameraX = (float) wrap(cameraPos.x + drift * WIND_X, period);
        frame.cameraZ = (float) wrap(cameraPos.z + drift * WIND_Z, period);
        frame.cameraHeight = (float) cameraPos.y;
        frame.frameIndex = dispatches % 1024;

        // Lit from the brighter of the sun and the moon by both together, so the light changes
        // continuously through dusk instead of jumping from one to the other.
        float[] sun = light.sunDirectRadiance();
        float[] moon = light.moonDirectRadiance();
        float[] direction = luminance(sun) >= luminance(moon) ? light.sunPosition() : light.moonPosition();
        float length = (float) Math.sqrt(direction[0] * direction[0] + direction[1] * direction[1]
                + direction[2] * direction[2]);
        float overcast = Math.clamp(rain, 0.0f, 1.0f) * clouds.getUnboundRainInfluence();
        float skyShare = 1.0f - 0.5f * overcast;
        // Light the ground sends back up into the cloud bases.
        float bounce = GROUND_ALBEDO / (float) Math.PI * Math.max(0.0f, direction[1] / Math.max(length, 1.0e-6f));
        for (int i = 0; i < 3; i++) {
            frame.lightDirection[i] = direction[i] / Math.max(length, 1.0e-6f);
            frame.lightIrradiance[i] = sun[i] + moon[i];
            frame.ambientTop[i] = ambientTop[i] * skyShare;
            frame.ambientBottom[i] = ambientTop[i] * skyShare * 0.35f + frame.lightIrradiance[i] * bounce;
        }
        float thunder = world.getThunderLevel(tickDelta);
        weather.update(world, Math.clamp(rain, 0.0f, 1.0f), thunder, clouds.getCoverage());
        for (int i = 0; i < 3; i++) {
            frame.coverage[i] = weather.coverage[i] + (0.95f - weather.coverage[i]) * overcast;
        }
        frame.density = clouds.getUnboundDensity() * (1.0f + overcast);
        frame.base = base;
        frame.thickness = clouds.getUnboundThickness();
        frame.featureScale = scale;
        double dx = cameraPos.x - lastCameraX;
        double dy = cameraPos.y - lastCameraY;
        double dz = cameraPos.z - lastCameraZ;
        double moved = Math.sqrt(dx * dx + dy * dy + dz * dz);
        boolean jumped = restart || moved > RESET_DISTANCE;
        frame.historyWeight = jumped || LightningFlash.changing() ? 1.0f
                : (float) Math.min(1.0, HISTORY_WEIGHT + moved / MOTION_BLOCKS);
        // The bolt reaches into the clouds above where it struck, a little up into their base.
        frame.flash[0] = (float) (frame.cameraX + LightningFlash.x() - cameraPos.x);
        frame.flash[1] = base + FLASH_HEIGHT_IN_CLOUD;
        frame.flash[2] = (float) (frame.cameraZ + LightningFlash.z() - cameraPos.z);
        frame.flash[3] = LightningFlash.strength() * FLASH_IRRADIANCE;
        frame.brightness = clouds.getUnboundBrightness();
        frame.parity = dispatches & 1;
        frame.hazeDistance = HAZE_DISTANCE;
        frame.detail = clouds.getUnboundDetail();
        frame.shapes.update(wrap(cameraPos.x + drift * WIND_X, period), wrap(cameraPos.z + drift * WIND_Z, period),
                period, base, frame.thickness, scale, weather.calm,
                (world.getGameTime() + (double) tickDelta) / 24000.0);
        if (!generator.dispatch(frame)) {
            return false;
        }
        CloudField.setFrame(frame);
        dispatches++;
        restart = false;
        lastCameraX = cameraPos.x;
        lastCameraY = cameraPos.y;
        lastCameraZ = cameraPos.z;
        return true;
    }

    private void publish(RemixAPI api, ByteBuffer pixels) {
        if (texture == null || !texture.isAlive() || material == null || !material.isAlive()
                || mesh == null || !mesh.isAlive()) {
            destroyAssets();
            ByteBuffer owned = DirectBufferPool.acquire(VolumetricCloudGenerator.OUTPUT_BYTES);
            owned.put(pixels).flip();
            texture = RemixTextureStore.get().create(new RemixTexture(
                    VolumetricCloudGenerator.WIDTH, VolumetricCloudGenerator.HEIGHT,
                    TextureFormat.RGBA16_SFLOAT, TexturePurpose.COLOR, owned));
            material = RemixMaterialStore.get().create(
                    new RemixMaterial(materialInfo(texture), List.of(texture), null));
            mesh = createMesh(material);
            if (blendPNext == 0) {
                blendPNext = BLEND.allocNative(0);
            }
            return;
        }
        patch.pixelData = pixels;
        entry.handle = texture.getHandle();
        api.updateTextureFromMemory(update);
    }

    /** Premultiplied emission over the sky: the clouds add their light and hide what lies behind. */
    private static InstanceInfoBlendEXT premultipliedBlend() {
        InstanceInfoBlendEXT blend = InstanceInfoBlendEXT.textureTimesVertexColorTextureAlphaBlend(0);
        blend.srcColorBlendFactor = InstanceInfoBlendEXT.VK_BLEND_FACTOR_ONE;
        blend.dstColorBlendFactor = InstanceInfoBlendEXT.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
        blend.srcAlphaBlendFactor = InstanceInfoBlendEXT.VK_BLEND_FACTOR_ZERO;
        blend.dstAlphaBlendFactor = InstanceInfoBlendEXT.VK_BLEND_FACTOR_ONE;
        return blend;
    }

    /** The texture holds radiance itself, so the material never changes after it is made. */
    private static MaterialInfo materialInfo(RemixTexture texture) {
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
                .emissiveIntensity(1.0f)
                .filterMode(1)
                .wrapMode(MaterialInfo.WRAP_MODE_REPEAT, MaterialInfo.WRAP_MODE_CLAMP)
                .opaqueExt(opaque)
                .build();
    }

    /** Rings from the zenith to the nadir, mapped as the shader lays the panorama out. */
    private static RemixMesh createMesh(RemixMaterial material) {
        int ringVertices = SEGMENTS + 1;
        int rings = RINGS + LOWER_RINGS;
        float split = VolumetricCloudGenerator.UPPER_ROWS / (float) VolumetricCloudGenerator.HEIGHT;
        HardcodedVertex[] vertices = new HardcodedVertex[(rings + 1) * ringVertices];
        for (int ring = 0; ring <= rings; ring++) {
            // A ring on the split itself, so no triangle spans the change of scale.
            float v = ring <= RINGS
                    ? split * ring / RINGS
                    : split + (1.0f - split) * (ring - RINGS) / LOWER_RINGS;
            double elevation = Math.toRadians(VolumetricCloudGenerator.elevationDegrees(v));
            float horizontal = (float) Math.cos(elevation);
            float y = (float) Math.sin(elevation);
            for (int s = 0; s <= SEGMENTS; s++) {
                float u = s / (float) SEGMENTS;
                double azimuth = 2.0 * Math.PI * u;
                float dx = (float) Math.sin(azimuth) * horizontal;
                float dz = (float) -Math.cos(azimuth) * horizontal;
                vertices[ring * ringVertices + s] = HardcodedVertex.create(
                        dx * RADIUS, y * RADIUS, dz * RADIUS, -dx, -y, -dz, u, v, 0xFFFFFFFF);
            }
        }
        int[] indices = new int[rings * SEGMENTS * 6];
        int n = 0;
        for (int ring = 0; ring < rings; ring++) {
            int upper = ring * ringVertices;
            int lower = upper + ringVertices;
            for (int s = 0; s < SEGMENTS; s++) {
                indices[n++] = upper + s;
                indices[n++] = lower + s;
                indices[n++] = lower + s + 1;
                indices[n++] = upper + s;
                indices[n++] = lower + s + 1;
                indices[n++] = upper + s + 1;
            }
        }
        RemixMeshSurface surface = new RemixMeshSurface(material).setVertices(vertices).setIndices(indices);
        return RemixMeshStore.get().create(new RemixMesh(new RemixMeshSurface[]{surface}));
    }

    /**
     * The mean cloud opacity in a readback over a few texels around {@code direction}, which
     * covers the sun's or moon's disc; 0 below the panorama.
     */
    static float opacityToward(ByteBuffer pixels, float[] direction) {
        int width = VolumetricCloudGenerator.WIDTH;
        int height = VolumetricCloudGenerator.HEIGHT;
        double elevation = Math.toDegrees(Math.asin(Math.clamp(direction[1], -1.0f, 1.0f)));
        if (elevation < VolumetricCloudGenerator.MIN_ELEVATION_DEGREES) {
            return 0.0f;
        }
        // The panorama's azimuth runs from north (-Z) through east (+X), as the mesh lays it out.
        double azimuth = Math.atan2(direction[0], -direction[2]);
        if (azimuth < 0.0) {
            azimuth += 2.0 * Math.PI;
        }
        int centreX = (int) (azimuth / (2.0 * Math.PI) * width);
        int centreY = (int) (VolumetricCloudGenerator.rowCoordinate(elevation) * height);
        float sum = 0.0f;
        int count = 0;
        for (int dy = -SHADOW_SAMPLE_RADIUS; dy <= SHADOW_SAMPLE_RADIUS; dy++) {
            int y = Math.clamp(centreY + dy, 0, height - 1);
            for (int dx = -SHADOW_SAMPLE_RADIUS; dx <= SHADOW_SAMPLE_RADIUS; dx++) {
                int x = Math.floorMod(centreX + dx, width);
                sum += Float.float16ToFloat(pixels.getShort(((y * width + x) * 4 + 3) * Short.BYTES));
                count++;
            }
        }
        return sum / count;
    }

    private static double wrap(double value, double period) {
        return value - Math.floor(value / period) * period;
    }

    private static float luminance(float[] rgb) {
        return 0.2126f * rgb[0] + 0.7152f * rgb[1] + 0.0722f * rgb[2];
    }

    /** Destroys the Remix assets; the next readback rebuilds them and the next dispatch restarts the average. */
    void destroyAssets() {
        destroyAsset(mesh);
        destroyAsset(material);
        destroyAsset(texture);
        mesh = null;
        material = null;
        texture = null;
        patch.pixelData = null;
        BLEND.freeNative();
        blendPNext = 0;
        CloudField.withdraw();
        restart = true;
        discVisibility = 1.0f;
        targetDiscVisibility = 1.0f;
        lastNanos = 0;
        LightManager.setSunDiscVisibility(1.0f);
    }

    /** Releases the GL resources; call on the render thread with the context current. */
    void destroyGenerator() {
        CloudField.withdraw();
        if (generator != null) {
            generator.destroy();
            generator = null;
        }
        uploadedCirrusVersion = -1;
        failed = false;
    }

    private static void destroyAsset(RemixAssetBase asset) {
        if (asset != null && !asset.isDeleted()) {
            asset.destroy();
        }
    }
}
