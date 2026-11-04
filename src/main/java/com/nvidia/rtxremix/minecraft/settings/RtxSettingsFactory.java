/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.settings;

import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.RemixException;
import com.nvidia.rtxremix.minecraft.asset.SceneSpace;
import com.nvidia.rtxremix.minecraft.light.BlockLightIntensityControls;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import com.nvidia.rtxremix.minecraft.terrain.ChunkManager;
import com.nvidia.rtxremix.minecraft.light.LightManager;
import com.nvidia.rtxremix.minecraft.lod.LodTerrain;
import com.nvidia.rtxremix.minecraft.RemixRenderer;
import com.nvidia.rtxremix.minecraft.cloud.CloudRenderer;
import com.nvidia.rtxremix.minecraft.cloud.CloudStyle;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.sky.SkyLook;
import com.nvidia.rtxremix.minecraft.lens.LensEffects;
import com.nvidia.rtxremix.minecraft.lens.LensLook;
import com.nvidia.rtxremix.minecraft.sky.SkyRenderer;
import com.nvidia.rtxremix.minecraft.weather.Puddles;
import com.nvidia.rtxremix.minecraft.UnfocusedFramerateLimit;
import com.nvidia.rtxremix.minecraft.entity.RemixEntityPass;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Constructs the curated RtxSettings bound to a live, initialized RemixRenderer. */
public final class RtxSettingsFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(RtxSettingsFactory.class);

    /**
     * Minecraft's maximum render distance, and therefore the point past which a
     * cull can no longer remove anything.
     */
    private static final int MAX_DISTANCE_CHUNKS = 32;

    private RtxSettingsFactory() {}

    /**
     * Builds the curated registry.
     *
     * <p>A new setting is one entry here, bound to an authoritative live getter
     * and setter, plus the {@code rtxremix.setting.} label and {@code .tip}
     * keys for its id in the language file. It then appears in
     * the menu, persists, and resets without further wiring. A setting needs
     * both a live model and a defined authored baseline. A boolean Remix
     * option gets both from {@link #remixBool}.
     *
     * <p><b>A declared range must contain the live default.</b> Defaults are
     * captured from the live getters as this method runs. A default outside its
     * own {@code [min, max]} is rejected by the store's range guard when the
     * saved file is read back, so the setting silently loses its persisted
     * value. Sun and moon intensity are normalized to {@code 1.0}; the other
     * artist-authored controls expose their native values, so check the runtime
     * model as well as the range when adding one.
     *
     * <p><b>The label must fit its widget.</b> Option rows are 150 px vanilla
     * widgets that draw {@code Label: Value} with a 2 px margin, and wider text
     * scrolls as a marquee that clips the label. {@code SettingsRowWidthTest}
     * measures every label at its registered widest value with the real
     * default-font advances, so a new setting also needs an entry there.
     */
    public static RtxSettings build(RemixRenderer renderer) {
        List<RtxSetting<?>> list = new ArrayList<>();

        ChunkManager chunks = renderer.getChunkManager();
        RemixEntityPass entities = renderer.getEntityPass();
        PostProcessingSettings postProcessing = renderer.getPostProcessingSettings();

        // --- Render distances (live-bound to managers) ---
        list.add(RtxSetting.ofBool("dist.autoFade", SettingGroup.RENDER_DISTANCE,
                "rtxremix.setting.dist.autoFade", "rtxremix.setting.dist.autoFade.tip",
                renderer.isViewDistanceFadeAuto(), renderer::isViewDistanceFadeAuto,
                renderer::setViewDistanceFadeAuto));

        LodTerrain lod = renderer.getLodTerrain();
        list.add(RtxSetting.ofBool("dist.lod", SettingGroup.RENDER_DISTANCE,
                "rtxremix.setting.dist.lod", "rtxremix.setting.dist.lod.tip",
                lod.isEnabled(), lod::isEnabled, lod::setEnabled));
        list.add(RtxSetting.ofInt(RtxOptionFactory.LOD_MEMORY_ID, SettingGroup.RENDER_DISTANCE,
                "rtxremix.setting.dist.lodMemory", "rtxremix.setting.dist.lodMemory.tip",
                LodTerrain.MIN_MEMORY_MIB, LodTerrain.UNLIMITED_MEMORY_MIB, LodTerrain.DEFAULT_MEMORY_MIB,
                lod::getMemoryMib, lod::setMemoryMib)
                .withStep(LodTerrain.MEMORY_STEP_MIB).deferSliderApply());

        // Both ranges are 0 to 32 chunks and both zeros mean Auto, Minecraft's
        // render distance. 32 is Minecraft's maximum render distance and
        // therefore the point past which a cull stops culling anything, so one
        // ceiling fits every distance and they share a single sentinel position.
        list.add(RtxSetting.ofInt("dist.light", SettingGroup.RENDER_DISTANCE,
                "rtxremix.setting.dist.light", "rtxremix.setting.dist.light.tip",
                0, MAX_DISTANCE_CHUNKS, chunks.getLightCullDistance(),
                chunks::getLightCullDistance, chunks::setLightCullDistance));

        list.add(RtxSetting.ofInt("dist.entity", SettingGroup.RENDER_DISTANCE,
                "rtxremix.setting.dist.entity", "rtxremix.setting.dist.entity.tip",
                0, MAX_DISTANCE_CHUNKS, entities.getEntityCullDistance(),
                entities::getEntityCullDistance, entities::setEntityCullDistance));

        list.add(RtxSetting.ofInt("light.reach", SettingGroup.RENDER_DISTANCE,
                "rtxremix.setting.light.reach", "rtxremix.setting.light.reach.tip",
                ChunkManager.MIN_LIGHT_REACH_BLOCKS, ChunkManager.MAX_LIGHT_REACH_BLOCKS,
                chunks.getLightReach(), chunks::getLightReach, chunks::setLightReach));

        // --- Framerate ---
        list.add(unfocusedFramerate());

        // --- Sky & time of day ---
        list.add(RtxSetting.ofBool("sky.enabled", SettingGroup.SKY,
                "rtxremix.setting.sky.enabled", "rtxremix.setting.sky.enabled.tip",
                RtxRemixMod.isSkyEnabled(), RtxRemixMod::isSkyEnabled,
                v -> {
                    RtxRemixMod.setSkyEnabled(v);
                    if (renderer.getSkyRenderer() != null) {
                        renderer.getSkyRenderer().forceRefresh();
                    }
                }));

        list.add(RtxSetting.ofBool("sky.ambient", SettingGroup.SKY,
                "rtxremix.setting.sky.ambient", "rtxremix.setting.sky.ambient.tip",
                SkyRenderer.isSkyAmbientRectEnabled(),
                SkyRenderer::isSkyAmbientRectEnabled,
                v -> {
                    SkyRenderer.setSkyAmbientRectEnabled(v);
                    if (renderer.getSkyRenderer() != null) {
                        renderer.getSkyRenderer().forceRefresh();
                    }
                }));
        list.add(skyFloat(
                renderer,
                "sky.mieScatteringAnisotropy",
                "rtxremix.setting.sky.haloFocus",
                0.0,
                SkyRenderer.MAX_MIE_SCATTERING_ANISOTROPY,
                SkyRenderer::getMieScatteringAnisotropy,
                SkyRenderer::setMieScatteringAnisotropy));
        list.add(skyFloat(
                renderer,
                "sky.minimumCelestialWeatherVisibility",
                "rtxremix.setting.sky.minimumCelestialWeatherVisibility",
                0.0,
                1.0,
                SkyRenderer::getMinimumCelestialWeatherVisibility,
                SkyRenderer::setMinimumCelestialWeatherVisibility));

        // --- Sky appearance ---
        list.add(skyLookFloat(renderer, "sky.saturation", "rtxremix.setting.sky.saturation",
                SkyLook.MAX_SATURATION, SkyLook::getSaturation, SkyLook::setSaturation));
        list.add(skyLookFloat(renderer, "sky.sunsetWarmth", "rtxremix.setting.sky.sunsetWarmth",
                SkyLook.MAX_SUNSET_WARMTH, SkyLook::getSunsetWarmth, SkyLook::setSunsetWarmth));
        list.add(skyLookFloat(renderer, "sky.sunGlare", "rtxremix.setting.sky.sunGlare",
                SkyLook.MAX_SUN_GLARE, SkyLook::getSunGlare, SkyLook::setSunGlare));
        list.add(skyLookFloat(renderer, "sky.rainbow", "rtxremix.setting.sky.rainbow",
                SkyLook.MAX_RAINBOW, SkyLook::getRainbow, SkyLook::setRainbow));
        list.add(skyLookFloat(renderer, "sky.starBrightness", "rtxremix.setting.sky.starBrightness",
                SkyLook.MAX_STAR_BRIGHTNESS, SkyLook::getStarBrightness, SkyLook::setStarBrightness));
        list.add(floatSetting("sky.aerialHaze", SettingGroup.SKY_LOOK, "rtxremix.setting.sky.aerialHaze",
                0.0, SkyLook.MAX_AERIAL_HAZE, SkyLook::getAerialHaze, SkyLook::setAerialHaze));
        // Coverage regenerates the cirrus texture on a worker, so it applies once a drag settles.
        list.add(skyLookFloat(renderer, "sky.cirrusCoverage", "rtxremix.setting.sky.cirrusCoverage",
                1.0, SkyLook::getCirrusCoverage, SkyLook::setCirrusCoverage).deferSliderApply());
        list.add(skyLookFloat(renderer, "sky.cirrusBrightness", "rtxremix.setting.sky.cirrusBrightness",
                SkyLook.MAX_CIRRUS_BRIGHTNESS, SkyLook::getCirrusBrightness, SkyLook::setCirrusBrightness));
        list.add(RtxSetting.ofBool("sky.aurora", SettingGroup.SKY_LOOK,
                "rtxremix.setting.sky.aurora", "rtxremix.setting.sky.aurora.tip",
                SkyLook.isAuroraEnabled(), SkyLook::isAuroraEnabled, SkyLook::setAuroraEnabled));
        list.add(skyLookFloat(renderer, "sky.auroraIntensity", "rtxremix.setting.sky.auroraIntensity",
                SkyLook.MAX_AURORA_INTENSITY, SkyLook::getAuroraIntensity, SkyLook::setAuroraIntensity));
        list.add(RtxSetting.ofBool("sky.auroraEverywhere", SettingGroup.SKY_LOOK,
                "rtxremix.setting.sky.auroraEverywhere", "rtxremix.setting.sky.auroraEverywhere.tip",
                SkyLook.isAuroraEverywhere(), SkyLook::isAuroraEverywhere, SkyLook::setAuroraEverywhere));

        CloudRenderer clouds = renderer.getCloudRenderer();
        list.add(RtxSetting.ofInt(RtxOptionFactory.CLOUD_STYLE_ID, SettingGroup.CLOUDS,
                "rtxremix.setting.clouds.style",
                "rtxremix.setting.clouds.style.tip",
                0, CloudStyle.values().length - 1, CloudStyle.CLASSIC.ordinal(),
                () -> clouds.getCloudStyle().ordinal(),
                v -> clouds.setCloudStyle(CloudStyle.of(v))));
        // Coverage regenerates the mask and every cloud chunk, so it applies once a drag settles.
        list.add(relativeFloat("clouds.coverageScale", SettingGroup.CLOUDS,
                "rtxremix.setting.clouds.coverage", CloudRenderer.DEFAULT_COVERAGE, 1.0f,
                clouds::getCoverage, clouds::setCoverage)
                .deferSliderApply());
        list.add(RtxSetting.ofBool("clouds.secondLayer", SettingGroup.CLOUDS,
                "rtxremix.setting.clouds.secondLayer",
                "rtxremix.setting.clouds.secondLayer.tip",
                CloudRenderer.DEFAULT_SECOND_LAYER, clouds::isSecondLayer, clouds::setSecondLayer));
        list.add(floatSetting("clouds.speed", SettingGroup.CLOUDS,
                "rtxremix.setting.clouds.speed", 0.0, CloudRenderer.MAX_SPEED,
                clouds::getSpeed, clouds::setSpeed));
        list.add(floatSetting("clouds.unboundThickness", SettingGroup.CLOUDS,
                "rtxremix.setting.clouds.unboundThickness", 20.0, 1000.0,
                clouds::getUnboundThickness, clouds::setUnboundThickness));
        list.add(floatSetting("clouds.unboundDensity", SettingGroup.CLOUDS,
                "rtxremix.setting.clouds.unboundDensity", 0.1, 3.0,
                clouds::getUnboundDensity, clouds::setUnboundDensity));
        list.add(floatSetting("clouds.unboundBrightness", SettingGroup.CLOUDS,
                "rtxremix.setting.clouds.unboundBrightness", 0.2, 3.0,
                clouds::getUnboundBrightness, clouds::setUnboundBrightness));
        list.add(floatSetting("clouds.unboundDetail", SettingGroup.CLOUDS,
                "rtxremix.setting.clouds.unboundDetail", 0.0, 2.0,
                clouds::getUnboundDetail, clouds::setUnboundDetail));
        list.add(floatSetting("clouds.unboundRain", SettingGroup.CLOUDS,
                "rtxremix.setting.clouds.unboundRain", 0.0, 1.0,
                clouds::getUnboundRainInfluence, clouds::setUnboundRainInfluence));
        list.add(floatSetting("clouds.unboundShadow", SettingGroup.CLOUDS,
                "rtxremix.setting.clouds.unboundShadow", 0.0, 1.0,
                clouds::getUnboundShadow, clouds::setUnboundShadow));
        list.add(floatSetting(
                "clouds.vanillaEdgeSmoothness",
                SettingGroup.CLOUDS,
                "rtxremix.setting.clouds.vanillaEdgeSmoothness",
                0.0,
                1.99,
                renderer.getCloudRenderer()::getVanillaEdgeSmoothness,
                renderer.getCloudRenderer()::setVanillaEdgeSmoothness)
                .deferSliderApply());
        list.add(floatSetting(
                "clouds.size",
                SettingGroup.CLOUDS,
                "rtxremix.setting.clouds.size",
                0.1,
                2.0,
                renderer.getCloudRenderer()::getCloudSize,
                renderer.getCloudRenderer()::setCloudSize)
                .deferSliderApply());
        list.add(floatSetting(
                "clouds.height",
                SettingGroup.CLOUDS,
                "rtxremix.setting.clouds.height",
                -128.0,
                128.0,
                renderer.getCloudRenderer()::getCloudsHeight,
                renderer.getCloudRenderer()::setCloudsHeight));

        list.add(skyFloat(renderer, "sky.sunIntensity", "rtxremix.setting.sky.sunIntensity",
                0.0, 100.0,
                LightManager::getSunIntensity, LightManager::setSunIntensity));
        list.add(relativeFloat("sky.moonScale", SettingGroup.SKY,
                "rtxremix.setting.sky.moonIntensity",
                LightManager.DEFAULT_MOON_INTENSITY, LightManager.MAX_MOON_INTENSITY,
                LightManager::getMoonIntensity, v -> {
                    LightManager.setMoonIntensity(v);
                    if (renderer.getSkyRenderer() != null) {
                        renderer.getSkyRenderer().forceRefresh();
                    }
                }));
        list.add(skyFloat(renderer, "sky.dayAmbient", "rtxremix.setting.sky.dayAmbient",
                0.0, 5.0,
                SkyRenderer::getDayAmbientBoost, SkyRenderer::setDayAmbientBoost));
        list.add(skyFloat(renderer, "sky.nightAmbient", "rtxremix.setting.sky.nightAmbient",
                0.0, 10.0,
                SkyRenderer::getNightAmbientBoost, SkyRenderer::setNightAmbientBoost));

        for (BlockLightIntensityControls.Control control
                : BlockLightIntensityControls.controls()) {
            list.add(RtxSetting.ofFloat(
                    control.settingId(),
                    SettingGroup.BLOCK_LIGHTS,
                    control.labelKey(),
                    "rtxremix.setting.blockLights.multiplier.tip",
                    0.0,
                    BlockLightIntensityControls.MAX_MULTIPLIER,
                    BlockLightIntensityControls.DEFAULT_MULTIPLIER,
                    control::get,
                    control::set));
        }

        // --- Materials ---
        BlockTextureManager textures = renderer.getTextureManager();
        list.add(RtxSetting.ofFloat(
                RtxOptionFactory.TERRAIN_EMISSION_MULTIPLIER_ID,
                SettingGroup.MATERIALS,
                "rtxremix.setting.materials.terrainEmission",
                "rtxremix.setting.materials.terrainEmission.tip",
                0.0,
                BlockTextureManager.MAX_TERRAIN_EMISSION_MULTIPLIER,
                BlockTextureManager.DEFAULT_TERRAIN_EMISSION_MULTIPLIER,
                () -> (double) textures.getTerrainEmissionMultiplier(),
                value -> textures.setTerrainEmissionMultiplier(value.floatValue())));
        RemixAPI remix = renderer.getRemixApi();
        remixBool("materials.smoothReliefMaps", SettingGroup.MATERIALS,
                "rtxremix.setting.materials.smoothReliefMaps",
                "rtx.materials.smoothReliefMapFiltering",
                remix::getConfigVariableResolved, remix::setConfigVariable)
                .ifPresent(list::add);
        // Deferred: a new amount or reflection rebuilds every puddle.
        list.add(RtxSetting.ofFloat("materials.puddleAmount", SettingGroup.MATERIALS,
                "rtxremix.setting.materials.puddleAmount", "rtxremix.setting.materials.puddleAmount.tip",
                0.0, 1.0, Puddles.DEFAULT_AMOUNT,
                () -> (double) Puddles.getAmount(), v -> Puddles.setAmount(v.floatValue()))
                .deferSliderApply());
        list.add(RtxSetting.ofFloat("materials.puddleReflection", SettingGroup.MATERIALS,
                "rtxremix.setting.materials.puddleReflection", "rtxremix.setting.materials.puddleReflection.tip",
                0.0, 1.0, Puddles.DEFAULT_REFLECTION,
                () -> (double) Puddles.getReflection(), v -> Puddles.setReflection(v.floatValue()))
                .deferSliderApply());
        list.add(RtxSetting.ofBool("materials.puddlesWhenDry", SettingGroup.MATERIALS,
                "rtxremix.setting.materials.puddlesWhenDry", "rtxremix.setting.materials.puddlesWhenDry.tip",
                false, Puddles::isKeptWhenDry, Puddles::setKeptWhenDry));

        // --- Debug views: switches for looking into what the renderer does, never needed to play ---
        list.add(RtxSetting.ofBool("debug.puddleMask", SettingGroup.DEBUG_VIEWS,
                "rtxremix.setting.debug.puddleMask", "rtxremix.setting.debug.puddleMask.tip",
                false, Puddles::isMaskShown, Puddles::setMaskShown));
        list.add(RtxSetting.ofBool("debug.domeClouds", SettingGroup.DEBUG_VIEWS,
                "rtxremix.setting.debug.domeClouds", "rtxremix.setting.debug.domeClouds.tip",
                false, LensEffects::isDomeCloudsShown, LensEffects::setDomeCloudsShown));
        list.add(RtxSetting.ofBool("debug.sceneOriginOffset", SettingGroup.DEBUG_VIEWS,
                "rtxremix.setting.debug.sceneOriginOffset", "rtxremix.setting.debug.sceneOriginOffset.tip",
                false, SceneSpace::debugOffset, SceneSpace::setDebugOffset));

        // --- Tonemapping ---
        list.add(floatSetting("post.exposure", SettingGroup.TONEMAPPING,
                "rtxremix.setting.post.exposure", 0.0, 2.0,
                postProcessing::getExposure, postProcessing::setExposure));
        list.add(floatSetting("post.shadows", SettingGroup.TONEMAPPING,
                "rtxremix.setting.post.shadows", -5.0, 5.0,
                postProcessing::getShadowLevel, postProcessing::setShadowLevel));
        list.add(floatSetting("post.highlights", SettingGroup.TONEMAPPING,
                "rtxremix.setting.post.highlights", -5.0, 10.0,
                postProcessing::getHighlightsLevel, postProcessing::setHighlightsLevel));

        // --- Eye adaptation ---
        list.add(floatSetting("post.lowLightAdaptation", SettingGroup.EYE_ADAPTATION,
                "rtxremix.setting.post.lowLightAdaptation", -5.0, 5.0,
                postProcessing::getLowLightAdaptation, postProcessing::setLowLightAdaptation));
        list.add(floatSetting("post.highLightAdaptation", SettingGroup.EYE_ADAPTATION,
                "rtxremix.setting.post.highLightAdaptation", -5.0, 10.0,
                postProcessing::getHighLightAdaptation, postProcessing::setHighLightAdaptation));

        // --- Lens ---
        list.add(relativeFloat("lens.depthOfFieldScale", SettingGroup.LENS,
                "rtxremix.setting.lens.depthOfField", LensLook.DEFAULT_DEPTH_OF_FIELD,
                LensLook.MAX_DEPTH_OF_FIELD, LensLook::getDepthOfField, LensLook::setDepthOfField));
        list.add(relativeFloat("lens.flareScale", SettingGroup.LENS,
                "rtxremix.setting.lens.flare", LensLook.DEFAULT_FLARE,
                LensLook.MAX_FLARE, LensLook::getFlare, LensLook::setFlare));
        list.add(floatSetting("lens.caustics", SettingGroup.LENS,
                "rtxremix.setting.lens.caustics", 0.0, LensLook.MAX_CAUSTICS,
                LensLook::getCaustics, LensLook::setCaustics));

        return new RtxSettings(list);
    }

    static RtxSetting<Integer> unfocusedFramerate() {
        return RtxSetting.ofInt(RtxOptionFactory.UNFOCUSED_FRAMERATE_ID, SettingGroup.FRAMERATE,
                "rtxremix.setting.framerate.unfocused",
                "rtxremix.setting.framerate.unfocused.tip",
                UnfocusedFramerateLimit.MIN, UnfocusedFramerateLimit.UNLIMITED,
                UnfocusedFramerateLimit.DEFAULT,
                UnfocusedFramerateLimit::get, UnfocusedFramerateLimit::set)
                .withStep(UnfocusedFramerateLimit.STEP);
    }

    /**
     * Binds a boolean Remix option, with the value Remix resolved at startup as
     * its default. The setting then reports its own writes rather than reading
     * the option again, because Remix resolves a write at a later option
     * update and a read before that still returns the previous value.
     *
     * <p>Returns empty when the loaded runtime cannot resolve the option, for
     * example a runtime built before the option existed, so the menu leaves
     * the setting out instead of failing client startup.
     */
    static Optional<RtxSetting<Boolean>> remixBool(String id, SettingGroup group, String labelKey,
            String option, Function<String, String> configReader,
            BiConsumer<String, String> configWriter) {
        boolean[] value = new boolean[1];
        try {
            value[0] = Boolean.parseBoolean(configReader.apply(option));
        } catch (RemixException e) {
            LOGGER.warn("Remix option {} is unavailable in this runtime, so the {} setting is hidden",
                    option, id, e);
            return Optional.empty();
        }
        return Optional.of(RtxSetting.ofBool(id, group, labelKey, labelKey + ".tip", value[0],
                () -> value[0],
                v -> {
                    value[0] = v;
                    configWriter.accept(option, Boolean.toString(v));
                }));
    }

    private static RtxSetting<Double> skyFloat(RemixRenderer renderer, String id, String labelKey,
            double min, double max,
            Supplier<Float> getter, Consumer<Float> setter) {
        return RtxSetting.ofFloat(id, SettingGroup.SKY, labelKey, labelKey + ".tip",
                min, max, getter.get(),
                () -> (double) getter.get(),
                v -> {
                    setter.accept(v.floatValue());
                    if (renderer.getSkyRenderer() != null) {
                        renderer.getSkyRenderer().forceRefresh();
                    }
                });
    }

    private static RtxSetting<Double> skyLookFloat(RemixRenderer renderer, String id, String labelKey,
            double max, Supplier<Float> getter, Consumer<Float> setter) {
        return RtxSetting.ofFloat(id, SettingGroup.SKY_LOOK, labelKey, labelKey + ".tip",
                0.0, max, getter.get(),
                () -> (double) getter.get(),
                v -> {
                    setter.accept(v.floatValue());
                    if (renderer.getSkyRenderer() != null) {
                        renderer.getSkyRenderer().forceRefresh();
                    }
                });
    }

    /**
     * A strength shown as a multiple of its default, so the slider reads 1 at the default. Its id
     * must not be one the strength itself was saved under: a saved file would feed that value in
     * as a multiple.
     */
    private static RtxSetting<Double> relativeFloat(String id, SettingGroup group, String labelKey,
            float defaultValue, float maxValue, Supplier<Float> getter, Consumer<Float> setter) {
        return RtxSetting.ofFloat(id, group, labelKey, labelKey + ".tip", 0.0, maxValue / defaultValue, 1.0,
                () -> (double) (getter.get() / defaultValue), v -> setter.accept(v.floatValue() * defaultValue));
    }

    private static RtxSetting<Double> floatSetting(String id, SettingGroup group, String labelKey,
            double min, double max,
            Supplier<Float> getter, Consumer<Float> setter) {
        return RtxSetting.ofFloat(id, group, labelKey, labelKey + ".tip", min, max, getter.get(),
                () -> (double) getter.get(), v -> setter.accept(v.floatValue()));
    }

}
