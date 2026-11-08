// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

// The Unbound clouds' density field, lighting, march and shadow, shared by the march that
// draws them into the sky (volumetric_clouds.comp) and the lens (LensEffects), which marches
// them in front of the land and shades the land beneath them, so both show the same clouds
// lit the same way. The includer defines PI and
// declares the 3D noise as Noise, the layer inputs CloudBase, CloudThickness, LayerCoverage
// (the small cumulus, the large cumulus and the altostratus, as CloudWeather has them),
// Density, FeatureScale and Detail, the light LightDirection, LightIrradiance, AmbientTop and
// AmbientBottom, the camera in the clouds' frame as CameraXZ and CameraHeight, the look as
// Brightness and HazeDistance, the cirrus layer as Cirrus, CirrusAngle, CirrusStrength and
// CIRRUS_MIN_ELEVATION_DEGREES, and a lightning flash as Flash (LightningFlash). It may define CLOUD_MIN_STEPS, CLOUD_MAX_STEPS,
// CLOUD_ALTO_STEPS and CLOUD_SHAPE_STEPS first, and CLOUD_MAX_SHAPES as CloudShapes sizes it.
// The shaped clouds' own uniforms are declared here.

#ifndef CLOUD_MIN_STEPS
#define CLOUD_MIN_STEPS 28
#endif
#ifndef CLOUD_MAX_STEPS
#define CLOUD_MAX_STEPS 56
#endif
#ifndef CLOUD_SHAPE_STEPS
#define CLOUD_SHAPE_STEPS 96
#endif
#ifndef CLOUD_MAX_SHAPES
#define CLOUD_MAX_SHAPES 1
#endif

// Shaped clouds (CloudShapes), each inside a bounding sphere of ShapeBounds (centre in the
// clouds' frame, radius). ShapeForm: x the kind (1 a creeper's head), w the height of its
// base. ShapeSize: x its height, y a head's half size, z its facing in radians, w the shape's
// strength, 0 to 1, as it forms and fades. An includer that keeps them in a block of its own
// (Vulkan GLSL has no loose uniforms) defines CLOUD_FIELD_EXTERNAL_SHAPES.
#ifndef CLOUD_FIELD_EXTERNAL_SHAPES
uniform int ShapeCount;
uniform vec4 ShapeBounds[CLOUD_MAX_SHAPES];
uniform vec4 ShapeForm[CLOUD_MAX_SHAPES];
uniform vec4 ShapeSize[CLOUD_MAX_SHAPES];
#endif

// Extinction per block at full density.
const float EXTINCTION = 0.08;
// The stack, from CloudBase and CloudThickness: small cumulus half as tall as the large,
// the large a little above it, and the altostratus about 1.7 km higher.
const float SMALL_SHARE = 0.5;
const float LARGE_LIFT = 260.0;
const float ALTO_LIFT = 1700.0;
const float ALTO_THICKNESS = 40.0;
const float SMALL_DENSITY = 1.0;
const float LARGE_DENSITY = 1.0;
const float ALTO_DENSITY = 0.12;
// Blocks per noise tile at feature scale 1, for each layer's weather cells and clumps. Each
// divides the frame's wrap, VolumetricClouds.WRAP_PERIOD (180000), or the sky jumps at the wrap.
const float SMALL_BROAD_PERIOD = 5000.0;
const float SMALL_FINE_PERIOD = 900.0;
const float LARGE_BROAD_PERIOD = 10000.0;
const float LARGE_FINE_PERIOD = 180000.0 / 69.0;
const float ALTO_BROAD_PERIOD = 60000.0;
const float ALTO_FINE_PERIOD = 7500.0;
const float ALTO_WARP = 3000.0;
const float BROAD_SHARE = 0.45;
const float DETAIL_PERIOD = 200.0;
const float BASE_LOW = 0.75;
const float BASE_HIGH = 0.9;
// Blocks over which a cumulus base rounds off.
const float BASE_ROUNDING = 20.0;
// How far the detail leans downwind at the top, per block of layer thickness.
const vec2 SHEAR = vec2(0.96, 0.28) * 0.2;
const float DETAIL_EROSION = 0.9;
// Height fraction over which erosion grows from none at a cumulus base to full; below it the
// thin rim of a base would fray into holes that show as a speckled patch from below.
const float BASE_EROSION_HEIGHT = 0.3;
const float EDGE_GAIN = 3.0;
// The least of the field's span over which a layer's cover climbs from its edge to full. A span
// as small as a low coverage stood sparse clouds' edges up as walls under flat tops.
const float COVER_EDGE_SPAN = 0.25;
// The share of the noise tile a cumulus layer's clumps rise through from base to top: about a
// cell of the Perlin-Worley, so a clump's outline changes on the way up.
const float CLUMP_RISE = 0.35;
// How far the detail's Perlin-Worley puffs push a cumulus surface out and in, in cover, and
// that noise's middle and the half-width of its middle eight tenths, which map onto -1 to 1.
const float PUFF_DEPTH = 0.35;
const float PUFF_CENTRE = 0.74;
const float PUFF_SPREAD = 0.11;

