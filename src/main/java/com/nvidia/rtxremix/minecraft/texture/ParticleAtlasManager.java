/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import com.nvidia.rtxremix.minecraft.util.DirectBufferPool;
import com.nvidia.rtxremix.minecraft.mixin.SpriteAtlasAccessor;
import com.nvidia.rtxremix.minecraft.asset.RemixTextureStore;
import com.nvidia.rtxremix.minecraft.asset.RemixTexture;
import com.nvidia.rtxremix.minecraft.asset.RemixMaterialStore;
import com.nvidia.rtxremix.minecraft.asset.RemixMaterial;
import com.nvidia.rtxremix.minecraft.RemixRenderer;
import com.mojang.blaze3d.platform.NativeImage;
import com.nvidia.rtxremix.MaterialInfo;
import com.nvidia.rtxremix.MaterialInfoOpaqueEXT;
import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.RemixException;
import com.nvidia.rtxremix.TextureFormat;
import com.nvidia.rtxremix.TexturePurpose;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;

/**
 * Captures the particle and item sprite atlases and builds, for each, parallel
 * LabPBR atlas textures plus one atlas-wide {@link MaterialInfoOpaqueEXT} that
 * carries its own alpha test rather than draw-call alpha state. Blending is chosen
 * per instance by {@link RemixParticleRenderer} through {@link InstanceInfoBlendEXT},
 * so one material serves both the alpha-tested and the blended particle passes.
 *
 * <p>Mirrors {@link BlockTextureManager#buildAtlasMaterials} but operates on the
 * particle atlas and on the item atlas, which item particles such as the shards of
 * a broken tool or an eaten apple sample.
 *
 * <p>TERRAIN_SHEET particles reuse materials from {@link BlockTextureManager}
 * rather than creating new ones here.
 */
public class ParticleAtlasManager {

    /** The existing particle-atlas intensity, shared by vanilla and extracted geometry. */
    public static final float EMISSION_INTENSITY = 40.0f;

    /** Resource readiness is retryable and must not quarantine an otherwise healthy producer. */
    public static final class NotReady extends RuntimeException {
        public NotReady(Identifier atlas) { super("Particle atlas is not stitched: " + atlas); }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(ParticleAtlasManager.class);

    private final RemixRenderer renderer;

    private final List<RemixTexture> managedTextures = new ArrayList<>();
    private final List<RemixMaterial> managedMaterials = new ArrayList<>();

    private RemixMaterial material;
    private RemixMaterial itemMaterial;
    private final java.util.Map<Identifier, ParticleMaterialTextures> geometryTextures = new java.util.HashMap<>();
    private final java.util.Map<Identifier, ParticleSpriteBounds> geometryBounds = new java.util.HashMap<>();

    private boolean initialized;
    private boolean initFailed;
    private int preparedRevision = -1;

    /** Runs at initialization/reload, before particle extraction; unrelated atlases are untouched. */
    public void prepareDeclaredAtlases() {
        if (ParticleAtlasDependencies.atlases().isEmpty()) return;
        int revision = ParticleAtlasDependencies.revision();
        if (preparedRevision == revision) return;
        prepareDeclaredAtlases(revision);
    }

    private void prepareDeclaredAtlases(int revision) {
        initialize();
        if (!initialized) return;
        Minecraft client = Minecraft.getInstance();
        LabPBRConverter.VariantCache variants = null;
        for (Identifier id : ParticleAtlasDependencies.atlases()) {
            if (TextureAtlas.LOCATION_PARTICLES.equals(id) || TextureAtlas.LOCATION_ITEMS.equals(id)
                    || TextureAtlas.LOCATION_BLOCKS.equals(id) || geometryTextures.containsKey(id)) continue;
            TextureAtlas atlas = stitchedAtlas(client, id);
            if (atlas == null) continue;
            if (variants == null) variants = LabPBRConverter.buildVariantCache(client.getResourceManager());
            try {
                buildAtlasMaterial(atlas, "custom_particle_atlas_" + geometryTextures.size(), "", variants);
                geometryBounds(id);
            } catch (RuntimeException failure) {
                LOGGER.error("Could not prepare particle atlas {} during resource initialization", id, failure);
            }
        }
        preparedRevision = revision;
    }

    public ParticleAtlasManager(RemixRenderer renderer) {
        this.renderer = renderer;
    }

