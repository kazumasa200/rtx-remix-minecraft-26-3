// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

// What the mod hands the patched runtime shaders, at descriptor set 4 (runtime-patch/layer/channel.h). The
// block is the one definition of the frame's layout: the patch build reads its members' offsets from the
// compiled library into frame_layout.txt, RuntimeFrame writes them, and RuntimeFrameTest holds the two
// together. The layer copies the bytes without looking inside them; a frame of zeros draws nothing.
#ifndef RRP_FRAME_GLSL
#define RRP_FRAME_GLSL

#ifndef CLOUD_MAX_SHAPES
#define CLOUD_MAX_SHAPES 1
#endif

layout(set = 4, binding = 0, std140) uniform RrpFrame {
    uint Enabled;            // 1 while the Unbound clouds are drawn by the runtime this frame
    uint FrameIndex;         // the mod's frame counter, which turns the march's jitter
    uint MarchCache;         // 1 when the layer's pass refreshes the sky cache this frame
    uint Parity;             // the checkerboard half of the cache a march refreshes
    vec2 FrameCameraXZ;      // the mod's camera in the clouds' frame, where the layer's pass marches from
    float FrameCameraHeight;
    float HistoryWeight;     // the sky cache's running-average weight; 1 starts it over
    vec2 SceneToCloud;       // the clouds' frame xz less scene-space xz (scene y is world y)
    float ShadowStrength;    // the Cloud Shadows setting, 0 to 1
    float LightConeCos;      // a shadow ray within this cone of LightDirection is the clouds' light's
    vec3 LightDirection;
    float DistantRayMin;     // and at least this long: a distant light's
    vec3 LightIrradiance;
    float CloudBase;
    vec3 AmbientTop;
    float CloudThickness;
    vec3 AmbientBottom;
    float Density;
    vec3 LayerCoverage;
    float FeatureScale;
    float Detail;
    float Brightness;
    float HazeDistance;
    float CirrusAngle;
    float CirrusStrength;
    float CirrusMinElevation;
    float ShadowTelltale;    // 1 for the Debug page's Cloud Shadow Test: the clouds' light's rays carry nothing
    float Reserved1;
    vec4 Flash;
    int ShapeCount;
    vec4 ShapeBounds[CLOUD_MAX_SHAPES];
    vec4 ShapeForm[CLOUD_MAX_SHAPES];
    vec4 ShapeSize[CLOUD_MAX_SHAPES];
};

layout(set = 4, binding = 1) uniform sampler3D Noise;           // repeat, linear, no mips, as the GL clouds'
layout(set = 4, binding = 2) uniform sampler2D Cirrus;          // clamp, linear, no mips
layout(set = 4, binding = 3) uniform sampler2D RrpSkyCache;     // the layer's pass: the sky of clouds
layout(set = 4, binding = 4) uniform sampler2D RrpCloudShadow;  // the layer's pass: depth toward the light

// The sky cache is an octahedral map of the whole sphere of directions from the frame's camera, up (+y) at the
// centre: the upper hemisphere is the inner diamond, so the folds lie below the horizon, where clouds are seen
// only from above.
vec2 rrpOctEncode(vec3 d) {
    d /= abs(d.x) + abs(d.y) + abs(d.z);
    vec2 p = d.xz;
    if (d.y < 0.0) {
        p = (1.0 - abs(p.yx)) * vec2(p.x >= 0.0 ? 1.0 : -1.0, p.y >= 0.0 ? 1.0 : -1.0);
    }
    return p * 0.5 + 0.5;
}

vec3 rrpOctDecode(vec2 uv) {
    vec2 p = uv * 2.0 - 1.0;
    vec3 d = vec3(p.x, 1.0 - abs(p.x) - abs(p.y), p.y);
    if (d.y < 0.0) {
        d.xz = (1.0 - abs(d.zx)) * vec2(d.x >= 0.0 ? 1.0 : -1.0, d.z >= 0.0 ? 1.0 : -1.0);
    }
    return normalize(d);
}

// Blocks the cloud shadow map spans, centred on the frame camera's line toward the light.
#define RRP_SHADOW_SPAN 32768.0
#define RRP_SHADOW_SIZE 2048.0

// The shadow map is indexed by where a point's line toward the light crosses the clouds' base: every point on
// that line below a layer sees the same cloud of it toward the light, which is what shadowOpticalDepths reads.
// The centre follows the frame camera's line, snapped to whole texels so the map does not crawl.
float rrpLightSlope() {
    return max(LightDirection.y, 0.1);
}

vec2 rrpShadowCentre() {
    vec2 q = FrameCameraXZ + LightDirection.xz * ((CloudBase - FrameCameraHeight) / rrpLightSlope());
    float texel = RRP_SHADOW_SPAN / RRP_SHADOW_SIZE;
    return floor(q / texel) * texel;
}

vec2 rrpShadowUv(vec2 cloudXZ, float height) {
    vec2 q = cloudXZ + LightDirection.xz * ((CloudBase - height) / rrpLightSlope());
    return (q - rrpShadowCentre()) / RRP_SHADOW_SPAN + 0.5;
}

#endif