// --- Layered clouds ---------------------------------------------------------------------
// Three layers stacked as weather builds them: small cumulus just above the base, large
// cumulus higher up, and a thin altostratus sheet far above both. Each cumulus layer takes
// its cover from flat noise, grows taller where that cover is strongest, and has its edges
// eroded by 3D detail sheared downwind with height. A layer above shades the ones below it,
// both from the sun and from the sky.

const int LAYER_SMALL = 0;
const int LAYER_LARGE = 1;
const int LAYER_ALTO = 2;

float remap(float value, float low, float high, float newLow, float newHigh) {
    return newLow + (value - low) / (high - low) * (newHigh - newLow);
}

float layerBase(int layer) {
    return layer == LAYER_SMALL ? CloudBase : layer == LAYER_LARGE ? CloudBase + LARGE_LIFT : CloudBase + ALTO_LIFT;
}

float layerThickness(int layer) {
    return layer == LAYER_SMALL ? CloudThickness * SMALL_SHARE : layer == LAYER_LARGE ? CloudThickness : ALTO_THICKNESS;
}

float layerCoverage(int layer) {
    return clamp(layer == LAYER_SMALL ? LayerCoverage.x : layer == LAYER_LARGE ? LayerCoverage.y : LayerCoverage.z,
        0.0, 1.0);
}

// The base shape noise, 0 to 1, at a point of the noise tile.
float shapeNoise(vec3 uvw) {
    vec4 n = texture(Noise, uvw);
    float lowFbm = n.g * 0.625 + n.b * 0.25 + n.a * 0.125;
    // The Perlin-Worley remap lands between about 0.7 and 0.95; stretching its middle over
    // 0 to 1 makes coverage roughly the share of sky the clouds fill.
    return clamp(remap(remap(n.r, lowFbm - 1.0, 1.0, 0.0, 1.0), BASE_LOW, BASE_HIGH, 0.0, 1.0), 0.0, 1.0);
}

// The base shape noise read flat at one height of the noise tile.
float flatShape(vec2 xz, float period, float slice) {
    return shapeNoise(vec3(xz.x / period, slice, xz.y / period));
}

// The clumps read through the layer's height, h from base to top, as well: a clump's outline
// changes as it rises, so its sides bulge and its top lumps. Read flat, every cloud was its
// outline stood up, sheer-sided and flat-topped like a slab of castella.
float clumpShape(vec2 xz, float h, float period, float slice) {
    return shapeNoise(vec3(xz.x / period, slice + h * CLUMP_RISE, xz.y / period));
}

// How strongly a layer is covered at a point, h of the way up it, 0 to 1: broad weather cells
// and the clumps inside them, thresholded by the layer's coverage.
float layerCover(int layer, vec2 xz, float h) {
    float scale = FeatureScale;
    float broad, fine;
    if (layer == LAYER_SMALL) {
        broad = flatShape(xz, SMALL_BROAD_PERIOD * scale, 0.19);
        fine = clumpShape(xz, h, SMALL_FINE_PERIOD * scale, 0.53);
    } else if (layer == LAYER_LARGE) {
        broad = flatShape(xz.yx, LARGE_BROAD_PERIOD * scale, 0.71);
        fine = clumpShape(xz.yx, h, LARGE_FINE_PERIOD * scale, 0.37);
    } else {
        broad = flatShape(xz, ALTO_BROAD_PERIOD * scale, 0.83);
        // Warped by the broad field, so the sheet tears into long streaks and rifts.
        fine = flatShape(xz + (broad - 0.5) * ALTO_WARP, ALTO_FINE_PERIOD * scale, 0.07);
    }
    float field = mix(fine, broad, BROAD_SHARE);
    float coverage = layerCoverage(layer);
    return clamp((field - (1.0 - coverage)) / max(coverage, COVER_EDGE_SPAN), 0.0, 1.0);
}

// The cover a cumulus dome is built on, eased in from the edge: the dome rises as the square
// root of the cover, so cover climbing straight from zero stood the cloud's side up as a wall;
// eased, the side bulges out and rounds into the base.
float domeCover(float cover) {
    return cover * cover * (3.0 - 2.0 * cover);
}