    public void initialize() {
        if (initialized || initFailed) return;
        try {
            RemixAPI api = renderer.getRemixApi();
            Minecraft client = Minecraft.getInstance();
            if (api == null || client == null || client.getTextureManager() == null) return;

            TextureAtlas particles = stitchedAtlas(client, TextureAtlas.LOCATION_PARTICLES);
            if (particles == null) return;

            LabPBRConverter.VariantCache labPBRVariants =
                    LabPBRConverter.buildVariantCache(client.getResourceManager());
            // Particle sprites are named without their folder; item atlas sprites carry it.
            material = buildAtlasMaterial(particles, "particle_atlas", "particle/", labPBRVariants);
            if (material == null) {
                LOGGER.error("Failed to create particle material(s)");
                initFailed = true;
                return;
            }

            // Item particles are skipped without it, so the particle material alone still serves.
            TextureAtlas items = stitchedAtlas(client, TextureAtlas.LOCATION_ITEMS);
            itemMaterial = items == null
                    ? null : buildAtlasMaterial(items, "item_particle_atlas", "", labPBRVariants);
            if (itemMaterial == null) {
                LOGGER.warn("No item atlas material; item particles are not drawn");
            }

            initialized = true;
        } catch (Exception e) {
            LOGGER.error("Particle atlas init failed", e);
            initFailed = true;
        }
    }

    /** The atlas once Minecraft has stitched it, or null to try again on a later frame. */
    private static TextureAtlas stitchedAtlas(Minecraft client, Identifier atlasId) {
        AbstractTexture tex = client.getTextureManager().getTexture(atlasId);
        if (!(tex instanceof TextureAtlas atlas)) return null;
        SpriteAtlasAccessor acc = (SpriteAtlasAccessor) atlas;
        if (acc.getWidth() == 0 || acc.getHeight() == 0) {
            LOGGER.warn("Atlas {} dimensions are zero, deferring init", atlasId);
            return null;
        }
        Map<Identifier, TextureAtlasSprite> sprites = acc.getSprites();
        if (sprites == null || sprites.isEmpty()) {
            LOGGER.warn("Atlas {} has no sprites, deferring init", atlasId);
            return null;
        }
        return atlas;
    }

