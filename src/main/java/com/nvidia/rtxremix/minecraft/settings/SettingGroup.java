/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.settings;

/** Ordering of constants drives ordering of rows in the settings screen. */
public enum SettingGroup {
    RENDER_DISTANCE(SettingCategory.PERFORMANCE, "rtxremix.settings.group.renderDistance"),
    FRAMERATE(SettingCategory.PERFORMANCE, "rtxremix.settings.group.framerate"),
    SKY(SettingCategory.LIGHTING, "rtxremix.settings.group.sky"),
    SKY_LOOK(SettingCategory.LIGHTING, "rtxremix.settings.group.skyLook"),
    CLOUDS(SettingCategory.LIGHTING, "rtxremix.settings.group.clouds"),
    BLOCK_LIGHTS(SettingCategory.LIGHTING, "rtxremix.settings.group.blockLights"),
    TONEMAPPING(SettingCategory.IMAGE, "rtxremix.settings.group.tonemapping"),
    EYE_ADAPTATION(SettingCategory.IMAGE, "rtxremix.settings.group.eyeAdaptation"),
    LENS(SettingCategory.IMAGE, "rtxremix.settings.group.lens"),
    HDR(SettingCategory.IMAGE, "rtxremix.settings.group.hdr"),
    MATERIALS(SettingCategory.MATERIALS, "rtxremix.settings.group.materials"),
    DEBUG_VIEWS(SettingCategory.DEBUG, "rtxremix.settings.group.debugViews");

    private final SettingCategory category;
    private final String labelKey;

    SettingGroup(SettingCategory category, String labelKey) {
        this.category = category;
        this.labelKey = labelKey;
    }

    SettingCategory category() {
        return category;
    }

    String labelKey() {
        return labelKey;
    }
}