// Density, 0 to 1 before the layer's density scale, at height fraction h through a cumulus layer.
float cumulusShape(int layer, vec3 world, float h, bool detailed) {
    if (h <= 0.0 || h >= 1.0) {
        return 0.0;
    }
    float cover = domeCover(layerCover(layer, world.xz, h));
    if (cover <= 0.0) {
        return 0.0;
    }
    float thickness = layerThickness(layer);
    // A rounded base a few blocks deep, and a dome above: only well-covered spots reach the
    // top, so strong cells tower while weak ones stay as low, flat puffs.
    float bottom = 1.0 - pow(1.0 - clamp(h * thickness / BASE_ROUNDING, 0.0, 1.0), 4.0);
    float top = 1.0 - h * h;
    float body = cover + bottom * top - 1.0;
    // Above the flat base the surface puffs out and in.
    float puffing = PUFF_DEPTH * Detail * smoothstep(0.0, BASE_EROSION_HEIGHT, h);
    float shape = clamp(body, 0.0, 1.0);
    if (detailed && body > -puffing && Detail > 0.0) {
        // Detail drifts downwind faster than the body, more so higher up, so tops shear over.
        vec3 sheared = world;
        sheared.xz += SHEAR * pow(h, 1.5) * thickness;
        vec4 n = texture(Noise, sheared / (DETAIL_PERIOD * FeatureScale));
        // The Perlin-Worley's round puffs swell the surface into cauliflower, as Perlin-Worley
        // swells the clumps; the body alone was smooth as a sculpted slab.
        float puff = clamp((n.r - PUFF_CENTRE) / PUFF_SPREAD, -1.0, 1.0);
        // Pushed out only half as far as in: pushed out alike, a thin edge grew into a scatter of
        // crumbs and the sky clouded over more than the coverage asks.
        shape = clamp(body + puff * puffing * (puff > 0.0 ? 0.5 : 1.0), 0.0, 1.0);
        float detail = n.g * 0.625 + n.b * 0.25 + n.a * 0.125;
        // Wispy low on the sides, billowing higher up.
        float erosion = mix(1.0 - detail, detail, clamp(h * 4.0, 0.0, 1.0));
        // Only the thin edges erode; the dense heart of a cloud keeps its body, and the base,
        // where the air reaches condensation all at once, stays flat rather than perforated.
        erosion *= smoothstep(0.0, BASE_EROSION_HEIGHT, h);
        shape = clamp(shape - erosion * erosion * DETAIL_EROSION * Detail * (1.0 - shape), 0.0, 1.0);
    }
    return clamp(shape * EDGE_GAIN, 0.0, 1.0);
}

float layerDensity(int layer, vec3 world, bool detailed) {
    float h = (world.y - layerBase(layer)) / layerThickness(layer);
    if (layer == LAYER_ALTO) {
        if (h <= 0.0 || h >= 1.0) {
            return 0.0;
        }
        float cover = layerCover(LAYER_ALTO, world.xz, h);
        return cover * cover * ALTO_DENSITY * Density;
    }
    float density = layer == LAYER_SMALL ? SMALL_DENSITY : LARGE_DENSITY;
    return cumulusShape(layer, world, h, detailed) * density * Density;
}

// --- Shaped clouds ----------------------------------------------------------------------
// Figures CloudShapes places. As in Cygames' arbitrary-shape clouds (GCC 2017), a shape only
// says where cloud may stand; its surface is eroded at the edges by the field's own noise, as
// the cumulus are, so a shape keeps the grain of the clouds around it.

// The most cloud a shape puts between a point and the sky, and how high the ground's light
// reaches up it, in blocks.
const float SHAPE_LIT_THICKNESS = 300.0;
// The share of the sky a shape's side cannot see, in UPPER_SKY_OCCLUSION's terms: the shape
// itself hides half of it. Its top sees all of it.
const float SHAPE_FLANK_SHADE = 0.83;
// A creeper's face, eight pixels to a row with the top row first; the set pixels are its
// eyes and mouth.
const int CREEPER_FACE[8] = int[](0x00, 0x00, 0x66, 0x66, 0x18, 0x3C, 0x3C, 0x24);

bool creeperPixel(int column, int row) {
    return column >= 0 && column < 8 && row >= 0 && row < 8 && ((CREEPER_FACE[row] >> (7 - column)) & 1) == 1;
}

// How far inside a creeper's head a point is, in blocks, negative outside: a cube whose face's
// dark pixels are holes through it, so the sky shows through its eyes and mouth.
float creeperInside(int shape, vec3 world) {
    vec3 offset = world - ShapeBounds[shape].xyz;
    float c = cos(ShapeSize[shape].z);
    float s = sin(ShapeSize[shape].z);
    vec3 local = vec3(c * offset.x - s * offset.z, offset.y, s * offset.x + c * offset.z);
    float size = ShapeSize[shape].y;
    vec3 q = abs(local) - vec3(size);
    float inside = -(length(max(q, 0.0)) + min(max(q.x, max(q.y, q.z)), 0.0));
    float pixel = size * 0.25;
    vec2 face = vec2(local.x + size, size - local.y) / pixel;
    int column = int(floor(face.x));
    int row = int(floor(face.y));
    if (creeperPixel(column, row)) {
        // To the hole's edge, counting only the sides it does not share with another hole.
        vec2 f = fract(face);
        float hole = 1.0e9;
        if (!creeperPixel(column - 1, row)) hole = min(hole, f.x);
        if (!creeperPixel(column + 1, row)) hole = min(hole, 1.0 - f.x);
        if (!creeperPixel(column, row - 1)) hole = min(hole, f.y);
        if (!creeperPixel(column, row + 1)) hole = min(hole, 1.0 - f.y);
        inside = min(inside, -hole * pixel);
    }
    return inside;
}