    /**
     * Stitches {@code atlas} with its LabPBR companions, looked up as
     * {@code <labPbrPrefix><sprite path>}, and returns its material, or null when the
     * albedo or the material could not be created.
     */
    private RemixMaterial buildAtlasMaterial(TextureAtlas atlas, String name, String labPbrPrefix,
                                             LabPBRConverter.VariantCache labPBRVariants) {
        SpriteAtlasAccessor acc = (SpriteAtlasAccessor) atlas;
        int atlasW = acc.getWidth();
        int atlasH = acc.getHeight();
        Map<Identifier, TextureAtlasSprite> sprites = acc.getSprites();

        byte[] albedoRGBA = BlockTextureManager.stitchAtlasRGBA(atlas);

        int pixels = atlasW * atlasH;
        byte[] normalAtlas    = new byte[pixels * 4];
        byte[] heightAtlas    = new byte[pixels];
        byte[] roughnessAtlas = new byte[pixels];
        byte[] metallicAtlas  = new byte[pixels];
        byte[] emissiveAtlas  = new byte[pixels * 4];

        Arrays.fill(heightAtlas, (byte) 255);
        Arrays.fill(roughnessAtlas, (byte) 180);
        for (int i = 0; i < pixels; i++) {
            int idx4 = i * 4;
            normalAtlas[idx4]     = (byte) 128;
            normalAtlas[idx4 + 1] = (byte) 128;
            normalAtlas[idx4 + 2] = (byte) 255;
            normalAtlas[idx4 + 3] = (byte) 255;
        }

        boolean hasAnyNormal = false, hasAnyHeight = false, hasAnyEmissive = false;
        List<Long> authoredEmissionBounds = new ArrayList<>();

        for (var entry : sprites.entrySet()) {
            Identifier spriteId = entry.getKey();
            TextureAtlasSprite sprite = entry.getValue();
            int pixX = AtlasSprites.contentX(sprite), pixY = AtlasSprites.contentY(sprite);
            int pixW = sprite.contents().width();
            int pixH = sprite.contents().height();

            Identifier texId = Identifier.fromNamespaceAndPath(spriteId.getNamespace(),
                    labPbrPrefix + spriteId.getPath());
            NativeImage nImg = labPBRVariants.loadVariant(texId, "_n");
            NativeImage sImg = labPBRVariants.loadVariant(texId, "_s");
            try {
                if (nImg != null) {
                    LabPBRConverter.blitNormal(normalAtlas, atlasW, nImg, pixX, pixY, pixW, pixH);
                    LabPBRConverter.blitHeight(heightAtlas, atlasW, nImg, pixX, pixY, pixW, pixH);
                    LabPBRConverter.applyAO(albedoRGBA, atlasW, nImg, pixX, pixY, pixW, pixH);
                    hasAnyNormal = true;
                    hasAnyHeight = true;
                }
                if (sImg != null) {
                    LabPBRConverter.blitRoughness(roughnessAtlas, atlasW, sImg, pixX, pixY, pixW, pixH);
                    LabPBRConverter.blitMetallic(metallicAtlas, atlasW, sImg, pixX, pixY, pixW, pixH);
                }
                if (sImg != null && LabPBRConverter.hasEmissiveData(sImg)) {
                    LabPBRConverter.blitEmissive(emissiveAtlas, atlasW, albedoRGBA, sImg,
                            pixX, pixY, pixW, pixH, 0);
                    hasAnyEmissive = true;
                    authoredEmissionBounds.add(ParticleMaterialTextures.boundsKey(
                            com.nvidia.rtxremix.HardcodedVertex.packHalf2x16(sprite.getU0(), sprite.getV0()),
                            com.nvidia.rtxremix.HardcodedVertex.packHalf2x16(sprite.getU1(), sprite.getV1())));
                }
            } finally {
                if (nImg != null) nImg.close();
                if (sImg != null) sImg.close();
            }
        }

        AtlasSprites.Padding padding = AtlasSprites.padding(sprites.values(), atlasW, atlasH);
        padding.fill(albedoRGBA, 4);
        padding.fill(normalAtlas, 4);
        padding.fill(heightAtlas, 1);
        padding.fill(roughnessAtlas, 1);
        padding.fill(metallicAtlas, 1);
        padding.fill(emissiveAtlas, 4);

        RemixTexture albedoTex = uploadTexture(name + "_albedo",
                albedoRGBA, atlasW, atlasH, TextureFormat.RGBA8_UNORM, TexturePurpose.COLOR);
        if (albedoTex == null) {
            LOGGER.error("Failed to upload {} albedo", name);
            return null;
        }

        RemixTexture normalTex = hasAnyNormal
                ? uploadTexture(name + "_normal", normalAtlas, atlasW, atlasH,
                        TextureFormat.RGBA8_UNORM, TexturePurpose.NORMAL_DX) : null;
        RemixTexture heightTex = hasAnyHeight
                ? uploadTexture(name + "_height", heightAtlas, atlasW, atlasH,
                        TextureFormat.R8_UNORM, TexturePurpose.HEIGHTMAP) : null;
        RemixTexture roughnessTex = uploadTexture(name + "_roughness",
                roughnessAtlas, atlasW, atlasH, TextureFormat.R8_UNORM, TexturePurpose.DATA);
        RemixTexture metallicTex = uploadTexture(name + "_metallic",
                metallicAtlas, atlasW, atlasH, TextureFormat.R8_UNORM, TexturePurpose.DATA);
        RemixTexture emissiveTex = hasAnyEmissive
                ? uploadTexture(name + "_emissive", emissiveAtlas, atlasW, atlasH,
                        TextureFormat.RGBA8_UNORM, TexturePurpose.COLOR) : null;

        RemixMaterial atlasMaterial = buildOpaqueMaterial(albedoTex, normalTex, heightTex,
                roughnessTex, metallicTex, emissiveTex);
        geometryTextures.put(atlas.location(), new ParticleMaterialTextures(albedoTex, normalTex, heightTex,
                roughnessTex, metallicTex, emissiveTex, EMISSION_INTENSITY,
                authoredEmissionBounds.stream().mapToLong(Long::longValue).sorted().toArray()));
        if (atlasMaterial != null) {
            LOGGER.info("{} initialized ({}x{}, {} sprites, normal={}, height={}, emissive={})",
                    name, atlasW, atlasH, sprites.size(), hasAnyNormal, hasAnyHeight, hasAnyEmissive);
        }
        return atlasMaterial;
    }