// How far inside a shape a point is, in blocks, before the noise erodes it.
float shapeInside(int shape, vec3 world) {
    return creeperInside(shape, world);
}

float shapeDensity(int shape, vec3 world, bool detailed) {
    vec4 bounds = ShapeBounds[shape];
    vec3 offset = world - bounds.xyz;
    if (dot(offset, offset) >= bounds.w * bounds.w) {
        return 0.0;
    }
    float height = ShapeSize[shape].x;
    float h = clamp((world.y - ShapeForm[shape].w) / height, 0.0, 1.0);
    float body = clamp(shapeInside(shape, world) / (ShapeSize[shape].y * 0.12), 0.0, 1.0);
    if (detailed && body > 0.0 && Detail > 0.0) {
        vec3 sheared = world;
        sheared.xz += SHEAR * pow(h, 1.5) * height;
        vec4 n = texture(Noise, sheared / (DETAIL_PERIOD * FeatureScale));
        float detail = n.g * 0.625 + n.b * 0.25 + n.a * 0.125;
        float erosion = mix(1.0 - detail, detail, clamp(h * 4.0, 0.0, 1.0)) * smoothstep(0.0, BASE_EROSION_HEIGHT, h);
        // A figure frays half as much as a cloud, so it stays recognisable.
        body = clamp(body - erosion * erosion * DETAIL_EROSION * Detail * 0.5 * (1.0 - body), 0.0, 1.0);
    }
    return clamp(body * EDGE_GAIN, 0.0, 1.0) * ShapeSize[shape].w * LARGE_DENSITY * Density;
}

// Light samples toward the sun, each twice as far as the one before so they cross the whole
// layer (Photon): three short steps saw only the first 30% of it, so every point deeper in got
// the same light. The first step, in blocks, for a 200-block layer, scaled with its thickness.
const int LIGHT_STEPS = 4;
const float LIGHT_STEP = 20.0;
const int SHAPE_LIGHT_STEPS = 7;
const float SHAPE_LIGHT_STEP = 8.0;
const int ALTO_LIGHT_STEPS = 2;
const float ALTO_LIGHT_STEP = 200.0;
const float FORWARD_G = 0.8;
const float MIDDLE_G = 0.45;
const float WIDE_G = 0.15;
// Light back toward a viewer with the sun behind, close to Bliss's 2 HG(-0.25).
const float BACK_G = -0.25;
const float BACK_WEIGHT = 1.5;
const float POWDER_RATE = 4.0;
// Light scattered many times inside a cloud thins as one over its optical depth, as a
// diffusing slab's transmission does, 1 / (1 + 0.75 (1 - g) tau) with g 0.85 (Kokhanovsky
// 2004), not exponentially: an exponential left the bases of thick cumulus black.
const float MULTIPLE_SCATTERING_GAIN = 2.0;
const float DIFFUSION_PER_DEPTH = 0.1125;
const float AMBIENT_GAIN = 0.65;
// Share of the sky a fully covered large cumulus layer hides from the small cumulus under it.
const float UPPER_SKY_OCCLUSION = 0.6;
// Blocks of cloud a sunbeam crosses in a fully covered layer above.
const float LARGE_SHADOW_DEPTH = 60.0;
const float ALTO_SHADOW_DEPTH = 30.0;

float henyeyGreenstein(float cosTheta, float g) {
    float g2 = g * g;
    return (1.0 - g2) / (4.0 * PI * pow(1.0 + g2 - 2.0 * g * cosTheta, 1.5));
}

// The share of a higher layer's cover that lies between a point and the sun or the sky:
// the layer is read where the line from the point reaches its middle.
float coverAbove(int layer, vec3 world, vec3 toward) {
    if (toward.y <= 0.01) {
        return 0.0;
    }
    float middle = layerBase(layer) + layerThickness(layer) * 0.4;
    if (world.y >= middle) {
        return 0.0;
    }
    vec2 xz = world.xz + toward.xz * ((middle - world.y) / toward.y);
    float cover = layerCover(layer, xz, 0.4);
    return layer == LAYER_ALTO ? cover * cover * ALTO_DENSITY / LARGE_DENSITY : cover;
}

// Optical depth toward the light: steps through the layer itself, plus the layers above it
// read once each.
float lightOpticalDepth(int layer, vec3 world, float jitter) {
    float depth = 0.0;
    bool alto = layer == LAYER_ALTO;
    float stepLength = alto ? ALTO_LIGHT_STEP : LIGHT_STEP * layerThickness(layer) / 200.0;
    float travelled = 0.0;
    for (int i = 0; i < (alto ? ALTO_LIGHT_STEPS : LIGHT_STEPS); i++) {
        vec3 position = world + LightDirection * (travelled + stepLength * jitter);
        depth += layerDensity(layer, position, false) * stepLength;
        travelled += stepLength;
        if (!alto) {
            stepLength *= 2.0;
        }
    }
    float shade = 0.0;
    if (layer == LAYER_SMALL) {
        shade += coverAbove(LAYER_LARGE, world, LightDirection) * LARGE_DENSITY * LARGE_SHADOW_DEPTH;
    }
    if (layer != LAYER_ALTO) {
        // A sheet seen edge on toward a low sun shades far more than one overhead.
        shade += coverAbove(LAYER_ALTO, world, LightDirection) * LARGE_DENSITY * ALTO_SHADOW_DEPTH
            * (1.0 - abs(LightDirection.y));
    }
    return (depth + shade * Density) * EXTINCTION;
}

// The cloud a shape puts between a point and the light, its density summed over the chord the
// line toward the light cuts through its bounds; the detail is left out, as for the layers.
float shapeShadowDepth(int shape, vec3 world) {
    vec4 bounds = ShapeBounds[shape];
    vec3 offset = world - bounds.xyz;
    float b = dot(offset, LightDirection);
    float discriminant = b * b - dot(offset, offset) + bounds.w * bounds.w;
    if (discriminant <= 0.0) {
        return 0.0;
    }
    float root = sqrt(discriminant);
    float start = max(-b - root, 0.0);
    float end = -b + root;
    if (end <= start) {
        return 0.0;
    }
    float stepLength = (end - start) / 6.0;
    float depth = 0.0;
    for (int i = 0; i < 6; i++) {
        depth += shapeDensity(shape, world + LightDirection * (start + stepLength * (float(i) + 0.5)), false);
    }
    return depth * stepLength;
}

// Optical depth toward the light from a point in a shape: steps through the shape, each twice
// as long as the last, and the altostratus above read once, as it shades the cumulus.
float shapeLightDepth(int shape, vec3 world, float jitter) {
    float depth = 0.0;
    // From a few blocks, so the shape's own edges shade it: a first step a sixteenth of the
    // shape long crossed the terminator and lit every side alike.
    float stepLength = SHAPE_LIGHT_STEP;
    float travelled = 0.0;
    for (int i = 0; i < SHAPE_LIGHT_STEPS; i++) {
        depth += shapeDensity(shape, world + LightDirection * (travelled + stepLength * jitter), false) * stepLength;
        travelled += stepLength;
        stepLength *= 2.0;
    }
    float shade = coverAbove(LAYER_ALTO, world, LightDirection) * LARGE_DENSITY * ALTO_SHADOW_DEPTH
        * (1.0 - abs(LightDirection.y));
    return (depth + shade * Density) * EXTINCTION;
}

// Cloud straight up through a cumulus dome, per block of the layer's thickness: the density,
// EDGE_GAIN times the dome cover less the height fraction squared and full once that passes
// 1, summed over the height. Detail erosion is left out.
float domeColumn(float cover) {
    float full = sqrt(max(cover - 1.0 / EDGE_GAIN, 0.0));
    float edge = sqrt(cover);
    return full + EDGE_GAIN * (cover * (edge - full) - (edge * edge * edge - full * full * full) / 3.0);
}

// The share of the light the clouds above a point let through toward it, each layer read once
// where the line toward the light crosses its middle: a cumulus layer by the column of its
// dome there, so a shadow has its cloud's outline, and the altostratus by its sheet crossed
// on the slant. shadowOpticalDepths gives the optical depth of each part: the small cumulus,
// the large cumulus with the shaped clouds, and the altostratus.
vec3 shadowOpticalDepths(vec3 world) {
    float small = domeColumn(domeCover(coverAbove(LAYER_SMALL, world, LightDirection)))
        * layerThickness(LAYER_SMALL) * SMALL_DENSITY;
    float large = domeColumn(domeCover(coverAbove(LAYER_LARGE, world, LightDirection)))
        * layerThickness(LAYER_LARGE) * LARGE_DENSITY;
    float alto = coverAbove(LAYER_ALTO, world, LightDirection) * LARGE_DENSITY * ALTO_THICKNESS
        / max(LightDirection.y, 0.1);
    float shapes = 0.0;
    for (int shape = 0; shape < CLOUD_MAX_SHAPES; shape++) {
        if (shape >= ShapeCount) {
            break;
        }
        shapes += shapeShadowDepth(shape, world);
    }
    return vec3(small * Density, large * Density + shapes, alto * Density) * EXTINCTION;
}

float shadowTransmittance(vec3 world) {
    vec3 depth = shadowOpticalDepths(world);
    return exp(-(depth.x + depth.y + depth.z));
}