    private RemixMaterial buildOpaqueMaterial(RemixTexture albedo, RemixTexture normal,
                                              RemixTexture height, RemixTexture roughness,
                                              RemixTexture metallic, RemixTexture emissive) {
        MaterialInfoOpaqueEXT.Builder ob = MaterialInfoOpaqueEXT.builder()
                .albedoConstant(1f, 1f, 1f)
                .roughnessConstant(1f)
                .metallicConstant(0f)
                .blendType(MaterialInfoOpaqueEXT.BLEND_TYPE_ALPHA)
                .useDrawCallAlphaState(false)
                .alphaTestType(4)
                .alphaReferenceValue(10);
        if (roughness != null) ob.roughnessTexture(roughness.getHandle());
        if (metallic != null)  ob.metallicTexture(metallic.getHandle());
        if (height != null)    ob.heightTexture(height.getHandle()).displaceIn(0.02f);

        MaterialInfo.Builder mb = MaterialInfo.builder()
                .albedoTexture(albedo.getHandle())
                .filterMode(0);
        if (normal != null)   mb.normalTexture(normal.getHandle());
        if (emissive != null) mb.emissiveTexture(emissive.getHandle()).emissiveIntensity(EMISSION_INTENSITY);

        return registerMaterial(mb.opaqueExt(ob.build()).build());
    }

    public RemixMaterial getMaterial()  { return material; }
    /** The item atlas's material, or null when the item atlas could not be captured. */
    public RemixMaterial getItemMaterial() { return itemMaterial; }
    public boolean isInitialized()     { return initialized; }

    /** Built only for a source using the public vertex stream, never vanilla slots. */
    public ParticleSpriteBounds geometryBounds(Identifier atlasId) {
        ParticleSpriteBounds bounds = geometryBounds.get(atlasId);
        if (bounds != null) return bounds;
        TextureAtlas atlas = stitchedAtlas(Minecraft.getInstance(), atlasId);
        if (atlas == null) throw new NotReady(atlasId);
        bounds = ParticleSpriteBounds.sprites(((SpriteAtlasAccessor) atlas).getSprites().values());
        geometryBounds.put(atlasId, bounds);
        return bounds;
    }

    /** Reuses the existing atlas uploads; an additional atlas is captured only when requested. */
    public ParticleMaterialTextures geometryTextures(Identifier atlasId) {
        if (TextureAtlas.LOCATION_BLOCKS.equals(atlasId)) {
            return renderer.getTextureManager().particleGeometryTextures();
        }
        ParticleMaterialTextures textures = geometryTextures.get(atlasId);
        if (textures != null) return textures;
        TextureAtlas atlas = stitchedAtlas(Minecraft.getInstance(), atlasId);
        if (atlas == null) throw new NotReady(atlasId);
        // A layer created during extraction cannot be predicted by a resource preloader.
        // Loading here preserves its first frame; early registration avoids this fallback.
        LOGGER.warn("Particle atlas {} was declared after resource preparation; capturing now. "
                + "Register its layer/atlas during resource initialization to avoid first-use work", atlasId);
        buildAtlasMaterial(atlas, "custom_particle_atlas_" + geometryTextures.size(), "",
                LabPBRConverter.buildVariantCache(Minecraft.getInstance().getResourceManager()));
        textures = geometryTextures.get(atlasId);
        if (textures == null) throw new IllegalStateException("Could not capture particle atlas: " + atlasId);
        return textures;
    }

    private RemixTexture uploadTexture(String name, byte[] data, int w, int h, int fmt, int purpose) {
        ByteBuffer buf = DirectBufferPool.acquire(data.length);
        buf.put(data).flip();
        try {
            RemixTexture tex = new RemixTexture(w, h, fmt, purpose, buf);
            // The texture owns the buffer from here: RemixAssetBase disposes it after a
            // successful create and on the create failure path alike, so releasing it
            // here as well would release it twice.
            RemixTextureStore.get().create(tex);
            managedTextures.add(tex);
            return tex;
        } catch (RemixException e) {
            LOGGER.error("Failed to upload particle texture '{}': {}", name, e.getMessage());
            return null;
        }
    }

    private RemixMaterial registerMaterial(MaterialInfo matInfo) {
        try {
            RemixMaterial mat = new RemixMaterial(matInfo);
            RemixMaterialStore.get().create(mat);
            managedMaterials.add(mat);
            return mat;
        } catch (RemixException e) {
            LOGGER.error("Failed to create particle material: {}", e.getMessage());
            return null;
        }
    }

    public void cleanup() {
        for (RemixMaterial mat : managedMaterials)
            if (!mat.isDeleted()) mat.destroy();
        managedMaterials.clear();

        for (RemixTexture tex : managedTextures)
            if (!tex.isDeleted()) tex.destroy();
        managedTextures.clear();

        material = null;
        itemMaterial = null;
        geometryTextures.clear();
        geometryBounds.clear();
        preparedRevision = -1;
        initialized = false;
        initFailed = false;
    }
}