// Thin edges scatter forward in a narrow lobe, the silver lining against the sun, while the
// dense interior, where light has scattered many times, spreads it widely; a little comes
// back toward a viewer with the sun behind. The lobes depend only on the ray, so a march
// evaluates them once.
struct CloudPhase {
    float narrow;
    float middle;
    float wide;
    float back;
};

CloudPhase cloudPhase(float cosTheta) {
    return CloudPhase(henyeyGreenstein(cosTheta, FORWARD_G), henyeyGreenstein(cosTheta, MIDDLE_G),
        henyeyGreenstein(cosTheta, WIDE_G), henyeyGreenstein(cosTheta, BACK_G));
}

// The light a unit of density scatters toward the viewer at a point h of the way up its cloud,
// given the optical depth toward the light, the extinction through the cloud's height, the
// share of the sky a cloud above hides from it, and how far the ground's light reaches it: from
// below, a point high up a shape sees the sky about the horizon rather than the ground.
vec3 scatteredLight(float depth, float density, float extinction, float h, float overhead, float ground,
                    CloudPhase phase) {
    h = clamp(h, 0.0, 1.0);
    float powder = 1.0 - exp(-POWDER_RATE * density);
    float lobes = mix(phase.narrow, mix(phase.middle, phase.wide, powder), powder) + phase.back * powder * BACK_WEIGHT;
    // Light that has scattered inside reaches deeper than the direct beam, and leaves nearly
    // evenly in every direction.
    vec3 sun = LightIrradiance * (exp(-depth) * lobes
        + MULTIPLE_SCATTERING_GAIN / (4.0 * PI) / (1.0 + DIFFUSION_PER_DEPTH * depth));
    // The sky lights a point through the cloud above it, and the ground's light through the
    // cloud below it, so a base takes the ground's light unblocked (Photon's split): dimming
    // both by the depth into the cloud crushed the bases to black. The cloud around the point
    // stands in for the column above and below it.
    vec3 above = AmbientTop * (1.0 - overhead * UPPER_SKY_OCCLUSION)
        / (1.0 + DIFFUSION_PER_DEPTH * extinction * (1.0 - h));
    vec3 below = mix(AmbientTop, AmbientBottom, ground) * exp(-extinction * h);
    return sun + mix(below, above, h) * AMBIENT_GAIN;
}

// A lightning flash's colour, and the blocks over which its light, scattered many times inside
// the cloud, falls to 1/e: the cloud around the bolt glows and the deck beyond stays dark.
const vec3 FLASH_COLOUR = vec3(0.75, 0.82, 1.0);
const float FLASH_REACH = 60.0;

// The light a lightning flash gives a point of cloud: Flash holds where in the clouds' frame the
// bolt reaches into them and, in w, the irradiance a cloud right at it takes, zero with none.
vec3 flashLight(vec3 world) {
    if (Flash.w <= 0.0) {
        return vec3(0.0);
    }
    return FLASH_COLOUR * Flash.w * exp(-distance(world, Flash.xyz) / FLASH_REACH) / (4.0 * PI);
}

vec3 cloudSource(int layer, vec3 world, float density, float h, CloudPhase phase, float jitter) {
    float overhead = layer == LAYER_SMALL ? coverAbove(LAYER_LARGE, world, vec3(0.0, 1.0, 0.0)) : 0.0;
    return scatteredLight(lightOpticalDepth(layer, world, jitter), density,
        density * EXTINCTION * layerThickness(layer), h, overhead, 1.0, phase) + flashLight(world);
}

// A small planet, so the layers curve down to the horizon within the march distance.
const float PLANET_RADIUS = 1.0e6;
const float MAX_DISTANCE = 30000.0;
const float MAX_SPAN = 9000.0;
#ifndef CLOUD_ALTO_STEPS
#define CLOUD_ALTO_STEPS 2
#endif

// Distances along a ray to where it reaches the given altitude. The ground curves away at
// t^2 (1 - dirY^2) / 2R, which is the planet to well within the march distance.
bool altitudeRoots(float altitude, float dirY, out float near, out float far) {
    float k = (1.0 - dirY * dirY) / (2.0 * PLANET_RADIUS);
    float c = CameraHeight - altitude;
    if (k < 1.0e-12) {
        if (abs(dirY) < 1.0e-6) {
            return false;
        }
        near = far = -c / dirY;
        return true;
    }
    float discriminant = dirY * dirY - 4.0 * k * c;
    if (discriminant < 0.0) {
        return false;
    }
    float root = sqrt(discriminant);
    float q = -0.5 * (dirY + (dirY >= 0.0 ? root : -root));
    float a = q / k;
    float b = q != 0.0 ? c / q : a;
    near = min(a, b);
    far = max(a, b);
    return true;
}

bool layerSpan(float base, float top, float dirY, out float start, out float end) {
    float baseNear, baseFar, topNear, topFar;
    bool hitBase = altitudeRoots(base, dirY, baseNear, baseFar);
    bool hitTop = altitudeRoots(top, dirY, topNear, topFar);
    if (CameraHeight < base) {
        if (!hitBase || !hitTop) {
            return false;
        }
        start = baseFar;
        end = topFar;
    } else if (CameraHeight < top) {
        start = 0.0;
        end = hitBase && baseNear > 0.0 ? baseNear : topFar;
    } else {
        if (!hitTop || topFar <= 0.0) {
            return false;
        }
        start = max(topNear, 0.0);
        end = hitBase && baseNear > 0.0 ? baseNear : topFar;
    }
    return end > start && start < MAX_DISTANCE;
}

// Premultiplied radiance and opacity of one layer along a direction out to limit blocks, and
// where it begins.
vec4 marchLayer(int layer, vec3 direction, float jitter, float limit, out float entry) {
    entry = MAX_DISTANCE;
    float base = layerBase(layer);
    float thickness = layerThickness(layer);
    float start, end;
    if (!layerSpan(base, base + thickness, direction.y, start, end)) {
        return vec4(0.0);
    }
    end = min(end, min(start + MAX_SPAN, min(limit, MAX_DISTANCE)));
    if (end <= start) {
        return vec4(0.0);
    }
    int steps = layer == LAYER_ALTO ? CLOUD_ALTO_STEPS : int(mix(float(CLOUD_MIN_STEPS), float(CLOUD_MAX_STEPS),
        clamp((end - start) / (thickness * 4.0), 0.0, 1.0)));
    float stepLength = (end - start) / float(steps);
    float t = start + stepLength * jitter;

    CloudPhase phase = cloudPhase(dot(direction, LightDirection));
    float horizontalLength = sqrt(max(0.0, 1.0 - direction.y * direction.y));
    float curvature = horizontalLength * horizontalLength / (2.0 * PLANET_RADIUS);

    vec3 scattered = vec3(0.0);
    float transmittance = 1.0;
    for (int i = 0; i < steps; i++) {
        float altitude = CameraHeight + direction.y * t + curvature * t * t;
        vec3 world = vec3(CameraXZ.x + direction.x * t, altitude, CameraXZ.y + direction.z * t);
        float density = layerDensity(layer, world, true);
        if (density > 0.0) {
            entry = min(entry, t);
            vec3 source = cloudSource(layer, world, density, (altitude - base) / thickness, phase, jitter);
            float stepTransmittance = exp(-density * EXTINCTION * stepLength);
            scattered += transmittance * source * (1.0 - stepTransmittance);
            transmittance *= stepTransmittance;
            if (transmittance < 0.01) {
                break;
            }
        }
        t += stepLength;
    }
    if (entry >= MAX_DISTANCE) {
        return vec4(0.0);
    }
    // Distant clouds sink into the haze, which the sky behind them already shows.
    float haze = exp(-entry / HazeDistance);
    return vec4(scattered * Brightness, 1.0 - transmittance) * haze;
}

// Premultiplied radiance and opacity of one shape along a direction out to limit blocks, and
// where it begins. Only the chord through its bounds is marched (Cygames lists the spheres a
// ray meets and marches those alone), and on it the march strides through the empty air by
// the distance to the shape's surface, as sphere tracing does (Nubis Cubed, SimonDev), and
// steps finely only inside: even steps over a shape's chord sliced it into rings.
vec4 marchShape(int shape, vec3 direction, float jitter, float limit, out float entry) {
    entry = MAX_DISTANCE;
    vec4 bounds = ShapeBounds[shape];
    vec3 camera = vec3(CameraXZ.x, CameraHeight, CameraXZ.y);
    vec3 offset = camera - bounds.xyz;
    float b = dot(offset, direction);
    float discriminant = b * b - dot(offset, offset) + bounds.w * bounds.w;
    if (discriminant <= 0.0) {
        return vec4(0.0);
    }
    float root = sqrt(discriminant);
    float start = max(-b - root, 0.0);
    float end = min(-b + root, min(limit, MAX_DISTANCE));
    if (end <= start) {
        return vec4(0.0);
    }
    float height = ShapeSize[shape].x;
    float stepLength = clamp(height / 80.0, 4.0, 20.0);
    float t = start + stepLength * jitter;
    CloudPhase phase = cloudPhase(dot(direction, LightDirection));
    float horizontalLength = sqrt(max(0.0, 1.0 - direction.y * direction.y));
    float curvature = horizontalLength * horizontalLength / (2.0 * PLANET_RADIUS);
    vec3 scattered = vec3(0.0);
    float transmittance = 1.0;
    for (int i = 0; i < CLOUD_SHAPE_STEPS && t < end; i++) {
        // Lifted with the ground's curve, as the layers are, so a shape stays above its layer.
        vec3 world = camera + direction * t + vec3(0.0, curvature * t * t, 0.0);
        float outside = -shapeInside(shape, world);
        if (outside > stepLength) {
            // Whole steps, so the samples stay on the ray's jittered grid: jumping by the
            // distance itself put every first sample the same depth under the surface, and the
            // noise drew contour lines there.
            t += floor(outside / stepLength) * stepLength;
            continue;
        }
        float density = shapeDensity(shape, world, true);
        if (density > 0.0) {
            entry = min(entry, t);
            // A shape stands apart, so the sky lights it from the sides, through the cloud
            // between a point and the nearest surface, rather than down a column as a layer is
            // lit, and the ground's light reaches its foot alone.
            float rise = world.y - ShapeForm[shape].w;
            float cover = min(2.0 * max(-outside, 0.0), SHAPE_LIT_THICKNESS);
            vec3 source = scatteredLight(shapeLightDepth(shape, world, jitter), density,
                density * EXTINCTION * cover, rise / height,
                SHAPE_FLANK_SHADE * (1.0 - smoothstep(0.75, 1.0, rise / height)),
                1.0 - smoothstep(0.0, SHAPE_LIT_THICKNESS, rise), phase) + flashLight(world);
            float stepTransmittance = exp(-density * EXTINCTION * stepLength);
            scattered += transmittance * source * (1.0 - stepTransmittance);
            transmittance *= stepTransmittance;
            if (transmittance < 0.01) {
                break;
            }
        }
        t += stepLength;
    }
    if (entry >= MAX_DISTANCE) {
        return vec4(0.0);
    }
    float haze = exp(-entry / HazeDistance);
    return vec4(scattered * Brightness, 1.0 - transmittance) * haze;
}

// Premultiplied radiance and opacity of the clouds along a direction out to limit blocks,
// nearest layer or shape first, and where the nearest begins, MAX_DISTANCE with none.
vec4 marchCloudsTo(vec3 direction, float jitter, float limit, out float entry) {
    vec4 parts[3 + CLOUD_MAX_SHAPES];
    float entries[3 + CLOUD_MAX_SHAPES];
    int count = 3;
    for (int layer = 0; layer < 3; layer++) {
        parts[layer] = marchLayer(layer, direction, jitter, limit, entries[layer]);
    }
    for (int shape = 0; shape < CLOUD_MAX_SHAPES; shape++) {
        if (shape >= ShapeCount) {
            break;
        }
        parts[count] = marchShape(shape, direction, jitter, limit, entries[count]);
        count++;
    }
    entry = MAX_DISTANCE;
    for (int i = 0; i < count; i++) {
        entry = min(entry, entries[i]);
    }
    vec4 result = vec4(0.0);
    for (int pass = 0; pass < count; pass++) {
        int nearest = -1;
        for (int i = 0; i < count; i++) {
            if (entries[i] < MAX_DISTANCE && (nearest < 0 || entries[i] < entries[nearest])) {
                nearest = i;
            }
        }
        if (nearest < 0) {
            break;
        }
        result += (1.0 - result.a) * parts[nearest];
        entries[nearest] = MAX_DISTANCE;
    }
    return result;
}

// Thin ice cloud: forward scattering, lit by the sun and the sky, and barely opaque.
const float CIRRUS_G = 0.6;
const float CIRRUS_SUN_GAIN = 1.6;
const float CIRRUS_SKY_GAIN = 0.7;
const float CIRRUS_OPACITY = 0.3;

// The cirrus layer seen along a direction, premultiplied. Its texture is laid out as the
// sky layers' cirrus dome maps it, turned slowly about the vertical by CirrusAngle.
vec4 cirrusLayer(vec3 direction) {
    if (CirrusStrength <= 0.0 || direction.y <= sin(radians(CIRRUS_MIN_ELEVATION_DEGREES))) {
        return vec4(0.0);
    }
    float c = cos(CirrusAngle);
    float s = sin(CirrusAngle);
    vec2 local = vec2(c * direction.x - s * direction.z, s * direction.x + c * direction.z);
    float horizontal = length(local);
    float elevation = degrees(asin(clamp(direction.y, -1.0, 1.0)));
    float r = (90.0 - elevation) / (90.0 - CIRRUS_MIN_ELEVATION_DEGREES);
    vec2 uv = 0.5 + 0.5 * r * (horizontal > 1.0e-5 ? local / horizontal : vec2(0.0));
    float density = texture(Cirrus, uv).r * CirrusStrength;
    if (density <= 0.0) {
        return vec4(0.0);
    }
    float cosTheta = dot(direction, LightDirection);
    float phase = mix(henyeyGreenstein(cosTheta, CIRRUS_G), 1.0 / (4.0 * PI), 0.5);
    vec3 radiance = LightIrradiance * (phase * CIRRUS_SUN_GAIN) + AmbientTop * CIRRUS_SKY_GAIN;
    return vec4(radiance * density, density * CIRRUS_OPACITY);
}
