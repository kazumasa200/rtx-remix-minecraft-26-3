/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.lens;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.joml.Matrix4f;
import org.joml.Vector3fc;
import org.joml.Vector4f;
import com.nvidia.rtxremix.minecraft.sky.CloudField;
import com.nvidia.rtxremix.minecraft.sky.LightningFlash;
import com.nvidia.rtxremix.minecraft.sky.SkyLook;
import com.nvidia.rtxremix.minecraft.sky.SkyRenderer;
import com.nvidia.rtxremix.minecraft.sky.SkyTimeJump;
import org.lwjgl.opengl.EXTTextureFilterAnisotropic;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryStack;
import java.nio.IntBuffer;
import java.util.SplittableRandom;

/**
 * Draws a camera lens over the composited Remix image: a light depth of field focused on
 * what the crosshair rests on, and flares from lights: ghosts and a rainbow ring along the
 * line from each light through the screen centre, and a starburst on the sun. The lights are
 * the sun and the brightest the frame submits ({@link FlareSources}); each flares as brightly
 * as the image is where it lands, so one behind a block or lost in the daylight does not.
 *
 * <p>It also fades distant land into the sky at the horizon (aerial haze) and draws the
 * Unbound clouds per pixel ({@link CloudField}). The runtime shows them on a dome, a panorama
 * marched a few times a second and averaged, which stays for reflections and the sky's light;
 * on screen that panorama is soft, trails a moving camera, and is hidden by the land wherever
 * a cloud lies in front of it. So the lens marches the same field with the same lighting for
 * every pixel: over the land, the clouds between the camera and the land; over the sky, all
 * of them, put in place of the dome's, which it takes back out of the image using the
 * panorama the dome shows and the sky the image shows behind it.
 *
 * <p>The passes run after the composite and before the weather. The first keeps only the
 * near-saturated parts of the Remix image, at half resolution, and mips them, which is what a
 * light's brightness is read from; small passes then gather the sky's colour along the
 * horizon, march the clouds at half resolution, measure how bright the image shows opaque
 * clouds against their marched light, and gather the sky behind the dome's clouds by
 * elevation; another traces the sunlight the waves land on the floors of the water around the
 * camera; the last redraws the image opaque over the main target, blurred by depth, hazed,
 * clouded and lit by the caustics, with the flares added. The interop depth is window depth from the Remix
 * camera's planes.
 *
 * <p>Render thread only. Like {@code RemixWeatherRenderer}, state the OpenGL backend caches
 * goes through {@link GlStateManager}, and program, vertex array, sampler and viewport
 * bindings are put back.
 */
public final class LensEffects {

    /** Whether the runtime's dome clouds are left as they are, a debug view; render thread only. */
    private static boolean domeCloudsShown;

    public static boolean isDomeCloudsShown() { return domeCloudsShown; }
    public static void setDomeCloudsShown(boolean shown) { domeCloudsShown = shown; }

    // The near plane the Remix camera renders with; the far plane comes with the camera.
    private static final float NEAR_PLANE = 0.05f;
    /** Blocks the focus raycast reaches; with nothing in reach the lens focuses here. */
    private static final double MAX_FOCUS_DISTANCE = 256.0;
    private static final float MIN_FOCUS_DISTANCE = 0.5f;
    /** Seconds for the focus to settle on a new distance, as an eye or an autofocus does. */
    private static final float FOCUS_SECONDS = 0.35f;
    /** Seconds for the clouds' brightness to follow the sky's exposure. */
    private static final float CALIBRATION_SECONDS = 0.5f;
    /**
     * Rays cast toward the sun, at its centre and this many radians to four sides, and their
     * reach: the share that meets no block is how much of its flare shows. The depth alone let
     * it through glass and lit ceilings, which the interop depth reads as sky or as a lamp.
     */
    private static final float SUN_RAY_SPREAD = 0.01f;
    private static final double SUN_RAY_LENGTH = 256.0;
    /** Seconds for the sun's flare to follow its visibility, so a passing post dims it rather than cuts it. */
    private static final float SUN_VISIBILITY_SECONDS = 0.08f;
    /** Seconds over which the sun's light for its flare follows the image. */
    private static final float SUN_LIGHT_SECONDS = 0.1f;
    /** How far the clear sky overhead is deepened at the sky saturation look's default of 1. */
    private static final float SKY_DEPTH_AT_DEFAULT = 0.5f;

    private static final int UNIT_SCENE = 0;
    private static final int UNIT_DEPTH = 1;
    private static final int UNIT_BRIGHT = 2;
    private static final int UNIT_HORIZON = 3;
    private static final int UNIT_NOISE = 4;
    private static final int UNIT_CLOUDS = 5;
    private static final int UNIT_CALIBRATION = 6;
    private static final int UNIT_CLOUD_DISTANCE = 7;
    private static final int UNIT_CIRRUS = 8;
    private static final int UNIT_PANORAMA = 9;
    private static final int UNIT_ATMOSPHERE = 10;
    private static final int UNIT_HISTORY = 11;
    // The temporal pass reads no panorama, so its history's distances take that unit; above
    // eleven the backend caches no bindings.
    private static final int UNIT_HISTORY_DISTANCE = UNIT_PANORAMA;
    // Nor do the sky bands' pass and the composite read the clouds' history.
    private static final int UNIT_SKY_BANDS = UNIT_HISTORY;
    // The statistics passes read no marched clouds, and the composite binds its own over them.
    private static final int UNIT_SAMPLES = UNIT_CLOUDS;
    private static final int UNIT_SAMPLE_CLOUDS = UNIT_CLOUD_DISTANCE;
    // The calibration reads the atmosphere only as sampled.
    private static final int UNIT_SAMPLE_SKY = UNIT_ATMOSPHERE;
    // The calibration and resolve passes read the raw rows, and neither the sky bands nor the history.
    private static final int UNIT_CALIBRATION_ROWS = UNIT_HISTORY;
    private static final int UNIT_COUNT = 12;
    // Past the units the backend caches, so bound and unbound directly around the composite.
    private static final int UNIT_WATER_COLUMNS = UNIT_COUNT;
    private static final int UNIT_CAUSTICS = UNIT_COUNT + 1;
    /** The haze's settled share: last frame's for the haze pass, this frame's for the composite. */
    private static final int UNIT_HAZE = UNIT_COUNT + 2;
    /** The sun's eased light: last frame's for the sun pass, this frame's for the source pass. */
    private static final int UNIT_SUN = UNIT_COUNT + 3;
    /** Each flare source's light, from the source pass, for the composite. */
    private static final int UNIT_SOURCES = UNIT_COUNT + 4;
    /** The glow pass's maps, an array texture, for the composite. */
    private static final int UNIT_GLOW = UNIT_COUNT + 5;
    /**
     * Blocks over which the caustics' waves repeat, each way. Every wave fits the period a whole
     * number of times, so the web traced over one period holds for every water anywhere: a map
     * of it reads at any distance with no edge, and its repeat is too far apart to make out in a
     * web of 32 waves.
     */
    private static final int CAUSTIC_PERIOD = 40;
    /** The caustic maps' texels per block: at sixteen, the web's lines read as pixels. */
    private static final int CAUSTIC_TEXELS = 32;
    private static final int CAUSTIC_MAP_SIZE = CAUSTIC_PERIOD * CAUSTIC_TEXELS;
    /**
     * The caustic grid's cells per block: ten across the shortest wave, so the grid follows its
     * curve and a line of light bends smoothly rather than in straight pieces.
     */
    private static final int CAUSTIC_CELLS = 16;
    private static final int CAUSTIC_GRID = CAUSTIC_PERIOD * CAUSTIC_CELLS;
    /** Blocks the waves may turn a ray of light aside from where flat water lands it. */
    private static final int CAUSTIC_MARGIN = 6;
    private static final int CAUSTIC_TRACE_SIZE = (CAUSTIC_PERIOD + 2 * CAUSTIC_MARGIN) * CAUSTIC_TEXELS;
    /**
     * The depths a map is traced at, blocks below the surface: a water source's top stands 8/9
     * of a block over its floor, so the first is that and each next a block more, and the floors
     * of Minecraft's water lie on them exactly. Deeper floors take the last.
     */
    private static final int CAUSTIC_LAYERS = 10;
    private static final float CAUSTIC_FIRST_DEPTH = 8.0f / 9.0f;
    private static final int CAUSTIC_WAVES = 32;
    private static final double SHORTEST_WAVE = 0.6;
    private static final double LONGEST_WAVE = 3.0;
    /**
     * How steep the traced water is: the summed waves' slope is about three quarters of this.
     * Steep enough that the light gathers into lines a block or two down, where most of
     * Minecraft's shallow water has its floor.
     */
    private static final double WAVE_SLOPE = 0.4;
    /** Waves run at this share of the speed deep water gives their length, calmer than a sea's. */
    private static final double WAVE_SPEED = 0.7;
    private static final double GRAVITY = 9.81;
    /**
     * The caustics' waves, each a wave vector in radians per block, a height in blocks, an angular
     * frequency and a phase to start from: lengths spread evenly in scale between the shortest
     * and the longest, and every heading alike, so the web's grain comes from the sun alone. A
     * low sun's light crosses the slopes slantwise and lands spread along its way, which draws
     * the web out in the sun's direction. Each wave vector is rounded to whole waves over
     * {@link #CAUSTIC_PERIOD}.
     */
    private static final double[] WAVE_X = new double[CAUSTIC_WAVES];
    private static final double[] WAVE_Z = new double[CAUSTIC_WAVES];
    private static final double[] WAVE_HEIGHT = new double[CAUSTIC_WAVES];
    private static final double[] WAVE_FREQUENCY = new double[CAUSTIC_WAVES];
    private static final double[] WAVE_PHASE = new double[CAUSTIC_WAVES];

    static {
        SplittableRandom random = new SplittableRandom(0x57415645L);
        for (int i = 0; i < CAUSTIC_WAVES; i++) {
            double wavelength = SHORTEST_WAVE
                    * Math.pow(LONGEST_WAVE / SHORTEST_WAVE, (i + random.nextDouble()) / CAUSTIC_WAVES);
            double heading = random.nextDouble() * 2.0 * Math.PI;
            // The nearest whole number of waves to the period each way.
            double across = CAUSTIC_PERIOD / wavelength;
            WAVE_X[i] = 2.0 * Math.PI * Math.round(across * Math.cos(heading)) / CAUSTIC_PERIOD;
            WAVE_Z[i] = 2.0 * Math.PI * Math.round(across * Math.sin(heading)) / CAUSTIC_PERIOD;
            double k = Math.hypot(WAVE_X[i], WAVE_Z[i]);
            WAVE_HEIGHT[i] = WAVE_SLOPE * (0.6 + 0.8 * random.nextDouble()) / Math.sqrt(CAUSTIC_WAVES) / k;
            WAVE_FREQUENCY[i] = Math.sqrt(GRAVITY * k) * WAVE_SPEED;
            WAVE_PHASE[i] = random.nextDouble() * 2.0 * Math.PI;
        }
    }
    /** The share of a floor's light taken to come straight from the sun when the clouds do not say. */
    private static final float DEFAULT_DIRECT_SHARE = 0.7f;
    /**
     * Brightness bands the clouds' calibration keeps apart, one stop each from 1/256 up, since
     * the tone mapper bends dark and bright cloud differently; enough for the clear sky at noon.
     */
    private static final int CALIBRATION_BINS = 16;
    /** Bands of the sine of elevation, nadir to zenith, the sky behind the clouds is kept for. */
    private static final int SKY_BANDS = 32;
    /** A camera move this far in a frame is a teleport, after which the clouds' history starts over. */
    private static final double HISTORY_RESET_DISTANCE = 64.0;
    /** The coarse grid the statistics passes sample the screen on. */
    private static final int SAMPLE_COLUMNS = 64;
    private static final int SAMPLE_ROWS = 36;
    /** Columns across the screen the sky's colour at the horizon is kept for. */
    private static final int HORIZON_BINS = 256;
    /** The sun and the lights {@link FlareSources} keeps. */
    private static final int MAX_SOURCES = FlareSources.MAX + 1;
    private static final float[] NO_LIGHT = new float[4];
    /**
     * Blocks beyond which a lamp no longer flares: farther, it covers a pixel or so, and what is
     * measured around it is whatever bright thing lies beside it.
     */
    private static final float MAX_LIGHT_DISTANCE = 64.0f;
    /** The maps the glow pass writes, one layer each. */
    private static final int GLOW_LAYERS = 4;

    private static final String VERTEX_SHADER = """
            #version 330 core
            void main() {
                // One triangle covering the screen.
                vec2 corner = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                gl_Position = vec4(corner * 2.0 - 1.0, 0.0, 1.0);
            }
            """;

    // Remix's images run top to bottom, so screen positions flip to sample them.
    private static final String SCENE_SAMPLING = """
            uniform sampler2D SceneColor;
            uniform vec2 ViewportSize;

            vec3 scene(vec2 uv) {
                return texture(SceneColor, vec2(uv.x, 1.0 - uv.y)).rgb;
            }
            """;

    private static final String DEPTH_SAMPLING = """
            uniform sampler2D SceneDepth;
            uniform float Near;
            uniform float Far;

            // Distance along the view axis; the sky reads as the far plane.
            float distanceAt(vec2 uv) {
                float depth = texture(SceneDepth, vec2(uv.x, 1.0 - uv.y)).r;
                return Near * Far / (Far - depth * (Far - Near));
            }

            bool isSky(float distance) {
                return distance > Far * 0.99;
            }
            """;

    private static final String VIEW_RAY = """
            uniform vec3 CameraForward;
            uniform vec3 CameraRight;
            uniform vec3 CameraUp;
            uniform vec2 TanHalfFov;

            // The ray through a screen position, one block long along the view axis.
            vec3 viewRay(vec2 uv) {
                vec2 ndc = uv * 2.0 - 1.0;
                return CameraForward + CameraRight * (ndc.x * TanHalfFov.x) + CameraUp * (ndc.y * TanHalfFov.y);
            }
            """;

    // The land a pixel shows, along the view axis, for the effects drawn on land. A sky pixel
    // beside land takes the farthest land around it, so a silhouette's edge is covered.
    private static final String LAND_DISTANCE = """
            // Nearer than this is the player's own hand, which stays sharp.
            const float HAND_DISTANCE = 1.5;

            float landDistance(vec2 uv) {
                float axial = distanceAt(uv);
                if (!isSky(axial)) {
                    return axial;
                }
                vec2 texel = 1.0 / vec2(textureSize(SceneDepth, 0));
                float land = axial;
                for (int y = -1; y <= 1; y++) {
                    for (int x = -1; x <= 1; x++) {
                        float neighbour = distanceAt(uv + vec2(x, y) * texel);
                        if (!isSky(neighbour) && (isSky(land) || neighbour > land)) {
                            land = neighbour;
                        }
                    }
                }
                return land;
            }
            """;

    // Aerial perspective: air thins with height, so the haze a path crosses is its length
    // times the mean density along it, which the exponential falloff integrates exactly.
    private static final String HAZE_DENSITY = """
            uniform float Haze;
            // The rain's strength, 0 to 1.
            uniform float Rain;
            uniform float CameraY;

            const float HAZE_PER_BLOCK = 0.000866;
            const float HAZE_SEA_LEVEL = 63.0;
            const float HAZE_SCALE_HEIGHT = 300.0;
            // Rain fills the air below the clouds evenly and closes the view to about two
            // kilometres. The runtime's own rain fog mostly absorbs, darkening distant land
            // below the overcast sky; here it scatters the sky's grey back in, as rain does.
            const float RAIN_HAZE_PER_BLOCK = 0.002;

            // The share of the colour of land axial blocks along the view axis that the haze
            // replaces with the sky's.
            float hazeAmount(vec3 ray, float axial) {
                float pathLength = axial * length(ray);
                float rise = axial * ray.y / HAZE_SCALE_HEIGHT;
                float mean = abs(rise) > 1.0e-3 ? (1.0 - exp(-rise)) / rise : 1.0 - 0.5 * rise;
                float startDensity = exp(-(CameraY - HAZE_SEA_LEVEL) / HAZE_SCALE_HEIGHT);
                float opticalDepth = Haze * pathLength
                    * (HAZE_PER_BLOCK * min(startDensity * mean, 4.0) + RAIN_HAZE_PER_BLOCK * Rain);
                return 1.0 - exp(-opticalDepth);
            }
            """;

    // The image is display encoded; light adds up only once decoded.
    private static final String COLOUR_SPACE = """
            vec3 srgbToLinear(vec3 c) {
                c = max(c, vec3(0.0));
                return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(0.04045, c));
            }

            vec3 linearToSrgb(vec3 c) {
                c = max(c, vec3(0.0));
                return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, c));
            }
            """;

    // Reads a sky kept per band of elevation (SKY_BANDS of them, by the sine of the elevation).
    // A band's alpha says how much it is trusted: it falls while the band sees no sky and climbs
    // back while it does, and a band at least half trusted counts as seen.
    private static final String BAND_READING = "const int SKY_BANDS = " + SKY_BANDS + ";\n" + """
            // The nearest band at least this fresh, from the given distance on; alpha zero when none is.
            vec4 nearestBand(sampler2D bands, int band, float freshness, int from) {
                for (int d = from; d < SKY_BANDS; d++) {
                    vec4 below = texelFetch(bands, ivec2(max(band - d, 0), 0), 0);
                    if (below.a > freshness) {
                        return below;
                    }
                    vec4 above = texelFetch(bands, ivec2(min(band + d, SKY_BANDS - 1), 0), 0);
                    if (above.a > freshness) {
                        return above;
                    }
                }
                return vec4(0.0);
            }

            // A band, passing over by its trust to the nearest other one that sees sky now, or
            // failing that has ever seen it; alpha zero when none has. A band below the horizon
            // keeps the sky it showed from high up, and land there faded toward that rather than
            // toward the horizon in view: after a clear start, rain's distant land glowed white
            // under a dark sky. Turning from one band to another at once stepped what was read
            // whenever walking bobbed a band at the edge of the view in and out of it.
            vec4 skyBand(sampler2D bands, int band) {
                vec4 own = texelFetch(bands, ivec2(band, 0), 0);
                vec4 other = nearestBand(bands, band, 0.5, 1);
                if (other.a <= 0.0) {
                    other = nearestBand(bands, band, 0.0, 0);
                }
                return other.a > 0.0 ? mix(other, own, smoothstep(0.25, 0.75, own.a)) : own;
            }

            vec4 skyBandAt(sampler2D bands, float rise) {
                float position = clamp((rise * 0.5 + 0.5) * float(SKY_BANDS) - 0.5, 0.0, float(SKY_BANDS - 1));
                int low = int(position);
                return mix(skyBand(bands, low), skyBand(bands, min(low + 1, SKY_BANDS - 1)), position - float(low));
            }
            """;

    // Where a lamp's glow lies on screen, from the flare sources' positions. The runtime's bloom
    // spreads a lamp's colour over the sky around it, which the sky's statistics took for the sky:
    // the clouds behind a torch turned red, and so did the sky read through their thin edges.
    // Within it the clear sky is not measured, and all the image shows over the sky expected there
    // counts as glow. A lamp's glow shrinks with its distance, so the reach does too. A lamp the
    // land hides glows on nothing: a fire a storm had set burning behind a ridge laid a disc of
    // the sky's light over the clouds above it.
    // The includer declares Sources, SourceCount and Aspect, and samples depth.
    private static final String LAMP_GLOW_READING = """
            // In screen heights from a lamp at LAMP_GLOW_DISTANCE or nearer: glow throughout
            // inside the first, none past the second.
            const float LAMP_GLOW_INNER = 0.15;
            const float LAMP_GLOW_OUTER = 0.35;
            const float LAMP_GLOW_DISTANCE = 8.0;

            float lampGlow(vec2 uv) {
                float near = 0.0;
                for (int i = 0; i < SourceCount; i++) {
                    if (abs(Sources[i].z - 1.0) < 0.5) {
                        continue;
                    }
                    // Land well in front of the lamp hides it; the block a torch stands on can
                    // cover its centre and still let its flame show. A lamp in the hand is in
                    // front of everything.
                    if (Sources[i].z < 1.5 && distanceAt(Sources[i].xy) < Sources[i].w - max(4.0, 0.25 * Sources[i].w)) {
                        continue;
                    }
                    float reach = LAMP_GLOW_DISTANCE / max(Sources[i].w, LAMP_GLOW_DISTANCE);
                    float d = length((uv - Sources[i].xy) * vec2(Aspect, 1.0)) / reach;
                    near = max(near, 1.0 - smoothstep(LAMP_GLOW_INNER, LAMP_GLOW_OUTER, d));
                }
                return near;
            }
            """;
    private static final String LAMP_GLOW = "const int MAX_SOURCES = " + MAX_SOURCES + ";\n" + """
            uniform vec4 Sources[MAX_SOURCES];
            uniform int SourceCount;
            uniform float Aspect;
            """ + LAMP_GLOW_READING;

    // Per screen column, the sky just above the lowest point it shows, against the sky the whole
    // screen shows at that height (decoded, per channel): how the sky in that direction differs,
    // as toward the setting sun, which distant land fades toward with the band at its own
    // height. A column showing no sky is left empty, alpha zero, for the fill pass.
    private static final String HORIZON_FRAGMENT_SHADER = """
            #version 330 core
            out vec4 fragColor;
            """ + SCENE_SAMPLING + DEPTH_SAMPLING + VIEW_RAY + COLOUR_SPACE + BAND_READING + LAMP_GLOW + """
            uniform sampler2D SkyBands;

            const int ROWS = 128;

            void main() {
                float u = gl_FragCoord.x / ViewportSize.x;
                for (int i = 0; i < ROWS; i++) {
                    float v = (float(i) + 0.5) / float(ROWS);
                    // A little above the land's edge, where antialiasing no longer mixes it in.
                    vec2 above = vec2(u, min(v + 2.0 / float(ROWS), 1.0));
                    vec2 higher = vec2(u, min(v + 4.0 / float(ROWS), 1.0));
                    // The sky must run on up to where its colour is read: a lone texel with
                    // nothing drawn, a gap in leaves that the unantialiased depth shows on some
                    // frames and not others, would read the land and flicker the haze.
                    // Nor is a lamp's glow the sky: faded toward it, distant land around a torch
                    // turned into pale holes in the glow.
                    if (isSky(distanceAt(vec2(u, v))) && isSky(distanceAt(above)) && isSky(distanceAt(higher))
                            && lampGlow(above) < 0.25) {
                        vec3 colour = srgbToLinear((scene(above) + scene(higher)) * 0.5);
                        vec4 band = skyBandAt(SkyBands, normalize(viewRay(above)).y);
                        if (band.a <= 0.0) {
                            break;
                        }
                        fragColor = vec4(clamp(colour / max(band.rgb, vec3(1.0e-4)), 0.5, 2.0), 1.0);
                        return;
                    }
                }
                fragColor = vec4(0.0);
            }
            """;

    // Gives a column without sky the nearest column's, and keeps last frame's when no column
    // shows any sky, as when looking straight down or in a cave.
    private static final String HORIZON_FILL_FRAGMENT_SHADER = """
            #version 330 core
            out vec4 fragColor;
            uniform sampler2D HorizonRaw;
            uniform sampler2D HorizonPrevious;

            void main() {
                int x = int(gl_FragCoord.x);
                int columns = textureSize(HorizonRaw, 0).x;
                vec4 here = texelFetch(HorizonRaw, ivec2(x, 0), 0);
                if (here.a > 0.0) {
                    fragColor = here;
                    return;
                }
                for (int d = 1; d < columns; d++) {
                    vec4 left = x - d >= 0 ? texelFetch(HorizonRaw, ivec2(x - d, 0), 0) : vec4(0.0);
                    vec4 right = x + d < columns ? texelFetch(HorizonRaw, ivec2(x + d, 0), 0) : vec4(0.0);
                    float leftSky = left.a > 0.0 ? 1.0 : 0.0;
                    float rightSky = right.a > 0.0 ? 1.0 : 0.0;
                    if (leftSky + rightSky > 0.0) {
                        fragColor = (left * leftSky + right * rightSky) / (leftSky + rightSky);
                        return;
                    }
                }
                fragColor = texelFetch(HorizonPrevious, ivec2(x, 0), 0);
            }
            """;

    // Averages each column with its neighbours across a sixth of the screen, so a column whose
    // lowest sky is a cloud does not stripe the haze against the columns beside it.
    private static final String HORIZON_SMOOTH_FRAGMENT_SHADER = """
            #version 330 core
            out vec4 fragColor;
            uniform sampler2D HorizonRaw;

            const int RADIUS = 24;

            void main() {
                int x = int(gl_FragCoord.x);
                int columns = textureSize(HorizonRaw, 0).x;
                vec4 sum = vec4(0.0);
                float weights = 0.0;
                float covered = 0.0;
                for (int d = -RADIUS; d <= RADIUS; d++) {
                    int column = clamp(x + d, 0, columns - 1);
                    float weight = float(RADIUS + 1 - abs(d));
                    vec4 colour = texelFetch(HorizonRaw, ivec2(column, 0), 0);
                    float sky = colour.a > 0.0 ? weight : 0.0;
                    sum += colour * sky;
                    covered += sky;
                    weights += weight;
                }
                fragColor = covered > 0.5 * weights ? sum / covered : vec4(0.0);
            }
            """;

    // The atmosphere's radiance toward a direction, as the dome light shows it behind the clouds
    // (CloudField.skyTexture): rows laid out as sky_atmosphere.comp's skyDirection lays them, and
    // a last row of statistics rather than sky.
    private static final String ATMOSPHERE_SAMPLING = """
            uniform sampler2D Atmosphere;
            uniform float AtmosphereRadiance;

            vec3 atmosphereAt(vec3 direction) {
                float u = atan(direction.z, direction.x) * (0.5 / 3.14159265358979323846) + 0.25;
                float v = acos(clamp(direction.y, -1.0, 1.0)) / 3.14159265358979323846;
                float rows = float(textureSize(Atmosphere, 0).y);
                return textureLod(Atmosphere, vec2(u, v * (rows - 1.0) / rows), 0.0).rgb * AtmosphereRadiance;
            }
            """;

    // Where the panorama the dome shows holds a direction, as
    // VolumetricCloudGenerator.rowCoordinate lays it out, and what it shows there. The includer
    // puts CloudField.defines() first.
    private static final String PANORAMA_SAMPLING = """
            uniform sampler2D Panorama;
            // The screen's size in pixels.
            uniform vec2 ScreenSize;

            vec2 panoramaUv(vec3 direction) {
                float elevation = degrees(asin(clamp(direction.y, -1.0, 1.0)));
                float split = float(CLOUD_UPPER_ROWS) / float(CLOUD_HEIGHT);
                float row = elevation >= MIN_ELEVATION_DEGREES
                    ? (90.0 - elevation) / (90.0 - MIN_ELEVATION_DEGREES) * split
                    : split + sqrt((MIN_ELEVATION_DEGREES - elevation) / (MIN_ELEVATION_DEGREES + 90.0)) * (1.0 - split);
                return vec2(atan(direction.x, -direction.z) * (0.5 / 3.14159265358979323846), row);
            }

            // Premultiplied light and opacity toward a screen position, as the image shows them.
            // The panorama is read over the pixel's footprint, as the runtime mipmaps the dome's
            // texture. Where a march grazes a cloud's flat base, its texels speckle between the
            // shaded base and the lit cloud; the runtime's denoiser smooths that speckle out of
            // the image but keeps the clouds' outlines. So the colour is averaged over the
            // neighbours whose opacity matches, and the opacity only deep inside the cloud, for
            // the calibration to compare like with like and the sky behind thin clouds to read
            // true: with the opacity averaged out to the soft edges, the outlines spread into
            // pale rims against the sky.
            vec4 shownClouds(vec2 uv) {
                vec2 centre = panoramaUv(normalize(viewRay(uv)));
                vec2 dx = panoramaUv(normalize(viewRay(uv + vec2(1.0 / ScreenSize.x, 0.0)))) - centre;
                vec2 dy = panoramaUv(normalize(viewRay(uv + vec2(0.0, 1.0 / ScreenSize.y)))) - centre;
                // Across the seam where the azimuth wraps.
                dx.x -= round(dx.x);
                dy.x -= round(dy.x);
                vec4 middle = textureGrad(Panorama, centre, dx, dy);
                vec2 texel = 1.0 / vec2(textureSize(Panorama, 0));
                vec4 sum = middle;
                float weights = 1.0;
                for (int y = -1; y <= 1; y++) {
                    for (int x = -1; x <= 1; x++) {
                        if (x != 0 || y != 0) {
                            vec4 near = textureGrad(Panorama, centre + vec2(x, y) * texel, dx, dy);
                            float w = max(1.0 - abs(near.a - middle.a) * 8.0, 0.0);
                            sum += near * w;
                            weights += w;
                        }
                    }
                }
                float opacity = mix(middle.a, sum.a / weights, smoothstep(0.7, 0.95, middle.a));
                vec4 cloud = vec4(sum.rgb / max(sum.a, 1.0e-4) * opacity, opacity);
                return vec4(max(cloud.rgb, vec3(0.0)), clamp(cloud.a, 0.0, 1.0));
            }
            """;

    // The calibration's brightness bands, one stop each by a cloud's luminance: the sky matte is
    // scene-linear, so clouds span a few stops either side of 1.
    private static final String CALIBRATION_BANDS = "const int CALIBRATION_BINS = " + CALIBRATION_BINS + ";\n" + """
            const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
            const float LOWEST_BAND_LOG2 = -8.0;

            // Where a colour's luminance falls among the bands, counted from the first band's centre.
            float bandPosition(vec3 colour) {
                return log2(max(dot(colour, LUMA), 1.0e-6)) - LOWEST_BAND_LOG2 - 0.5;
            }
            """;

    // The light the image shows for clouds, and for the atmosphere behind them, from the
    // calibration's brightness bands as the resolve pass leaves them, one linear read each: a
    // row for the clouds the dome shows, a row for the clear sky the dome light shows, which the
    // image does not bring to the clouds' exposure, and a row per band of elevation for what that
    // still leaves. Searching the rows for the nearest measured band in every call cost the 4K
    // composite tens of reads a pixel.
    private static final String CLOUD_EXPOSURE = CALIBRATION_BANDS + """
            uniform sampler2D CloudCalibration;

            vec3 calibrationAt(float position, int row) {
                float u = (clamp(position, 0.0, float(CALIBRATION_BINS - 1)) + 0.5) / float(CALIBRATION_BINS);
                return textureLod(CloudCalibration, vec2(u, (float(row) + 0.5) / 3.0), 0.0).rgb;
            }

            // What the image multiplies a cloud's light by, between the bands around its brightness.
            vec3 cloudExposure(vec3 colour) {
                return calibrationAt(bandPosition(colour), 0);
            }

            // What the image multiplies the atmosphere's radiance by.
            vec3 skyExposure(vec3 colour) {
                return calibrationAt(bandPosition(colour), 1);
            }

            // How the image's clear sky differs from the atmosphere at its exposure, at the sine
            // of an elevation: the tone mapper mixes the channels, so neither the sky's luminance
            // nor each channel's own radiance says exactly how it shows a colour, and the sky
            // behind the clouds came out cream or cyan toward the horizon by day.
            vec3 skyResidual(float rise) {
                return calibrationAt(rise * float(CALIBRATION_BINS) - 0.5, 2);
            }

            // The decoded light the image shows for clouds of this premultiplied light and
            // opacity, at the exposure their brightness gets.
            vec3 cloudLight(vec4 cloud) {
                if (cloud.a <= 1.0e-3) {
                    return vec3(0.0);
                }
                return cloudExposure(cloud.rgb / cloud.a) * cloud.rgb;
            }
            """;

    // The clouds' field reads these, by the names cloud_field.glsl expects. Each pixel's march
    // has fewer steps through the cumulus than the dome's, since the history averages several
    // frames' marches, and more through the altostratus, which the dome's panorama blurs but
    // a pixel resolves. The includer puts CloudField.defines() first.
    private static final String CLOUD_FIELD = """
            #define CLOUD_MIN_STEPS 20
            #define CLOUD_MAX_STEPS 40
            #define CLOUD_ALTO_STEPS 6
            const float PI = 3.14159265358979323846;
            uniform sampler2D Cirrus;
            uniform float CirrusAngle;
            uniform float CirrusStrength;
            uniform sampler3D Noise;
            uniform float CloudBase;
            uniform float CloudThickness;
            uniform vec3 LayerCoverage;
            uniform float Density;
            uniform float FeatureScale;
            uniform float Detail;
            uniform vec3 LightDirection;
            uniform vec3 LightIrradiance;
            uniform vec3 AmbientTop;
            uniform vec3 AmbientBottom;
            uniform vec2 CameraXZ;
            uniform float CameraHeight;
            uniform float Brightness;
            uniform float HazeDistance;
            uniform vec4 Flash;
            """ + CloudField.source();

    // The haze's share per pixel, folded into its history where the camera saw the same land.
    // The depth is the runtime's, rendered smaller and jittered for its upscaler, so along a
    // silhouette it flips between the near and the far side every frame while the image settles
    // to a blend of the two; the haze, light on the one and thick on the other, flickered there,
    // most where Distant Horizons' terrain stands behind Minecraft's. A history held inside the
    // range of its neighbours settles to the same blend and trails nothing.
    private static final String HAZE_TEMPORAL_FRAGMENT_SHADER = """
            #version 330 core
            out vec4 fragColor;
            """ + CloudField.defines() + DEPTH_SAMPLING + VIEW_RAY + LAND_DISTANCE + HAZE_DENSITY + COLOUR_SPACE
            + CLOUD_FIELD + """
            uniform sampler2D HazePrevious;
            uniform float Clouds;
            uniform float CloudShadow;
            uniform vec2 ViewportSize;
            uniform mat4 PreviousViewProjection;
            uniform vec3 CameraMotion;
            uniform float HistoryValid;

            // This frame's share: about ten frames averaged.
            const float CURRENT_WEIGHT = 0.1;

            float hazeAt(vec2 uv, float axial) {
                return isSky(axial) || axial < HAND_DISTANCE ? 0.0 : hazeAmount(viewRay(uv), axial);
            }

            float skyAt(vec2 uv) {
                return isSky(distanceAt(uv)) ? 1.0 : 0.0;
            }

            // The share of the sun's light the clouds above let through to the land a pixel shows.
            float shadeAt(vec2 uv, float axial) {
                if (Clouds <= 0.0 || CloudShadow <= 0.0 || isSky(axial)) {
                    return 1.0;
                }
                vec3 ray = viewRay(uv);
                float along = axial < HAND_DISTANCE ? 0.0 : axial * length(ray);
                return shadowTransmittance(vec3(CameraXZ.x, CameraHeight, CameraXZ.y) + normalize(ray) * along);
            }

            // Red: the haze's share. Green: the share of the pixel that shows sky, which the
            // jittered depth samples at a different point of the pixel each frame, so averaged
            // over frames it settles to how much of a pixel along a silhouette is sky, as the
            // antialiased image shows it, instead of flipping between all and none. Blue: the
            // light the clouds' shadow lets through. Distant land is seen at a grazing angle, where
            // the jitter moves the point a pixel shows by tens of blocks, so along a shadow's edge
            // the shadow came and went from frame to frame; it settles, and is taken at a distance
            // from the camera settled over frames, alpha, while the land under the pixel stays
            // about as far. Along the view axis the distance changed as the camera turned, and
            // the shade was taken short of the land or past it while the history caught up.
            // Each frame's own values when no history.
            void main() {
                vec2 uv = gl_FragCoord.xy / ViewportSize;
                float axial = landDistance(uv);
                float current = hazeAt(uv, axial);
                float sky = skyAt(uv);
                float shade = shadeAt(uv, axial);
                vec3 ray = viewRay(uv);
                float along = isSky(axial) ? 6.0e4 : axial * length(ray);
                fragColor = vec4(current, sky, shade, along);
                if (HistoryValid <= 0.0) {
                    return;
                }
                // Neighbours a depth texel away, the reach of the jitter.
                vec2 texel = 1.0 / vec2(textureSize(SceneDepth, 0));
                float low = current;
                float high = current;
                float skyLow = sky;
                float skyHigh = sky;
                float near = axial;
                float far = axial;
                for (int y = -1; y <= 1; y++) {
                    for (int x = -1; x <= 1; x++) {
                        vec2 at = uv + vec2(x, y) * texel;
                        float neighbourAxial = landDistance(at);
                        float neighbour = hazeAt(at, neighbourAxial);
                        low = min(low, neighbour);
                        high = max(high, neighbour);
                        float neighbourSky = skyAt(at);
                        skyLow = min(skyLow, neighbourSky);
                        skyHigh = max(skyHigh, neighbourSky);
                        if (!isSky(neighbourAxial)) {
                            near = min(near, neighbourAxial);
                            far = max(far, neighbourAxial);
                        }
                    }
                }
                // A sky pixel moves as something very far away does, with the turning view alone.
                vec3 previous = normalize(ray) * (isSky(axial) ? 1.0e5 : along) + CameraMotion;
                vec4 clip = PreviousViewProjection * vec4(previous, 1.0);
                if (clip.w <= 0.0) {
                    return;
                }
                vec2 previousUv = clip.xy / clip.w * 0.5 + 0.5;
                if (any(lessThan(previousUv, vec2(0.0))) || any(greaterThan(previousUv, vec2(1.0)))) {
                    return;
                }
                vec4 history = texture(HazePrevious, previousUv);
                // The jitter moves distant land's distance by a fifth or so, a silhouette's far
                // more. Where it is the same land, the shade is taken at the settled distance,
                // moved by as much as the camera's own motion brought the land nearer.
                float historyAlong = history.a + along - length(previous);
                bool sameLand = !isSky(axial) && abs(historyAlong - along) < 0.25 * along + 2.0;
                float settled = sameLand ? mix(historyAlong, along, CURRENT_WEIGHT) : along;
                // Along a silhouette the depth flips between the near and the far side, so the
                // shade settles to a blend of the two, as the image does, whatever the distance.
                bool edge = !isSky(axial) && far > 1.3 * near + 2.0;
                if (sameLand || edge) {
                    shade = mix(history.b, sameLand ? shadeAt(uv, settled / length(ray)) : shade, CURRENT_WEIGHT);
                }
                fragColor = vec4(mix(clamp(history.r, low, high), current, CURRENT_WEIGHT),
                    mix(clamp(history.g, skyLow, skyHigh), sky, CURRENT_WEIGHT), shade, settled);
            }
            """;

    // The clouds as the dome's march draws them, at half resolution: premultiplied light and
    // opacity between the camera and the land, or, over the sky, all of them with the cirrus
    // above; the distance along the view axis each texel marched to, Far for the sky; and
    // where along its ray the clouds begin, MAX_DISTANCE with none.
    private static final String CLOUD_MARCH_FRAGMENT_SHADER = """
            #version 330 core
            layout(location = 0) out vec4 fragColor;
            layout(location = 1) out vec2 fragDistance;
            uniform vec2 ViewportSize;
            uniform float FrameIndex;
            """ + CloudField.defines() + DEPTH_SAMPLING + VIEW_RAY + CLOUD_FIELD + """

            void main() {
                // Of the four pixels under this texel, alternate texels march to the nearest land
                // and to the farthest, so along a silhouette, or a ridge against the sky, the
                // upsample finds texels on both sides.
                bool nearest = ((int(gl_FragCoord.x) + int(gl_FragCoord.y)) & 1) == 0;
                float axial = -1.0;
                vec2 uv = vec2(0.0);
                for (int i = 0; i < 4; i++) {
                    vec2 sampleUv = (gl_FragCoord.xy + vec2(float(i & 1), float(i >> 1)) * 0.5 - 0.25) / ViewportSize;
                    float distance = distanceAt(sampleUv);
                    if (!isSky(distance) && (axial < 0.0 || (nearest ? distance < axial : distance > axial))) {
                        axial = distance;
                        uv = sampleUv;
                    }
                }
                // Interleaved gradient noise, stepped by the golden ratio each frame, so the
                // history averages the march's steps away.
                float jitter = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715)))
                    + FrameIndex * 0.61803398875);
                float entry;
                if (axial < 0.0) {
                    vec3 direction = normalize(viewRay(gl_FragCoord.xy / ViewportSize));
                    vec4 clouds = marchCloudsTo(direction, jitter, MAX_DISTANCE, entry);
                    fragColor = clouds + (1.0 - clouds.a) * cirrusLayer(direction);
                    fragDistance = vec2(Far, entry);
                    return;
                }
                vec3 ray = viewRay(uv);
                float rayLength = length(ray);
                fragColor = marchCloudsTo(ray / rayLength, jitter, axial * rayLength, entry);
                fragDistance = vec2(axial, entry);
            }
            """;

    // This frame's march folded into last frame's result, found where the camera saw the same
    // clouds: at their entry, or the land behind when there are none, moved by the camera's
    // motion. A history whose texel saw sky where this one sees land, or land at another
    // depth, is not taken, and one outside this texel's neighbours' range is pulled into it,
    // so moving clouds and parallax leave no trails.
    private static final String CLOUD_TEMPORAL_FRAGMENT_SHADER = """
            #version 330 core
            layout(location = 0) out vec4 fragColor;
            layout(location = 1) out vec2 fragDistance;
            """ + DEPTH_SAMPLING + VIEW_RAY + """
            uniform sampler2D CloudLight;
            uniform sampler2D CloudDistance;
            uniform sampler2D CloudHistory;
            uniform sampler2D HistoryDistance;
            uniform vec2 ViewportSize;
            uniform mat4 PreviousViewProjection;
            uniform vec3 CameraMotion;
            uniform float HistoryValid;

            // The march's share of each frame: about twenty frames averaged while the view holds
            // still, fewer as what this texel shows moves across the screen, so the clouds
            // settle smooth yet follow a turning camera.
            const float STILL_WEIGHT = 0.05;
            const float MOVING_WEIGHT = 0.25;
            // Texels of motion over which the weight goes from still to moving.
            const float MOTION_TEXELS = 4.0;
            const float NO_CLOUD = 29000.0;
            // Blocks out a texel with neither cloud nor land is taken to lie.
            const float EMPTY_SKY_DISTANCE = 10000.0;

            void main() {
                ivec2 texel = ivec2(gl_FragCoord.xy);
                ivec2 last = ivec2(ViewportSize) - 1;
                vec4 current = texelFetch(CloudLight, texel, 0);
                vec2 distances = texelFetch(CloudDistance, texel, 0).rg;
                fragDistance = distances;
                bool sky = isSky(distances.r);
                vec4 low = current;
                vec4 high = current;
                for (int y = -1; y <= 1; y++) {
                    for (int x = -1; x <= 1; x++) {
                        ivec2 neighbour = clamp(texel + ivec2(x, y), ivec2(0), last);
                        if (isSky(texelFetch(CloudDistance, neighbour, 0).r) == sky) {
                            vec4 value = texelFetch(CloudLight, neighbour, 0);
                            low = min(low, value);
                            high = max(high, value);
                        }
                    }
                }
                fragColor = current;
                if (HistoryValid <= 0.0) {
                    return;
                }
                vec3 ray = viewRay(gl_FragCoord.xy / ViewportSize);
                float along = distances.g < NO_CLOUD ? distances.g
                    : sky ? EMPTY_SKY_DISTANCE : distances.r * length(ray);
                vec4 clip = PreviousViewProjection * vec4(normalize(ray) * along + CameraMotion, 1.0);
                if (clip.w <= 0.0) {
                    return;
                }
                vec2 previousUv = clip.xy / clip.w * 0.5 + 0.5;
                if (any(lessThan(previousUv, vec2(0.0))) || any(greaterThan(previousUv, vec2(1.0)))) {
                    return;
                }
                float previousDistance = texture(HistoryDistance, previousUv).r;
                bool same = sky ? isSky(previousDistance)
                    : !isSky(previousDistance) && abs(previousDistance - distances.r) < 0.1 * distances.r + 0.5;
                if (same) {
                    float motion = length((previousUv - gl_FragCoord.xy / ViewportSize) * ViewportSize);
                    float weight = mix(STILL_WEIGHT, MOVING_WEIGHT, clamp(motion / MOTION_TEXELS, 0.0, 1.0));
                    fragColor = mix(clamp(texture(CloudHistory, previousUv), low, high), current, weight);
                }
            }
            """;

    // The moon's halo and the aurora are the sky's own light beyond the atmosphere, and the
    // runtime's bloom spreads them over the clouds around. Measured, they went into the clouds'
    // calibration and the clear sky's rows and map, which keep what they last saw, and clouds
    // anywhere turned green or pale. Neither is measured.
    private static final String SKY_LIGHT_NEAR = """
            // Toward the moon, with whether it is up, and how strongly the aurora shows.
            uniform vec4 Moon;
            uniform float Aurora;

            // Within this angle of the moon its halo outshines the night sky.
            const float MOON_HALO_COS = 0.97;
            // The aurora's curtains, and the bloom around them, reach no higher.
            const float AURORA_TOP = 0.87;

            bool skyLightNear(vec3 ray) {
                if (Moon.w > 0.0 && dot(ray, Moon.xyz) > MOON_HALO_COS) {
                    return true;
                }
                // The curtains hang within about 80 degrees of north, toward -z.
                return Aurora > 0.0 && ray.y < AURORA_TOP && -ray.z > 0.2 * length(ray.xz);
            }
            """;

    // The screen sampled once on a coarse grid, one texel per sample, for the passes below that
    // gather statistics from it: the decoded image with, for a sample showing sky, the sine of
    // its elevation plus two in alpha (zero for land), and the dome's clouds and the atmosphere
    // behind them there. Each of those passes is a handful of texels that loop over every
    // sample, so the sampling work is done here, in parallel, rather than in their loops.
    private static final String SAMPLES_FRAGMENT_SHADER = """
            #version 330 core
            layout(location = 0) out vec4 sampledScene;
            layout(location = 1) out vec4 sampledClouds;
            layout(location = 2) out vec4 sampledSky;
            """ + "const vec2 SAMPLE_GRID = vec2(" + SAMPLE_COLUMNS + ".0, " + SAMPLE_ROWS + ".0);\n"
            + CloudField.defines() + SCENE_SAMPLING + DEPTH_SAMPLING + VIEW_RAY + COLOUR_SPACE + PANORAMA_SAMPLING
            + ATMOSPHERE_SAMPLING + LAMP_GLOW + SKY_LIGHT_NEAR + """
            uniform float Clouds;

            void main() {
                vec2 uv = gl_FragCoord.xy / SAMPLE_GRID;
                // Sky only with sky on every side: the depth is not antialiased, and a silhouette
                // texel whose depth says sky shows the building's colour.
                vec2 texel = 1.0 / ScreenSize;
                bool sky = isSky(distanceAt(uv)) && isSky(distanceAt(uv + vec2(texel.x, 0.0)))
                    && isSky(distanceAt(uv - vec2(texel.x, 0.0))) && isSky(distanceAt(uv + vec2(0.0, texel.y)))
                    && isSky(distanceAt(uv - vec2(0.0, texel.y)))
                    && lampGlow(uv) < 0.25 && !skyLightNear(normalize(viewRay(uv)));
                sampledScene = vec4(srgbToLinear(scene(uv)), sky ? 2.0 + normalize(viewRay(uv)).y : 0.0);
                sampledClouds = sky && Clouds > 0.0 ? shownClouds(uv) : vec4(0.0);
                sampledSky = vec4(sky && Clouds > 0.0 ? atmosphereAt(normalize(viewRay(uv))) : vec3(0.0), 1.0);
            }
            """;

    // The statistics passes read the samples above.
    private static final String SAMPLE_READING = "const int SAMPLE_COLUMNS = " + SAMPLE_COLUMNS + ";\n"
            + "const int SAMPLE_ROWS = " + SAMPLE_ROWS + ";\n" + """
            uniform sampler2D Samples;
            uniform sampler2D SampleClouds;
            uniform sampler2D SampleSky;
            """;

    // How bright the image shows the sky's clouds against the light they were marched with,
    // per brightness band, one texel each: the sky matte is scene-linear (rtx.skyForceHDR) and
    // exposed and tone mapped with the rest of the frame, so opaque clouds the frame shows in
    // the sky measure what the lens's clouds must be brought to, per channel, since the tone
    // mapper shifts hue at dusk. Only clouds that hide the sky entirely are measured: through
    // a cloud only nine tenths opaque the blue sky is a large share of a dark cloud's colour,
    // and that tinted the dark bands blue. The atmosphere the rest lets through, at last
    // frame's exposure, is taken off as well: at night the sky outshines a moonlit cloud, and
    // a twentieth of it still tinted the night's bands blue. Eased toward from last frame's,
    // and kept while the frame shows no opaque cloud of that brightness in the sky; a measured
    // band has alpha 1. A second row measures the clear sky against the atmosphere the dome light
    // shows, channel by channel, which the image shows about half as bright as the clouds' row
    // would bring it, and a third keeps, per band of elevation from the horizon up, how the
    // image's clear sky still differs from the atmosphere at that exposure. A band that has never been measured, as when the only clouds lie below
    // the camera, holds a guess with alpha one half: the exposure that brings the clear upper
    // sky's radiance to how bright the image shows it, through a Reinhard shoulder. With no
    // band at all, the clouds' scene-linear light went onto the image as it was and showed flat
    // white.
    private static final String CLOUD_CALIBRATION_FRAGMENT_SHADER = """
            #version 330 core
            out vec4 fragColor;
            """ + CLOUD_EXPOSURE + SAMPLE_READING + """
            uniform sampler2D CalibrationPrevious;
            uniform float Follow;
            // The clear sky's rows take turns, one a frame: 0 the sky's, 1 the residual. Each
            // eases by RowFollow, two frames' worth.
            uniform float Phase;
            uniform float RowFollow;
            // The luminance of the upper sky's mean radiance, which lights the clouds.
            uniform float SkyLuminance;
            // 0 for a moment after a jump in the time of day, while the image still shows the old
            // light: measured then, the clouds took noon's colours into midnight. The clouds'
            // row holds its guess meanwhile and the sky's rows hold what they had.
            uniform float Measure;

            // The image's tone curve keeps the mid-tones brighter than Reinhard's through the same
            // sky: at one and a half times the exposure, guessed clouds matched measured ones to a
            // tenth.
            const float GUESS_EXPOSURE_GAIN = 1.5;
            // A band of the clear sky's rows measured on fewer samples took a star or the moon for
            // the sky, and the moon's glow turned green.
            const float MIN_SKY_SAMPLES = 8.0;
            // The rows read every other sample each way: each band runs on one thread, and
            // medians found by halving over all of them took four to seven milliseconds a frame.
            const int SKY_STRIDE = 2;

            // At a sample of clear sky in a band, by the atmosphere's luminance there, what the
            // image shows over the atmosphere.
            float clearSkyIn(int bin, ivec2 at, out vec3 ratio) {
                vec4 image = texelFetch(Samples, at, 0);
                vec3 sky = texelFetch(SampleSky, at, 0).rgb;
                ratio = max(image.rgb, vec3(1.0e-5)) / max(sky, vec3(1.0e-6));
                if (image.a <= 0.0 || texelFetch(SampleClouds, at, 0).a >= 0.02 || dot(sky, LUMA) <= 0.0
                        || clamp(int(floor(bandPosition(sky) + 0.5)), 0, CALIBRATION_BINS - 1) != bin) {
                    return 0.0;
                }
                return 1.0;
            }

            // At a sample of clear sky in a band of elevation, what the image shows over the
            // atmosphere at its exposure.
            float residualIn(int band, ivec2 at, out vec3 ratio) {
                vec4 image = texelFetch(Samples, at, 0);
                vec3 sky = texelFetch(SampleSky, at, 0).rgb;
                vec3 shown = skyExposure(sky) * sky;
                ratio = max(image.rgb, vec3(1.0e-5)) / max(shown, vec3(1.0e-6));
                // Nothing to compare until the sky's row has been resolved once.
                if (image.a <= 0.0 || texelFetch(SampleClouds, at, 0).a >= 0.02 || dot(shown, LUMA) <= 0.0
                        || clamp(int((image.a - 2.0) * float(CALIBRATION_BINS)), 0, CALIBRATION_BINS - 1) != band) {
                    return 0.0;
                }
                return 1.0;
            }

            // The mean in stops over a band's samples within a stop of their mean, channel by
            // channel, or that mean where no sample lies so near.
            vec3 trimmedMean(vec3 mean, int band, bool residual) {
                vec3 ratio;
                vec3 sum = vec3(0.0);
                vec3 kept = vec3(0.0);
                for (int y = 0; y < SAMPLE_ROWS; y += SKY_STRIDE) {
                    for (int x = 0; x < SAMPLE_COLUMNS; x += SKY_STRIDE) {
                        float member = residual ? residualIn(band, ivec2(x, y), ratio) : clearSkyIn(band, ivec2(x, y), ratio);
                        vec3 stops = log2(ratio);
                        vec3 near = member * step(abs(stops - mean), vec3(1.0));
                        sum += near * stops;
                        kept += near;
                    }
                }
                return mix(mean, sum / max(kept, vec3(1.0)), step(vec3(0.5), kept));
            }

            // The clear sky's row: what the image shows over the atmosphere, averaged in stops
            // over a band's samples and again over those within a stop of that, channel by
            // channel. Stars, the moon and the lamps' bloom brighten a few samples far past the
            // rest, which a plain mean would take in; a median found by halving read the samples
            // nine times over, on one thread a band, and cost a millisecond a frame. A band of
            // fewer samples is left as it was.
            vec4 skyBin(int bin, vec4 previous) {
                vec3 ratio;
                vec3 sum = vec3(0.0);
                float weights = 0.0;
                for (int y = 0; y < SAMPLE_ROWS; y += SKY_STRIDE) {
                    for (int x = 0; x < SAMPLE_COLUMNS; x += SKY_STRIDE) {
                        float member = clearSkyIn(bin, ivec2(x, y), ratio);
                        sum += member * log2(ratio);
                        weights += member;
                    }
                }
                if (weights < MIN_SKY_SAMPLES) {
                    return previous;
                }
                vec3 current = exp2(trimmedMean(sum / weights, bin, false));
                return vec4(previous.a > 0.0 ? mix(previous.rgb, current, RowFollow) : current, 1.0);
            }

            // The residual row, per band of elevation from the horizon up: what the image's clear
            // sky shows over the atmosphere at its exposure, averaged as the sky's row is. Kept,
            // fading, while the band shows no clear sky, its alpha falling below half in about a
            // second, so readers turn to a band in view.
            vec4 residualBin(int band, vec4 previous) {
                vec3 ratio;
                vec3 sum = vec3(0.0);
                float weights = 0.0;
                for (int y = 0; y < SAMPLE_ROWS; y += SKY_STRIDE) {
                    for (int x = 0; x < SAMPLE_COLUMNS; x += SKY_STRIDE) {
                        float member = residualIn(band, ivec2(x, y), ratio);
                        sum += member * log2(ratio);
                        weights += member;
                    }
                }
                if (weights < MIN_SKY_SAMPLES) {
                    return vec4(previous.rgb, previous.a > 0.0 ? max(previous.a - 0.25 * RowFollow, 1.0e-3) : 0.0);
                }
                // Back in view after it went stale, the band takes the figure at once and its
                // trust climbs back as fast as it fell, so the resolve passes over to it gradually.
                vec3 current = exp2(trimmedMean(sum / weights, band, true));
                return vec4(previous.a >= 0.5 ? mix(previous.rgb, current, RowFollow) : current,
                    min(max(previous.a, 0.0) + 0.25 * RowFollow, 1.0));
            }

            void main() {
                int bin = int(gl_FragCoord.x);
                vec4 previous = texelFetch(CalibrationPrevious, ivec2(gl_FragCoord.xy), 0);
                if (gl_FragCoord.y > 2.0) {
                    fragColor = Measure > 0.5 && Phase > 0.5 ? residualBin(bin, previous) : previous;
                    return;
                }
                if (gl_FragCoord.y > 1.0) {
                    fragColor = Measure > 0.5 && Phase < 0.5 ? skyBin(bin, previous) : previous;
                    return;
                }
                vec3 shown = vec3(0.0);
                vec3 marched = vec3(0.0);
                float weights = 0.0;
                float skyShown = 0.0;
                float skySamples = 0.0;
                for (int y = 0; y < SAMPLE_ROWS; y += SKY_STRIDE) {
                    for (int x = 0; x < SAMPLE_COLUMNS; x += SKY_STRIDE) {
                        vec4 image = texelFetch(Samples, ivec2(x, y), 0);
                        if (image.a <= 0.0) {
                            continue;
                        }
                        vec4 cloud = texelFetch(SampleClouds, ivec2(x, y), 0);
                        // Clear sky well above the horizon's haze.
                        if (cloud.a < 0.05 && image.a - 2.0 > 0.3) {
                            skyShown += dot(image.rgb, LUMA);
                            skySamples += 1.0;
                        }
                        float w = smoothstep(0.95, 1.0, cloud.a);
                        int cloudBin = clamp(int(floor(bandPosition(cloud.rgb) + 0.5)), 0, CALIBRATION_BINS - 1);
                        if (w > 0.0 && cloudBin == bin) {
                            vec3 sky = texelFetch(SampleSky, ivec2(x, y), 0).rgb;
                            vec3 behind = skyExposure(sky) * sky * skyResidual(image.a - 2.0);
                            shown += max(image.rgb - (1.0 - cloud.a) * behind, 0.0) * w;
                            marched += cloud.rgb * w;
                            weights += w;
                        }
                    }
                }
                if (Measure < 0.5 || weights < 3.0 || min(marched.r, min(marched.g, marched.b)) < 1.0e-4 * weights) {
                    if (previous.a >= 1.0 || skySamples < 8.0 || SkyLuminance <= 0.0) {
                        fragColor = previous;
                        return;
                    }
                    float sky = clamp(skyShown / skySamples, 1.0e-4, 0.95);
                    float exposure = GUESS_EXPOSURE_GAIN * sky / ((1.0 - sky) * SkyLuminance);
                    float centre = exp2(float(bin) + LOWEST_BAND_LOG2 + 0.5);
                    vec3 guess = vec3(exposure / (1.0 + exposure * centre));
                    fragColor = vec4(previous.a > 0.0 ? mix(previous.rgb, guess, Follow) : guess, 0.5);
                    return;
                }
                vec3 current = shown / marched;
                fragColor = vec4(previous.a > 0.0 ? mix(previous.rgb, current, Follow) : current, 1.0);
            }
            """;

    // The calibration's rows as the lens reads them: each band, or the nearest one measured;
    // the clear sky's row, with none measured yet, the clouds'; and the residual row's band,
    // passing over by its trust to the nearest one that shows clear sky now, or failing that
    // has ever shown it, or one. Once a frame, so every read of them is a single texel.
    private static final String CALIBRATION_RESOLVE_FRAGMENT_SHADER = """
            #version 330 core
            out vec4 fragColor;
            """ + "const int CALIBRATION_BINS = " + CALIBRATION_BINS + ";\n" + """
            uniform sampler2D CalibrationRows;

            // A row's band, or the nearest one at least this fresh, from the given distance on;
            // alpha zero when none is.
            vec4 nearest(int bin, int row, float freshness, int from) {
                for (int d = from; d < CALIBRATION_BINS; d++) {
                    vec4 below = texelFetch(CalibrationRows, ivec2(max(bin - d, 0), row), 0);
                    if (below.a >= freshness) {
                        return below;
                    }
                    vec4 above = texelFetch(CalibrationRows, ivec2(min(bin + d, CALIBRATION_BINS - 1), row), 0);
                    if (above.a >= freshness) {
                        return above;
                    }
                }
                return vec4(0.0);
            }

            // A clouds' band, or the nearest one measured on opaque cloud, or with none measured
            // yet, the band's guess from the clear sky. Guesses beside measured bands blotched the
            // clouds where the two disagreed.
            vec3 cloudBin(int bin) {
                vec4 measured = nearest(bin, 0, 1.0, 0);
                if (measured.a >= 1.0) {
                    return measured.rgb;
                }
                vec4 own = texelFetch(CalibrationRows, ivec2(bin, 0), 0);
                return own.a > 0.0 ? own.rgb : vec3(1.0);
            }

            void main() {
                int bin = int(gl_FragCoord.x);
                int row = int(gl_FragCoord.y);
                vec3 resolved;
                if (row == 0) {
                    resolved = cloudBin(bin);
                } else if (row == 1) {
                    vec4 sky = nearest(bin, 1, 1.0, 0);
                    resolved = sky.a >= 1.0 ? sky.rgb : cloudBin(bin);
                } else {
                    // Passing over by the band's trust: turning at once to a neighbour as the band
                    // went stale, and back as it returned, stepped the clouds' sky whenever walking
                    // bobbed a band at the top of the view in and out of it.
                    vec4 own = texelFetch(CalibrationRows, ivec2(bin, 2), 0);
                    vec4 other = nearest(bin, 2, 0.5, 1);
                    if (other.a <= 0.0) {
                        other = nearest(bin, 2, 1.0e-3, 0);
                    }
                    vec3 fallback = other.a > 0.0 ? other.rgb : vec3(1.0);
                    resolved = mix(fallback, own.rgb, own.a > 0.0 ? smoothstep(0.25, 0.75, own.a) : 0.0);
                }
                fragColor = vec4(resolved, 1.0);
            }
            """;

    // The sky the image shows, decoded, per band of elevation, from every pixel that shows sky:
    // what the air in front of distant land fades toward at that height. Eased toward from last
    // frame's, and kept while no sky shows in a band, its alpha falling below half in about a
    // second so readers prefer bands in view.
    // Four maps of the open sky per quarter-resolution texel, mipmapped and read coarse, so
    // the clouds' detail, which the image and the panorama draw differently, averages out.
    // Layer 2: how the image's clear sky differs from the atmosphere, in stops, over the dome's
    // clear sky; the residual row is one figure per band of elevation, and around it the
    // atmosphere ran from half to twice the image's sky, toward the moon and away, so the sky
    // drawn behind the edges of clouds showed as pale or dark rims. Layer 3: what the clear sky
    // shows beyond twice the atmosphere, the runtime's bloom: a torch beside the camera at night
    // brightened the sky around it many times over, and taken as an error of the atmosphere it
    // was cut off at the clouds. A sunset's sky, several times its band's mean toward the sun,
    // came out the same either way. Layers 0 and 1: what
    // the image shows over the dome's thick clouds beyond their light, and the atmosphere behind
    // them, the bloom where no clear sky is near. Alpha is the share of each texel to trust.
    private static final String GLOW_FRAGMENT_SHADER = """
            #version 330 core
            layout(location = 0) out vec4 overClouds;
            layout(location = 1) out vec4 skyBehind;
            layout(location = 2) out vec4 clearSky;
            layout(location = 3) out vec4 clearExcess;
            """ + CloudField.defines() + SCENE_SAMPLING + DEPTH_SAMPLING + VIEW_RAY + COLOUR_SPACE
            + PANORAMA_SAMPLING + ATMOSPHERE_SAMPLING + CLOUD_EXPOSURE + LAMP_GLOW + SKY_LIGHT_NEAR + """

            // How far the image's clear sky is taken to differ from the atmosphere, in stops: a
            // star or the moon takes in a texel no more than the lower bound, and what lies above
            // the upper one is bloom.
            const float MIN_CLEAR_STOPS = -2.0;
            const float MAX_CLEAR_STOPS = 1.0;

            void main() {
                vec2 uv = gl_FragCoord.xy / ViewportSize;
                // Four linear reads between the four by four pixels the texel covers.
                vec2 pixel = 1.0 / ScreenSize;
                vec3 image = vec3(0.0);
                bool sky = true;
                for (int i = 0; i < 4; i++) {
                    vec2 at = uv + vec2((i & 1) == 0 ? -1.0 : 1.0, (i & 2) == 0 ? -1.0 : 1.0) * pixel;
                    image += srgbToLinear(scene(at)) * 0.25;
                    sky = sky && isSky(distanceAt(at));
                }
                vec4 shown = shownClouds(uv);
                vec3 direction = normalize(viewRay(uv));
                vec3 atmosphere = atmosphereAt(direction);
                vec3 modelled = skyExposure(atmosphere) * atmosphere * skyResidual(direction.y);
                float thick = sky ? smoothstep(0.02, 0.1, shown.a) : 0.0;
                float clear = sky && shown.a < 0.02 ? 1.0 : 0.0;
                vec3 stops = clamp(log2(max(image, vec3(1.0e-6)) / max(modelled, vec3(1.0e-6))),
                    MIN_CLEAR_STOPS, MAX_CLEAR_STOPS);
                overClouds = vec4((image - cloudLight(shown)) * thick, thick);
                skyBehind = vec4((1.0 - shown.a) * modelled * thick, thick);
                float measured = skyLightNear(direction) ? 0.0 : clear * (1.0 - lampGlow(uv));
                clearSky = vec4(stops * measured, measured);
                clearExcess = vec4(max(image - modelled * exp2(stops * (1.0 - lampGlow(uv))), 0.0) * clear, clear);
            }
            """;

    private static final String SKY_BANDS_FRAGMENT_SHADER = """
            #version 330 core
            out vec4 fragColor;
            """ + "const int BANDS = " + SKY_BANDS + ";\n" + SAMPLE_READING + """
            uniform sampler2D SkyBandsPrevious;
            uniform float Follow;

            void main() {
                int band = int(gl_FragCoord.x);
                vec3 sum = vec3(0.0);
                float count = 0.0;
                for (int y = 0; y < SAMPLE_ROWS; y++) {
                    for (int x = 0; x < SAMPLE_COLUMNS; x++) {
                        vec4 image = texelFetch(Samples, ivec2(x, y), 0);
                        if (image.a > 0.0
                                && clamp(int(((image.a - 2.0) * 0.5 + 0.5) * float(BANDS)), 0, BANDS - 1) == band) {
                            sum += image.rgb;
                            count += 1.0;
                        }
                    }
                }
                vec4 previous = texelFetch(SkyBandsPrevious, ivec2(band, 0), 0);
                if (count < 1.0) {
                    // Follow is about the frame's seconds over CALIBRATION_SECONDS, half a
                    // second, so the alpha falls by a half each second.
                    fragColor = vec4(previous.rgb, previous.a > 0.0 ? max(previous.a - 0.25 * Follow, 0.01) : 0.0);
                    return;
                }
                // A band back in view after it went stale takes the sky it shows at once, and
                // its trust climbs back as fast as it fell, so readers pass over to it gradually.
                vec3 current = sum / count;
                fragColor = vec4(previous.a >= 0.5 ? mix(previous.rgb, current, Follow) : current,
                    min(max(previous.a, 0.0) + 0.25 * Follow, 1.0));
            }
            """;

    // The light each flare source takes, one texel a source, once a frame rather than in every
    // pixel of the composite: the sun's eased light, and a lamp's own brightness in the image
    // where it lands, or none where the image does not show it there.
    private static final String SOURCE_LIGHT_FRAGMENT_SHADER = """
            #version 330 core
            out vec4 fragColor;
            """ + "const int MAX_SOURCES = " + MAX_SOURCES + ";\n" + DEPTH_SAMPLING + """
            uniform sampler2D Bright;
            uniform sampler2D SunLight;
            uniform vec2 ViewportSize;
            uniform vec4 Sources[MAX_SOURCES];
            uniform int SourceCount;

            vec3 bright(vec2 uv, float lod) {
                return textureLod(Bright, uv, lod).rgb;
            }

            // How bright the image is where a light lands: the largest of a few taps around it, so
            // a light behind a block, or too dim against the daylight, does not flare.
            vec3 lightAt(vec2 uv) {
                vec2 step = 4.0 / ViewportSize;
                vec3 b = bright(uv, 1.0);
                b = max(b, bright(uv + vec2(step.x, 0.0), 1.0));
                b = max(b, bright(uv - vec2(step.x, 0.0), 1.0));
                b = max(b, bright(uv + vec2(0.0, step.y), 1.0));
                return max(b, bright(uv - vec2(0.0, step.y), 1.0));
            }

            // Whether the image shows the lamp itself around where a light lands: something at the
            // light's own distance. Nearer, a block hides it; farther, the lamp is not drawn there,
            // as with the hand hidden, and only what lies behind would be measured.
            bool showsLight(vec2 uv, float lightDistance) {
                vec2 step = 4.0 / ViewportSize;
                float tolerance = 0.6 + 0.1 * lightDistance;
                return abs(distanceAt(uv) - lightDistance) < tolerance
                    || abs(distanceAt(uv + vec2(step.x, 0.0)) - lightDistance) < tolerance
                    || abs(distanceAt(uv - vec2(step.x, 0.0)) - lightDistance) < tolerance
                    || abs(distanceAt(uv + vec2(0.0, step.y)) - lightDistance) < tolerance
                    || abs(distanceAt(uv - vec2(0.0, step.y)) - lightDistance) < tolerance;
            }

            void main() {
                int i = int(gl_FragCoord.x);
                vec4 source = i < SourceCount ? Sources[i] : vec4(0.0);
                if (i >= SourceCount || (source.z < 0.5 && !showsLight(source.xy, source.w))) {
                    fragColor = vec4(0.0);
                    return;
                }
                // The sun stands in a bright sky, so its own glow decides the strength, as far
                // as the rays toward it found the world open and the depth around it shows sky.
                vec3 light = abs(source.z - 1.0) < 0.5 ? texelFetch(SunLight, ivec2(0), 0).rgb * source.w
                    : lightAt(source.xy);
                fragColor = vec4(light, 1.0);
            }
            """;

    // The light the sun's flare and starburst take, in one texel: the bright image over a small
    // patch around the sun, times the share of that patch the depth shows as sky. Where the sun
    // stands at the edge of land, the depth, jittered for the upscaler, showed sky in a few
    // more or fewer of the patch's samples each frame, and the flare filling the top of the
    // screen flickered with it; more samples and a few frames' easing settle it.
    private static final String SUN_LIGHT_FRAGMENT_SHADER = """
            #version 330 core
            out vec4 fragColor;
            """ + DEPTH_SAMPLING + """
            uniform sampler2D Bright;
            uniform sampler2D SunLightPrevious;
            uniform vec2 SunUv;
            uniform float Aspect;
            uniform float Follow;
            uniform float HistoryValid;

            void main() {
                float open = 0.0;
                for (int y = -3; y <= 3; y++) {
                    for (int x = -3; x <= 3; x++) {
                        open += isSky(distanceAt(SunUv + vec2(float(x) / Aspect, float(y)) * 0.0015)) ? 1.0 : 0.0;
                    }
                }
                vec3 current = textureLod(Bright, SunUv, 3.0).rgb * (open / 49.0);
                vec3 previous = texelFetch(SunLightPrevious, ivec2(0), 0).rgb;
                fragColor = vec4(HistoryValid > 0.0 ? mix(previous, current, Follow) : current, 1.0);
            }
            """;

    private static final String BRIGHT_FRAGMENT_SHADER = """
            #version 330 core
            out vec4 fragColor;
            """ + SCENE_SAMPLING + """

            // Only what the tone mapper nearly saturated flares, as only a lens's brightest sources do.
            const float THRESHOLD = 0.8;

            void main() {
                // Each half-resolution texel centre falls between four scene texels, which the
                // linear filter averages.
                vec3 colour = scene(gl_FragCoord.xy / ViewportSize);
                fragColor = vec4(max(colour - THRESHOLD, 0.0) / (1.0 - THRESHOLD), 1.0);
            }
            """;

    // The sun mirrored on water and puddles, for the lens's glow and streaks: per
    // quarter-resolution texel, the brightest of the four by four scene pixels it covers, kept
    // where the tone mapper nearly saturated it and a level surface there mirrors the sun: the
    // view ray turned back up off the horizontal comes within a few degrees of it. That light
    // glows; alpha says how far it stands out from the pixels around it, which only a glint,
    // a point, does and a sunlit white floor or the broad sheen under the sun does not, and
    // only that much of it streaks. The bright pass's averaged texels let no single-pixel
    // glint through. The image is display referred, so this goes by look rather than radiance.
    private static final String GLINT_FRAGMENT_SHADER = """
            #version 330 core
            out vec4 fragColor;
            """ + SCENE_SAMPLING + DEPTH_SAMPLING + VIEW_RAY + """
            uniform vec2 ScreenSize;
            uniform vec3 SunDirection;

            const float THRESHOLD = 0.85;
            const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
            const float INNER = radians(3.0);
            const float OUTER = radians(16.0);

            void main() {
                vec2 texel = 1.0 / ScreenSize;
                vec2 corner = floor(gl_FragCoord.xy) * 4.0 * texel;
                vec3 brightest = vec3(0.0);
                vec2 brightestUv = corner;
                float peak = 0.0;
                float total = 0.0;
                for (int y = 0; y < 4; y++) {
                    for (int x = 0; x < 4; x++) {
                        vec2 uv = corner + (vec2(x, y) + 0.5) * texel;
                        vec3 colour = scene(uv);
                        float luminance = dot(colour, LUMA);
                        total += luminance;
                        if (luminance > peak) {
                            peak = luminance;
                            brightest = colour;
                            brightestUv = uv;
                        }
                    }
                }
                float strength = clamp((peak - THRESHOLD) / (1.0 - THRESHOLD), 0.0, 1.0);
                if (strength <= 0.0 || isSky(distanceAt(brightestUv))) {
                    fragColor = vec4(0.0);
                    return;
                }
                vec3 ray = normalize(viewRay(brightestUv));
                float angle = acos(clamp(dot(vec3(ray.x, -ray.y, ray.z), SunDirection), -1.0, 1.0));
                strength *= (1.0 - smoothstep(INNER, OUTER, angle)) * step(ray.y, 0.0);
                fragColor = vec4(brightest * strength, smoothstep(0.05, 0.2, peak - total / 16.0));
            }
            """;

    // The glints drawn out into six rays, like the sun's starburst from the same lens: three
    // lines through each, fading with distance (Kawase 2003's streaks, gathered in one pass).
    private static final String STREAK_FRAGMENT_SHADER = """
            #version 330 core
            out vec4 fragColor;
            uniform sampler2D Glints;
            uniform vec2 ViewportSize;

            const int TAPS = 10;
            const float FALLOFF = 0.84;
            const float SPACING = 1.5;
            const float PI = 3.14159265358979323846;

            void main() {
                vec2 texel = 1.0 / ViewportSize;
                vec2 uv = gl_FragCoord.xy * texel;
                vec3 sum = vec3(0.0);
                for (int axis = 0; axis < 3; axis++) {
                    float angle = float(axis) * PI / 3.0;
                    vec2 stride = vec2(cos(angle), sin(angle)) * SPACING * texel;
                    float weight = 1.0;
                    for (int i = 1; i <= TAPS; i++) {
                        weight *= FALLOFF;
                        vec4 ahead = textureLod(Glints, uv + stride * float(i), 0.0);
                        vec4 behind = textureLod(Glints, uv - stride * float(i), 0.0);
                        sum += (ahead.rgb * ahead.a + behind.rgb * behind.a) * weight;
                    }
                }
                fragColor = vec4(sum, 1.0);
            }
            """;

    // The glints' streaks and a small glow around each, added over the finished image.
    private static final String GLINT_BLEND_FRAGMENT_SHADER = """
            #version 330 core
            out vec4 fragColor;
            uniform sampler2D Glints;
            uniform sampler2D Streaks;
            uniform vec2 ViewportSize;
            uniform float Strength;

            const float STREAK_GAIN = 0.35;
            const float GLOW_GAIN = 0.45;

            void main() {
                vec2 uv = gl_FragCoord.xy / ViewportSize;
                vec3 glow = textureLod(Glints, uv, 1.0).rgb * 0.5 + textureLod(Glints, uv, 2.0).rgb * 0.3
                    + textureLod(Glints, uv, 3.0).rgb * 0.2;
                vec3 light = (texture(Streaks, uv).rgb * STREAK_GAIN + glow * GLOW_GAIN) * Strength;
                // A soft limit, so a sea full of glints brightens without going flat white.
                fragColor = vec4(light / (1.0 + light), 1.0);
            }
            """;

    // The water around the camera as the caustics read it.
    private static final String WATER_COLUMN_READING = "const int COLUMNS = " + WaterColumns.SIZE + ";\n"
            + "const int COLUMN_SHIFT = " + Integer.numberOfTrailingZeros(WaterColumns.SIZE) + ";\n" + """
            // A column a texel in rows (WaterColumns): the water's top, the floor's top, the top
            // of what shades the column from the sun, and the column's key.
            uniform samplerBuffer WaterColumns;
            // The camera's block, so a column is found exactly however far the world reaches.
            uniform ivec3 CameraBlock;
            uniform vec3 SunDirection;
            uniform float WaterIor;

            const float NO_WATER_BELOW = -1000.0;

            // A column's texel, or zero when the window holds no measure of that column.
            vec4 waterColumn(ivec2 block) {
                ivec2 offset = block - CameraBlock.xz;
                if (abs(offset.x) >= COLUMNS / 2 - 1 || abs(offset.y) >= COLUMNS / 2 - 1) {
                    return vec4(0.0);
                }
                ivec2 texel = block & (COLUMNS - 1);
                vec4 column = texelFetch(WaterColumns, texel.y * COLUMNS + texel.x);
                float key = float((((block.x >> COLUMN_SHIFT) & 255) << 8 | ((block.y >> COLUMN_SHIFT) & 255)) + 1);
                return column.a == key ? column : vec4(0.0);
            }
            """;

    // The waves' slope at every point of the caustic grid over one period, once a frame for the
    // traces at every depth to read.
    private static final String CAUSTIC_SLOPE_FRAGMENT_SHADER = """
            #version 330 core
            out vec4 fragColor;
            """ + "const int CAUSTIC_WAVES = " + CAUSTIC_WAVES + ";\n"
            + "const float CAUSTIC_CELLS = " + CAUSTIC_CELLS + ".0;\n" + """
            // Each wave's wave vector, in radians per block, its height in blocks, and its phase
            // at the period's corner now.
            uniform vec4 Waves[CAUSTIC_WAVES];

            void main() {
                vec2 point = (gl_FragCoord.xy - 0.5) / CAUSTIC_CELLS;
                vec2 slope = vec2(0.0);
                for (int i = 0; i < CAUSTIC_WAVES; i++) {
                    vec4 wave = Waves[i];
                    slope += wave.xy * (wave.z * cos(dot(wave.xy, point) + wave.w));
                }
                fragColor = vec4(slope, 0.0, 1.0);
            }
            """;

    /**
     * The caustics traced from the waves at one depth: a grid over one period of the surface,
     * each point of it tilted by the slope of the waves there, bending the sunlight down to a
     * floor that deep. Drawn where that light lands, less where flat water would land it, each
     * triangle spreads the light of the surface it covered over the floor it covers, so the
     * floor brightens where the waves gather light and darkens where they spread it, and where
     * folds of light cross they add up.
     */
    private static final String CAUSTIC_VERTEX_SHADER = """
            #version 330 core
            """ + "const int CAUSTIC_GRID = " + CAUSTIC_GRID + ";\n"
            + "const float CAUSTIC_CELLS = " + CAUSTIC_CELLS + ".0;\n"
            + "const float CAUSTIC_PERIOD = " + CAUSTIC_PERIOD + ".0;\n"
            + "const float CAUSTIC_MARGIN = " + CAUSTIC_MARGIN + ".0;\n" + """
            uniform sampler2D CausticSlopes;
            uniform vec3 SunDirection;
            uniform float WaterIor;
            // Blocks from the surface down to the floor this trace lights.
            uniform float Depth;
            // The point of the surface the vertex stands for, in blocks from the period's corner.
            out vec2 Source;

            void main() {
                // A strip of grid cells per instance, two vertices to a column.
                ivec2 point = ivec2(gl_VertexID >> 1, gl_InstanceID + (gl_VertexID & 1));
                vec2 slope = texelFetch(CausticSlopes, point % CAUSTIC_GRID, 0).xy;
                Source = vec2(point) / CAUSTIC_CELLS;
                vec3 bent = refract(-SunDirection, normalize(vec3(-slope.x, 1.0, -slope.y)), 1.0 / WaterIor);
                vec3 level = refract(-SunDirection, vec3(0.0, 1.0, 0.0), 1.0 / WaterIor);
                vec2 aside = bent.xz * (Depth / max(-bent.y, 0.05)) - level.xz * (Depth / max(-level.y, 0.05));
                vec2 landing = Source + clamp(aside, vec2(-CAUSTIC_MARGIN), vec2(CAUSTIC_MARGIN));
                gl_Position = vec4((landing + CAUSTIC_MARGIN) / (CAUSTIC_PERIOD + 2.0 * CAUSTIC_MARGIN) * 2.0 - 1.0,
                    0.0, 1.0);
            }
            """;

    private static final String CAUSTIC_FRAGMENT_SHADER = """
            #version 330 core
            """ + "const float CAUSTIC_TEXELS = " + CAUSTIC_TEXELS + ".0;\n" + """
            in vec2 Source;
            out vec4 fragColor;

            void main() {
                // The surface whose light lands on this texel, against the texel's own area: one
                // under flat water. A triangle maps linearly, so its derivatives are exact.
                vec2 x = dFdx(Source);
                vec2 y = dFdy(Source);
                float gathered = abs(x.x * y.y - x.y * y.x) * (CAUSTIC_TEXELS * CAUSTIC_TEXELS);
                fragColor = vec4(min(gathered, 64.0), 0.0, 0.0, 1.0);
            }
            """;

    // One depth's map over the period: the trace folded back onto it, as light carried over an
    // edge of the period lands at the same place of the next one.
    private static final String CAUSTIC_FOLD_FRAGMENT_SHADER = """
            #version 330 core
            out vec4 fragColor;
            """ + "const int CAUSTIC_MAP_SIZE = " + CAUSTIC_MAP_SIZE + ";\n"
            + "const int CAUSTIC_BORDER = " + CAUSTIC_MARGIN * CAUSTIC_TEXELS + ";\n" + """
            uniform sampler2D CausticTrace;

            void main() {
                ivec2 texel = ivec2(gl_FragCoord.xy) + CAUSTIC_BORDER;
                ivec2 size = textureSize(CausticTrace, 0);
                float light = 0.0;
                for (int y = -1; y <= 1; y++) {
                    for (int x = -1; x <= 1; x++) {
                        ivec2 at = texel + ivec2(x, y) * CAUSTIC_MAP_SIZE;
                        if (all(greaterThanEqual(at, ivec2(0))) && all(lessThan(at, size))) {
                            light += texelFetch(CausticTrace, at, 0).r;
                        }
                    }
                }
                fragColor = vec4(light, 0.0, 0.0, 1.0);
            }
            """;

    private static final String COMPOSITE_FRAGMENT_SHADER = """
            #version 330 core
            out vec4 fragColor;
            """ + "const int MAX_SOURCES = " + MAX_SOURCES + ";\n" + BAND_READING
            + CloudField.defines() + SCENE_SAMPLING + DEPTH_SAMPLING + VIEW_RAY + LAND_DISTANCE + HAZE_DENSITY
            + COLOUR_SPACE + CLOUD_FIELD + PANORAMA_SAMPLING + ATMOSPHERE_SAMPLING + CLOUD_EXPOSURE + WATER_COLUMN_READING
            + "const float CAUSTIC_PERIOD = " + CAUSTIC_PERIOD + ".0;\n"
            + "const int CAUSTIC_LAYERS = " + CAUSTIC_LAYERS + ";\n"
            + "const float CAUSTIC_FIRST_DEPTH = " + CAUSTIC_FIRST_DEPTH + ";\n"
            + "const float CAUSTIC_MAX_DEPTH = " + (WaterColumns.MAX_DEPTH - 1) + ".0;\n" + """

            uniform sampler2D HorizonSky;
            uniform sampler2D CloudLight;
            uniform sampler2D CloudDistance;
            uniform sampler2D SkyBands;
            uniform vec2 CloudSize;
            uniform float Clouds;
            // 1 while the eye is under water, where the sky and its clouds show through the waves.
            uniform float Submerged;
            // How far the clear sky overhead is deepened back toward its blue, 0 for none.
            uniform float SkyDepth;
            // The sine of the sun's elevation.
            uniform float SunHeight;
            // How dark the clouds' shadows on the land are, 0 to 1.
            uniform float CloudShadow;
            uniform float FocusDistance;
            uniform float DepthOfField;
            uniform float Flare;
            uniform float Aspect;
            // Each light that may flare: where it lands on screen, in z 1 for the sun and 2 for a
            // flame in the player's hand, and in w its distance along the view axis, at which the
            // image must show something there.
            uniform vec4 Sources[MAX_SOURCES];
            // Each source's light, none where it is not shown, from the source pass.
            uniform sampler2D SourceLights;
            uniform int SourceCount;
            // How strongly the floors of shallow water show caustics, 0 for none: the setting
            // times the share of the light that comes straight from the sun.
            uniform float Caustics;
            // The caustic pass's maps of one period of floor, a layer per depth, mipmapped: the
            // sunlight the waves land there against what flat water lands.
            uniform sampler2DArray CausticMaps;
            // Where the camera's block falls in the caustics' period.
            uniform vec2 CausticOrigin;
            // Where in the camera's block the camera is.
            uniform vec3 CameraFraction;

            // Blur radius, as a share of the screen height, per dioptre the distance is out of
            // focus, and its cap: a light blur, a couple of pixels for the far distance at 1080p.
            // Twice this read as too strong at the default.
            const float BLUR_PER_DIOPTRE = 0.01;
            const float MAX_BLUR = 0.004;
            const int TAPS = 24;
            const float GOLDEN_ANGLE = 2.39996323;
            // Ghosts along the line from a light through the centre: where on it (0 at the light,
            // 1 at the centre), how large as a share of the screen height, and their tint.
            const int GHOSTS = 5;
            const float GHOST_AT[GHOSTS] = float[](0.45, 0.8, 1.25, 1.55, 1.95);
            const float GHOST_RADIUS[GHOSTS] = float[](0.025, 0.07, 0.04, 0.11, 0.055);
            const vec3 GHOST_TINT[GHOSTS] = vec3[](vec3(1.0, 0.7, 0.4), vec3(0.6, 1.0, 0.7),
                vec3(0.5, 0.7, 1.0), vec3(0.8, 0.6, 1.0), vec3(1.0, 0.8, 0.5));
            const float GHOST_GAIN = 0.05;
            const float RING_AT = 1.15;
            const float RING_RADIUS = 0.2;
            const float RING_WIDTH = 0.012;
            const float RING_GAIN = 0.06;
            const float GLOW_GAIN = 0.2;
            const float STAR_GAIN = 0.8;

            float blurRadius(float distance) {
                if (distance < HAND_DISTANCE) {
                    return 0.0;
                }
                float defocus = abs(1.0 / FocusDistance - 1.0 / distance);
                return min(defocus * BLUR_PER_DIOPTRE, MAX_BLUR) * DepthOfField * ViewportSize.y;
            }

            vec3 depthOfField(vec2 uv) {
                vec3 centre = scene(uv);
                float distance = distanceAt(uv);
                float radius = blurRadius(distance);
                if (radius < 0.5) {
                    return centre;
                }
                vec3 sum = centre;
                float weight = 1.0;
                // A blur of a pixel or two, most of the distant view at the default, needs few
                // taps; all of them over 4K cost several milliseconds.
                int taps = radius < 1.5 ? TAPS / 3 : radius < 3.0 ? TAPS * 2 / 3 : TAPS;
                for (int i = 0; i < taps; i++) {
                    float r = sqrt((float(i) + 0.5) / float(taps)) * radius;
                    float angle = float(i) * GOLDEN_ANGLE;
                    vec2 sampleUv = uv + vec2(cos(angle), sin(angle)) * r / ViewportSize;
                    float sampleDistance = distanceAt(sampleUv);
                    // Something nearer and sharper than this pixel does not spread over it.
                    float w = sampleDistance < distance
                        ? smoothstep(r - 1.0, r + 1.0, blurRadius(sampleDistance))
                        : 1.0;
                    sum += scene(sampleUv) * w;
                    weight += w;
                }
                return sum / weight;
            }

            // A ghost is the aperture's image: a faint disc with a brighter rim.
            float ghost(float r, float radius) {
                float inside = 1.0 - smoothstep(radius * 0.93, radius, r);
                return inside * (0.35 + 0.65 * smoothstep(radius * 0.6, radius * 0.97, r));
            }

            vec3 spectrum(float t) {
                float hue = (1.0 - t) * 0.75;
                return clamp(abs(fract(hue + vec3(0.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0) - 1.0, 0.0, 1.0);
            }

            // Reflections between a lens's elements: ghosts along the line from the light through
            // the centre, their colours parted at the rims, a thin rainbow ring, and a glow on the
            // light itself. Only shapes, never a copy of the image, so nothing but lights flares.
            vec3 flareFrom(vec2 uv, vec2 lightUv, vec3 light) {
                vec2 scale = vec2(Aspect, 1.0);
                vec2 p = uv * scale;
                vec2 source = lightUv * scale;
                vec2 axis = vec2(0.5) * scale - source;
                vec3 sum = vec3(0.0);
                for (int i = 0; i < GHOSTS; i++) {
                    float r = length(p - (source + axis * GHOST_AT[i]));
                    float radius = GHOST_RADIUS[i];
                    vec3 fringed = vec3(ghost(r, radius * 1.02), ghost(r, radius), ghost(r, radius * 0.98));
                    sum += fringed * GHOST_TINT[i] * GHOST_GAIN;
                }
                float ring = (length(p - (source + axis * RING_AT)) - RING_RADIUS + RING_WIDTH) / (2.0 * RING_WIDTH);
                if (ring > 0.0 && ring < 1.0) {
                    sum += spectrum(1.0 - ring) * sin(PI * ring) * RING_GAIN;
                }
                sum += vec3(exp(-length(p - source) * 25.0) * GLOW_GAIN);
                return sum * light;
            }

            // Diffraction spikes off the aperture blades, six strong and six faint between them.
            vec3 starburst(vec2 uv, vec2 sunUv, vec3 sun) {
                vec2 offset = (uv - sunUv) * vec2(Aspect, 1.0);
                float r = length(offset);
                float angle = atan(offset.y, offset.x);
                float rays = pow(abs(cos(3.0 * angle)), 90.0) + 0.5 * pow(abs(cos(3.0 * angle + PI / 6.0)), 160.0);
                float star = rays * exp(-r * 6.0) + 0.5 * exp(-r * 30.0);
                return sun * star * STAR_GAIN;
            }

            vec4 horizonSky(float u) {
                return texture(HorizonSky, vec2(u, 0.5));
            }

            // The marched clouds at half resolution, gathered from the texels around this pixel
            // that see what it sees: over the land, the land texels, mostly those at its depth,
            // so the clouds neither bleed across a silhouette nor show the march's dither; over
            // the sky, the sky texels. No texel to take leaves weights at 0.
            vec4 gatherClouds(vec2 uv, float axial, bool sky, out float weights) {
                vec2 position = uv * CloudSize - 0.5;
                ivec2 corner = ivec2(floor(position));
                vec2 f = position - vec2(corner);
                ivec2 last = ivec2(CloudSize) - 1;
                float tolerance = 0.03 * axial + 0.3;
                vec4 sum = vec4(0.0);
                weights = 0.0;
                for (int y = -1; y <= 2; y++) {
                    for (int x = -1; x <= 2; x++) {
                        ivec2 texel = clamp(corner + ivec2(x, y), ivec2(0), last);
                        float texelDistance = texelFetch(CloudDistance, texel, 0).r;
                        if (isSky(texelDistance) != sky) {
                            continue;
                        }
                        vec2 d = vec2(x, y) - f;
                        float w = exp(-0.5 * dot(d, d));
                        if (!sky) {
                            // A floor under the depth weight, so a pixel no texel shares a depth
                            // with, a fence post say, still takes its neighbours' clouds.
                            w *= exp(-abs(texelDistance - axial) / tolerance) + 1.0e-3;
                        }
                        sum += texelFetch(CloudLight, texel, 0) * w;
                        weights += w;
                    }
                }
                return weights > 0.0 ? sum / weights : vec4(0.0);
            }

            // The distance haze and clouds take at a pixel: its own, or, for a sky pixel beside
            // the land, the farthest land beside it. The image is antialiased and the depth is
            // not, so along a silhouette a pixel shows part land and part sky while its depth,
            // jittered from frame to frame, says one or the other; a sky pixel left unhazed
            // there shows the land's own dark colour, a dark outline that crawls along every
            // distant ridge. The sky behind a ridge is nearly the haze's colour, so hazing its
            // share of the pixel too changes nothing visible.
            // The clouds in front of the land, at the brightness the dome shows its own clouds.
            vec3 cloudsOverLand(vec2 uv, vec3 colour, float axial) {
                float weights;
                vec4 cloud = gatherClouds(uv, axial, false, weights);
                if (cloud.a <= 1.0e-3) {
                    return colour;
                }
                return linearToSrgb(srgbToLinear(colour) * (1.0 - cloud.a) + cloudLight(cloud));
            }

            // The tone mapper's shoulder bleaches a bright sky toward white channel by channel, so
            // the blue overhead read nearly as pale as the horizon. Toward the zenith the sky's
            // colour is pushed out from its grey and dimmed a little, as the eye sees a clear
            // sky; the horizon, white from its long path through the air, is left as it is, and
            // so is rain's overcast. Clouds are drawn over it afterwards. Only colour that leans the
            // way the sky's band of elevation does is deepened: a lamp's orange glow over the night's
            // blue was pushed to red there and stopped at the skyline, where the land is not deepened.
            vec3 deepenSky(vec2 uv, vec3 colour) {
                float rise = normalize(viewRay(uv)).y;
                float depth = SkyDepth * smoothstep(0.05, 0.7, rise) * (1.0 - Rain);
                if (depth <= 0.0) {
                    return colour;
                }
                vec3 linear = srgbToLinear(colour);
                float luminance = dot(linear, LUMA);
                vec3 chroma = linear - luminance;
                vec4 band = skyBandAt(SkyBands, rise);
                vec3 skyChroma = band.rgb - dot(band.rgb, LUMA);
                float lengths = length(chroma) * length(skyChroma);
                if (band.a > 0.0 && lengths > 1.0e-8 && length(chroma) > 0.05 * luminance) {
                    depth *= smoothstep(0.3, 0.8, dot(chroma, skyChroma) / lengths);
                }
                linear = luminance * (1.0 - 0.3 * depth) + chroma * (1.0 + 1.2 * depth);
                return linearToSrgb(max(linear, vec3(0.0)));
            }

            // The glow pass's maps of the open sky, read coarse through four reads: 0 the image
            // over the dome's thick clouds less their light, 1 the atmosphere behind them, 2 how
            // the image's clear sky differs from the atmosphere, in stops, 3 what it shows beyond
            // that.
            uniform sampler2DArray Glow;
            const float GLOW_LEVEL = 3.0;
            // Up to this share of the light expected over the dome's clouds is taken for where
            // the image's clouds and the panorama's differ rather than for bloom.
            const float GLOW_FLOOR = 0.25;
            // How much brighter than the atmosphere brought to the clear sky around the sky read
            // through thin dome clouds may be.
            const float THIN_SKY_MARGIN = 1.1;
            // Toward the moon, with whether it is up; its halo is laid behind thick dome clouds
            // out to this angle.
            uniform vec4 Moon;
            const float MOON_HALO_FADE_COS = 0.9;
            // How far the marched clouds replace the dome's, 0 to 1: after a jump in the time of
            // day the image's own clouds show until the calibration has measured the new light.
            uniform float CloudsShown;

            vec4 glowMap(vec2 uv, float layer) {
                vec2 texel = 0.5 / vec2(textureSize(Glow, int(GLOW_LEVEL)).xy);
                return 0.25 * (textureLod(Glow, vec3(uv - texel, layer), GLOW_LEVEL)
                    + textureLod(Glow, vec3(uv + vec2(texel.x, -texel.y), layer), GLOW_LEVEL)
                    + textureLod(Glow, vec3(uv + vec2(-texel.x, texel.y), layer), GLOW_LEVEL)
                    + textureLod(Glow, vec3(uv + texel, layer), GLOW_LEVEL));
            }

            """ + LAMP_GLOW_READING + """

            // The marched clouds over the sky in place of the dome's, drawn over the sky behind
            // the dome's: read straight out of the image where the dome's clouds are thin, and
            // where they hide it, the atmosphere the dome light shows there, at the exposure the
            // calibration gives its brightness and deepened as the clear sky beside it is.
            // Nothing of the dome's clouds is kept. Its panorama speckles where a march grazes a
            // flat cloud base and the denoiser smooths that out of the image but keeps the
            // outlines, so the difference between the two drew grain over the bases of clouds
            // overhead and lines along their outlines. Rain closes the sky into a featureless
            // overcast the dome shows as well, and its lightning changes the light faster than
            // the dome follows, so rain hands the sky back to the dome.
            vec3 cloudsOverSky(vec2 uv, vec3 colour) {
                float weights;
                vec4 marched = gatherClouds(uv, Far, true, weights);
                if (weights <= 0.0 || Rain >= 1.0) {
                    return colour;
                }
                vec4 shown = shownClouds(uv);
                vec3 image = srgbToLinear(colour);
                // Dividing by what the dome's clouds let pass magnifies any error in their light,
                // which coloured the rims of sunset clouds magenta and yellow. A sky kept per band
                // of elevation instead held whatever it last saw, the day's white at the horizon
                // into the night, and spread the moon's glow and the lamps' bloom along its band,
                // which the thin edges of clouds showed as pale rims. Clear sky, the sun's disc in
                // it, is left as it is.
                vec3 direction = normalize(viewRay(uv));
                vec3 sky = atmosphereAt(direction);
                vec3 modelled = srgbToLinear(deepenSky(uv, linearToSrgb(skyExposure(sky) * sky * skyResidual(direction.y))));
                // The atmosphere brought to the image's clear sky around, as far as there is any.
                vec4 stops = glowMap(uv, 2.0);
                float clear = min(stops.a * 4.0, 1.0);
                vec3 local = stops.a > 1.0e-3 ? exp2(stops.rgb / stops.a * clear) : vec3(1.0);
                modelled *= local;
                // Bloom is the lens's, over everything, and the dome's clouds took it with them:
                // the glow of a lamp stopped at the skyline. It is taken out of the image before
                // the clouds are replaced and laid back over all of it afterwards, from what the
                // image shows beyond the clear sky and the dome's thick clouds it is expected to.
                // Put back only over the clouds, it cut a dark rim into the glow wherever thin
                // dome cloud stood beside them.
                vec4 glow = glowMap(uv, 0.0);
                vec4 excess = glowMap(uv, 3.0);
                vec3 overClouds = glow.a > 1.0e-3
                    ? max((glow.rgb - local * glowMap(uv, 1.0).rgb) / glow.a
                        - GLOW_FLOOR * (cloudLight(shown) + (1.0 - shown.a) * modelled), 0.0)
                    : vec3(0.0);
                float trust = glow.a + excess.a;
                float fill = trust > 1.0e-3 ? min(trust * 4.0, 1.0) / trust : 0.0;
                // What the clear sky shows beyond the atmosphere is a lamp's bloom only around the
                // lamp. Elsewhere it is the sky's own light, the moon's halo and the aurora, which
                // lies behind the clouds and stays in the sky read out of the image: laid over the
                // clouds coarse, or behind them where the dome hides it, it tinted whole clouds
                // green and purple toward the aurora and drew the moon's light through their edges
                // in blocks.
                float lamp = lampGlow(uv);
                vec3 skyLight = excess.rgb * fill * (1.0 - lamp);
                vec3 bloom = (overClouds * glow.a + excess.rgb * lamp) * fill;
                float thick = smoothstep(0.02, 0.1, shown.a);
                // Around a lamp, where the dome shows no thick cloud whose speckle would come
                // with it, the image's own glow is kept rather than the coarse one: laid back
                // coarse, a torch's glow spread into a wide pink ring over the sky.
                bloom = mix(bloom, max(image - cloudLight(shown) - (1.0 - shown.a) * modelled, 0.0),
                    lamp * (1.0 - thick));
                vec3 behind = max(image - bloom - cloudLight(shown), 0.0) / max(1.0 - shown.a, 1.0e-3);
                // Thin dome clouds' light that their calibration, measured on opaque cloud, does
                // not take out of the image stays in the sky read through them, and showed as
                // pale wisps and rims where the marched clouds have none. Clear sky keeps its stars.
                behind = mix(behind, min(behind, modelled * THIN_SKY_MARGIN), smoothstep(0.005, 0.02, shown.a));
                // The moon's halo is smooth and runs on behind the dome's thick clouds, where the
                // image cannot show it: without it the halo stopped at the outlines of the dome's
                // holes, which the marched clouds do not share, and lit their edges in blocks.
                float halo = Moon.w * smoothstep(MOON_HALO_FADE_COS, 1.0, dot(direction, Moon.xyz));
                behind = mix(behind, modelled + skyLight * halo, thick);
                vec3 replaced = cloudLight(marched) + (1.0 - marched.a) * behind + bloom;
                // Where neither has a cloud the image stays as it is, even where the coarse bloom
                // reads brighter than the sky it lies on.
                float change = smoothstep(0.0, 0.02, max(marched.a, shown.a));
                return mix(colour, linearToSrgb(replaced), change * (1.0 - Rain) * CloudsShown);
            }

            // The haze's share at each pixel and the share of it that shows sky, settled over
            // frames by the haze pass.
            uniform sampler2D HazeAmount;

            float skyShareHere() {
                return texelFetch(HazeAmount, ivec2(gl_FragCoord.xy), 0).g;
            }

            // The land in the clouds' shadows: the light the clouds above stop, times the share of
            // the land's light, per channel, that comes straight from the sun or moon rather than
            // the sky, which falls as the light sinks. At the setting's default a full shadow
            // keeps the sky's blue share and a little bounce, as dark as Remix's own shadow of a
            // block. Your hand lies in the shadow you stand in.
            const float SHADOW_DEPTH_AT_DEFAULT = 0.8;
            // How far the shadow's tint is taken toward grey. The share of direct light is warm,
            // so taking it alone left the sky's blue and every shadow read cold; under a real
            // cloud the light it scatters, white, makes up much of what the sun's beam lost.
            const float SHADOW_NEUTRAL = 0.6;

            vec3 cloudShadows(vec2 uv, vec3 colour, float axial) {
                if (isSky(axial) || CloudShadow <= 0.0) {
                    return colour;
                }
                vec3 ray = viewRay(uv);
                float along = axial < HAND_DISTANCE ? 0.0 : axial * length(ray);
                vec3 world = vec3(CameraXZ.x, CameraHeight, CameraXZ.y) + normalize(ray) * along;
                vec3 direct = LightIrradiance * max(LightDirection.y, 0.0);
                vec3 share = direct / max(direct + AmbientTop, vec3(1.0e-4));
                // Settled over frames by the haze pass, which runs while the sky is in view.
                float transmittance = Submerged > 0.0 ? shadowTransmittance(world)
                    : texelFetch(HazeAmount, ivec2(gl_FragCoord.xy), 0).b;
                vec3 darkening = min(vec3(0.95), 2.0 * CloudShadow * SHADOW_DEPTH_AT_DEFAULT * share
                    * (1.0 - transmittance));
                darkening = mix(darkening, vec3(dot(darkening, vec3(0.2126, 0.7152, 0.0722))), SHADOW_NEUTRAL);
                // Only the land's share of a pixel: a sky pixel beside the land takes the land's
                // distance, and the sky darkened by the sunlight's share, which is warm, drew a
                // flickering blue line along distant ridges.
                darkening *= 1.0 - skyShareHere();
                // Nor a lamp's glow after sunset, which lies in front of the land: darkened with
                // it, the outlines of distant buildings showed through a torch's glow. By day the
                // glow is faint and the shadow is kept.
                darkening *= 1.0 - lampGlow(uv) * (1.0 - smoothstep(-0.05, 0.1, SunHeight));
                return linearToSrgb(srgbToLinear(colour) * (1.0 - darkening));
            }

            // How far a pixel lies in open sky, from the settled sky share of it and its
            // neighbours: 1 a couple of pixels clear of any silhouette, 0 on it. The depth decides
            // land or sky afresh each frame and its jitter moves a silhouette by a pixel, so the
            // clouds swapped in for the dome's along it, and the sky deepened, flickered on and
            // off there, and drew an outline of the dome's clouds around every ridge.
            float openSky() {
                ivec2 pixel = ivec2(gl_FragCoord.xy);
                ivec2 last = textureSize(HazeAmount, 0) - 1;
                float share = 1.0;
                for (int y = -1; y <= 1; y++) {
                    for (int x = -1; x <= 1; x++) {
                        share = min(share, texelFetch(HazeAmount, clamp(pixel + ivec2(x, y), ivec2(0), last), 0).g);
                    }
                }
                return smoothstep(0.75, 1.0, share);
            }

            // Over open sky the marched clouds replace the dome's; beside the land the image
            // keeps the dome's, with the clouds in front of the land drawn over it; between the
            // two they blend.
            vec3 clouds(vec2 uv, vec3 colour, float axial) {
                vec3 nearLand = isSky(axial) ? colour : cloudsOverLand(uv, colour, axial);
                float open = openSky();
                vec3 drawn = open > 0.0 ? mix(nearLand, cloudsOverSky(uv, colour), open) : nearLand;
                // A lamp's flame and glow lie in front of every cloud but carry the depth of what
                // lies behind, so around a lamp the clouds only brighten: drawn over, the flame of
                // a torch went out against the sky and distant land showed through its glow.
                return mix(drawn, max(drawn, colour), lampGlow(uv));
            }

            vec3 aerialHaze(vec2 uv, vec3 colour) {
                float amount = texelFetch(HazeAmount, ivec2(gl_FragCoord.xy), 0).r;
                vec4 direction = horizonSky(uv.x);
                if (amount <= 0.0 || direction.a <= 0.0) {
                    return colour;
                }
                vec4 band = skyBandAt(SkyBands, normalize(viewRay(uv)).y);
                if (band.a <= 0.0) {
                    return colour;
                }
                // Land fades toward the sky the image shows at its own height, in its direction. Light
                // from below that brightens the rain's fog near the ground has no depth, so it reads
                // as sky at the bottom of a column; fading toward each column's lowest sky, whole
                // towers rising out of it took its colour and glowed against the darker sky.
                vec3 fade = linearToSrgb(band.rgb * direction.rgb);
                vec3 hazed = mix(colour, fade, amount);
                // Around a lamp the haze only brightens. Its flame and bloom lie in front of the
                // land but carry the depth of whatever lies behind, and faded with distant land
                // they left its outline as a pale hole in the glow.
                return mix(hazed, max(hazed, colour), lampGlow(uv));
            }

            // Caustics: the web of light the waves of a shallow water gather the sun into on its
            // floor. The runtime refracts no light into caustics, so the floor's pixels are
            // brightened where the waves land more sunlight than flat water would and darkened
            // where they land less, by the maps the caustic pass traces from the waves.
            // How much further apart in refractive index the caustics' red and blue are than
            // green: twice what water parts them by, so the colour shows.
            const float CAUSTIC_DISPERSION = 0.006;
            // The sun's angular diameter, which spreads a line of light by as much per block of depth.
            const float SUN_DISC = 0.0093;

            // The light the waves land at a point of the floor, in blocks from the camera's block,
            // against flat water's of one, read over the floor a pixel covers (two vectors, in
            // blocks), between the maps of the depths on either side.
            float causticLight(vec2 floorPoint, float depth, vec2 acrossX, vec2 acrossY) {
                float scale = 1.0 / CAUSTIC_PERIOD;
                vec2 uv = (CausticOrigin + floorPoint) * scale;
                vec2 gradX = acrossX * scale;
                vec2 gradY = acrossY * scale;
                float layer = clamp((depth - CAUSTIC_FIRST_DEPTH), 0.0, float(CAUSTIC_LAYERS - 1));
                float below = floor(layer);
                float light = textureGrad(CausticMaps, vec3(uv, below), gradX, gradY).r;
                if (layer > below) {
                    float deeper = textureGrad(CausticMaps, vec3(uv, below + 1.0), gradX, gradY).r;
                    light = mix(light, deeper, layer - below);
                }
                // Shallower than the first map, the light has not yet gathered.
                return mix(1.0, light, clamp(depth / CAUSTIC_FIRST_DEPTH, 0.0, 1.0));
            }

            // The light the waves land at a point of the floor, per channel, against flat water's
            // of one, read over the floor a pixel covers so that distant floors settle to their
            // mean instead of showing the web as a pattern; the maps' filter is anisotropic, so
            // a floor seen edge on keeps its web across the view. The maps lie where the light
            // would land through flat water, so the floor is read back along the sun's way by
            // as much, and shorter wavelengths bend further and land nearer the sun, so the
            // web's lines part into colour.
            vec3 causticGain(vec2 floorPoint, float depth, vec2 acrossX, vec2 acrossY, float contrast) {
                float spread = depth * SUN_DISC;
                acrossX *= max(spread / max(length(acrossX), 1.0e-6), 1.0);
                acrossY *= max(spread / max(length(acrossY), 1.0e-6), 1.0);
                float s = length(SunDirection.xz);
                vec2 away = s > 1.0e-3 ? -SunDirection.xz / s : vec2(0.0);
                float lowIor = WaterIor - CAUSTIC_DISPERSION;
                float highIor = WaterIor + CAUSTIC_DISPERSION;
                float red = s / sqrt(lowIor * lowIor - s * s);
                float green = s / sqrt(WaterIor * WaterIor - s * s);
                float blue = s / sqrt(highIor * highIor - s * s);
                vec3 light = vec3(causticLight(floorPoint - away * (depth * red), depth, acrossX, acrossY),
                    causticLight(floorPoint - away * (depth * green), depth, acrossX, acrossY),
                    causticLight(floorPoint - away * (depth * blue), depth, acrossX, acrossY));
                return clamp(1.0 + contrast * (light - 1.0), vec3(0.0), vec3(1.0 + 4.0 * contrast));
            }

            // Nearer than this is the hand, drawn a tenth of its size in front of the eye. The
            // depth of field's distance, a block and a half, also took the floor at a wader's feet.
            const float CAUSTIC_HAND_DISTANCE = 0.25;

            vec3 caustics(vec2 uv, vec3 colour, float axial) {
                if (isSky(axial) || axial < CAUSTIC_HAND_DISTANCE) {
                    return colour;
                }
                vec3 ray = viewRay(uv);
                // From the corner of the camera's block, in blocks.
                vec3 point = CameraFraction + ray * axial;
                vec4 column = waterColumn(CameraBlock.xz + ivec2(floor(point.xz)));
                if (column.a <= 0.0 || column.r < NO_WATER_BELOW) {
                    return colour;
                }
                float height = float(CameraBlock.y) + point.y;
                float cameraHeight = float(CameraBlock.y) + CameraFraction.y;
                // The way the view reaches the floor, which a pixel's footprint lies along.
                vec3 arriving = normalize(ray);
                if (cameraHeight > column.r) {
                    // Seen from above, the depth is where the path through the water would reach
                    // unbent, as the runtime's surface replacement places the floor it shows. The
                    // floor that pixel shows is where the view bends to at the surface.
                    vec3 view = normalize(ray);
                    if (view.y > -1.0e-3 || height > column.r + 0.05) {
                        return colour;
                    }
                    vec3 crossing = CameraFraction + view * ((column.r - cameraHeight) / view.y);
                    vec4 entered = waterColumn(CameraBlock.xz + ivec2(floor(crossing.xz)));
                    vec3 bent = refract(view, vec3(0.0, 1.0, 0.0), 1.0 / WaterIor);
                    if (entered.a <= 0.0 || entered.r < NO_WATER_BELOW || bent.y > -1.0e-3) {
                        return colour;
                    }
                    // Column by column along the bent view until it meets the ground: a floor
                    // rising toward a shore, or the side of a column that stands higher, stops it
                    // before the floor it entered over.
                    ivec2 cell = ivec2(floor(crossing.xz));
                    vec2 across = 1.0 / max(abs(bent.xz), vec2(1.0e-6));
                    vec2 toward = vec2(bent.x >= 0.0 ? 1.0 : -1.0, bent.z >= 0.0 ? 1.0 : -1.0);
                    vec2 next = (vec2(cell) + max(toward, 0.0) - crossing.xz) * toward * across;
                    vec4 here = entered;
                    float t = -1.0;
                    for (int i = 0; i < 64; i++) {
                        float floorTop = here.r < NO_WATER_BELOW ? here.b : here.g;
                        float reaches = (floorTop - float(CameraBlock.y) - crossing.y) / bent.y;
                        float leaves = min(next.x, next.y);
                        if (reaches <= leaves) {
                            t = reaches;
                            break;
                        }
                        if (next.x < next.y) {
                            cell.x += int(toward.x);
                            next.x += across.x;
                        } else {
                            cell.y += int(toward.y);
                            next.y += across.y;
                        }
                        here = waterColumn(CameraBlock.xz + cell);
                        if (here.a <= 0.0) {
                            break;
                        }
                        float top = here.r < NO_WATER_BELOW ? here.b : here.g;
                        if (top - float(CameraBlock.y) > crossing.y + bent.y * leaves) {
                            t = leaves;
                            break;
                        }
                    }
                    if (t < 0.0) {
                        return colour;
                    }
                    point = crossing + bent * t;
                    column = entered;
                    height = float(CameraBlock.y) + point.y;
                    arriving = bent;
                }
                float depth = column.r - height;
                float edge = max(abs(point.x), abs(point.z));
                if (depth <= 0.0 || depth > CAUSTIC_MAX_DEPTH || edge > float(COLUMNS / 2) - 2.0) {
                    return colour;
                }
                // Back along the sunlight as the surface bent it, to where it entered the water.
                vec3 into = -refract(-SunDirection, vec3(0.0, 1.0, 0.0), 1.0 / WaterIor);
                vec3 entry = point + into * (depth / max(into.y, 0.05));
                // Nothing reaches a floor whose sunlight a column on the way up casts a shadow over.
                float stride = 1.0 / max(length(SunDirection.xz), 0.25);
                for (int i = 1; i <= 24; i++) {
                    vec3 q = entry + SunDirection * (float(i) * stride);
                    float qHeight = float(CameraBlock.y) + q.y;
                    if (qHeight > column.r + 48.0) {
                        break;
                    }
                    vec4 shade = waterColumn(CameraBlock.xz + ivec2(floor(q.xz)));
                    if (shade.a > 0.0 && shade.b > qHeight) {
                        return colour;
                    }
                }
                // Deep down the folds of many waves cross and the web greys out, all the way by
                // the deepest floor measured, so no depth draws a line where the web stops. Past
                // the columns measured the floor is unknown; that far out the web has long
                // settled to its mean.
                float contrast = Caustics * exp(-max(depth - 4.0, 0.0) / 6.0)
                    * (1.0 - smoothstep(CAUSTIC_MAX_DEPTH * 0.6, CAUSTIC_MAX_DEPTH, depth))
                    * (1.0 - smoothstep(float(COLUMNS / 2) - 24.0, float(COLUMNS / 2) - 2.0, edge));
                if (Clouds > 0.0) {
                    // A cloud's shadow takes the sunlight, and the web with it.
                    vec3 cloudFrame = vec3(CameraXZ.x, CameraHeight, CameraXZ.y) + entry - CameraFraction;
                    contrast *= shadowTransmittance(cloudFrame);
                }
                // The floor a pixel covers: the turn of the view from one pixel to the next, laid
                // over the path's length onto the level floor along the way the view reaches it.
                float reach = length(point - CameraFraction);
                float rayLength = length(ray);
                vec3 stepX = CameraRight * (2.0 * TanHalfFov.x / ViewportSize.x) / rayLength;
                vec3 stepY = CameraUp * (2.0 * TanHalfFov.y / ViewportSize.y) / rayLength;
                stepX -= arriving * dot(arriving, stepX);
                stepY -= arriving * dot(arriving, stepY);
                float down = min(arriving.y, -0.02);
                vec2 acrossX = reach * (stepX.xz - arriving.xz * (stepX.y / down));
                vec2 acrossY = reach * (stepY.xz - arriving.xz * (stepY.y / down));
                return linearToSrgb(srgbToLinear(colour)
                    * causticGain(point.xz, depth, acrossX, acrossY, contrast));
            }

            void main() {
                vec2 uv = gl_FragCoord.xy / ViewportSize;
                vec3 colour = DepthOfField > 0.0 ? depthOfField(uv) : scene(uv);
                float axial = Clouds > 0.0 || Haze > 0.0 || Caustics > 0.0 ? landDistance(uv) : 0.0;
                if (Clouds > 0.0) {
                    colour = cloudShadows(uv, colour, axial);
                }
                if (Caustics > 0.0) {
                    colour = caustics(uv, colour, axial);
                }
                if (Haze > 0.0) {
                    colour = aerialHaze(uv, colour);
                }
                if (Submerged <= 0.0) {
                    // Only the pixel's share of sky, settled over frames.
                    float skyShare = skyShareHere();
                    if (skyShare > 0.0) {
                        colour = mix(colour, deepenSky(uv, colour), skyShare);
                    }
                    if (Clouds > 0.0) {
                        colour = clouds(uv, colour, axial);
                    }
                }
                if (Flare > 0.0) {
                    vec3 flare = vec3(0.0);
                    for (int i = 0; i < SourceCount; i++) {
                        vec4 light = texelFetch(SourceLights, ivec2(i, 0), 0);
                        if (light.a <= 0.0) {
                            continue;
                        }
                        vec2 lightUv = Sources[i].xy;
                        if (abs(Sources[i].z - 1.0) < 0.5) {
                            flare += starburst(uv, lightUv, light.rgb);
                        }
                        flare += flareFrom(uv, lightUv, light.rgb);
                    }
                    colour += flare * Flare;
                }
                fragColor = vec4(colour, 1.0);
            }
            """;

    private final Matrix4f view = new Matrix4f();
    private final Matrix4f projection = new Matrix4f();
    private final Vector4f clip = new Vector4f();
    /** Per source: screen u, v, 1 for the sun, and padding, as the shader's Sources array. */
    private final float[] sources = new float[MAX_SOURCES * 4];
    private float aspect = 16.0f / 9.0f;
    private float rain;
    private boolean submerged;
    private float farPlane = 1000.0f;
    private int screenWidth = 1;
    private int screenHeight = 1;
    private double cameraX;
    private double cameraY;
    private double cameraZ;
    /** Camera forward, right and up, then the tangents of half the field of view across and up. */
    private final float[] cameraBasis = new float[9];
    private float tanHalfFovX;
    private float tanHalfFovY;
    private float focusDioptres = 1.0f / (float) MAX_FOCUS_DISTANCE;
    private final int[] previousViewport = new int[4];
    private final int[] previousSamplers = new int[UNIT_COUNT];
    private long lastFocusNanos;
    private final float[] sunDirection = new float[3];
    /** The share of the sun the world leaves open, eased over {@link #SUN_VISIBILITY_SECONDS}. */
    private float sunVisibility;
    private Program sunLightProgram;
    /** Ping-ponged, one texel each: the sun's light for its flare, eased over frames. */
    private final int[] sunLight = new int[2];
    private int sunLightCurrent;
    private boolean sunLightValid;
    private float sunLightFollow = 1.0f;
    private Program sourceLightProgram;
    /** One texel a flare source: its light, alpha 0 where the image does not show it. */
    private int sourceLights;
    private final WaterColumns waterColumns = new WaterColumns();
    private float waterIor = 1.2f;
    /** Which half of the caustic layers this frame traces. */
    private int causticParity;
    /** Per caustic wave, the four floats of the shader's Waves. */
    private final float[] waves = new float[CAUSTIC_WAVES * 4];
    private Program causticProgram;
    private Program causticSlopeProgram;
    private Program causticFoldProgram;
    /** The waves' slopes over the caustic grid, the trace of one depth, and every depth's map. */
    private int causticSlopes;
    private int causticTrace;
    private int causticMaps;
    /** One 2D view per layer of {@link #causticMaps}, so a frame mips only the layers it traced. */
    private final int[] causticLayerViews = new int[CAUSTIC_LAYERS];

    private Program brightProgram;
    private Program horizonProgram;
    private Program horizonFillProgram;
    private Program horizonSmoothProgram;
    private int horizonSmooth;
    private Program compositeProgram;
    private int horizonRaw;
    /** Ping-ponged, so a frame without sky can keep the last frame's colours. */
    private final int[] horizonFilled = new int[2];
    private int horizonCurrent;
    private Program skyBandsProgram;
    private Program samplesProgram;
    private int sampleScene;
    private int sampleClouds;
    private int sampleSky;
    private int sampleFramebuffer;
    private final int[] skyBands = new int[2];
    private int skyBandsCurrent;
    private Program cloudMarchProgram;
    private Program cloudCalibrationProgram;
    private int cloudTexture;
    private int cloudDistanceTexture;
    private int cloudFramebuffer;
    private Program cloudTemporalProgram;
    /** Ping-ponged: the accumulated clouds and the distances each texel saw them at. */
    private final int[] cloudHistory = new int[2];
    private final int[] historyDistance = new int[2];
    private int historyCurrent;
    private int temporalFramebuffer;
    private boolean historyValid;
    private int cloudFrame;
    private final Matrix4f viewProjection = new Matrix4f();
    private final Matrix4f previousViewProjection = new Matrix4f();
    private double previousCameraX;
    private double previousCameraY;
    private double previousCameraZ;
    private int cloudWidth;
    private int cloudHeight;
    private Program hazeTemporalProgram;
    /** Ping-ponged at the screen's size: the haze's share per pixel, settled over frames. */
    private final int[] hazeAmount = new int[2];
    private int hazeCurrent;

    private int hazeWidth;
    private int hazeHeight;
    private boolean hazeValid;
    private final Matrix4f hazePreviousViewProjection = new Matrix4f();
    private double hazePreviousX;
    private double hazePreviousY;
    private double hazePreviousZ;
    /** Ping-ponged, one row of bands each, so the clouds' brightness eases from last frame's. */
    private final int[] calibration = new int[2];
    private int calibrationCurrent;
    private Program calibrationResolveProgram;
    /** Which of the clear sky's calibration rows this frame measures. */
    private int calibrationPhase;
    /** The calibration's rows resolved for reading, as {@code CALIBRATION_RESOLVE_FRAGMENT_SHADER} leaves them. */
    private int resolvedCalibration;
    private float calibrationFollow = 1.0f;
    private int seenTimeJump = SkyTimeJump.generation();
    /**
     * Until then, after a jump in the time of day, the image still shows the old light: from
     * noon to midnight the runtime's land and dome clouds took about four seconds to darken,
     * while its clear sky changed at once.
     */
    private long skySettledNanos = Long.MIN_VALUE / 2;
    private static final long SKY_SETTLE_NANOS = 4_000_000_000L;
    /** Then the marched clouds fade back in over this long, while the calibration settles. */
    private static final long SKY_FADE_NANOS = 1_000_000_000L;
    private int vertexArray;
    private int framebuffer;
    private Program glintProgram;
    private Program streakProgram;
    private Program glintBlendProgram;
    /** Quarter resolution: the glints with their mips for the glow, and their streaks. */
    private int glintTexture;
    private int streakTexture;
    private int glintWidth;
    private int glintHeight;
    private Program glowProgram;
    /** Quarter resolution with mips, a layer per map of {@code GLOW_FRAGMENT_SHADER}. */
    private int glowTexture;
    private int glowFramebuffer;
    private int glowWidth;
    private int glowHeight;
    private int brightTexture;
    private int brightWidth;
    private int brightHeight;
    private int sceneSampler;
    private int depthSampler;
    private int brightSampler;
    private int panoramaSampler;
    private int atmosphereSampler;
    private int causticSampler;

    /**
     * Takes this frame's camera, its projection and the distance the lens focuses at, which
     * follows what the view rests on.
     *
     * @param sunDirection unit vector toward the sun; one pointing down has no flare
     */
    public void updateCamera(Camera camera, Level level, float fovYDegrees, float aspect, float farPlane,
                             float[] sunDirection) {
        this.aspect = aspect;
        submerged = camera.getFluidInCamera() == FogType.WATER;
        this.farPlane = farPlane;
        rain = level == null ? 0.0f : level.getRainLevel(1.0f);
        Vec3 position = camera.position();
        cameraX = position.x;
        cameraY = position.y;
        cameraZ = position.z;
        camera.getViewRotationMatrix(view);
        projection.setPerspective((float) Math.toRadians(fovYDegrees), aspect, NEAR_PLANE, farPlane);
        Vector3fc forward = camera.forwardVector();
        Vector3fc left = camera.leftVector();
        Vector3fc up = camera.upVector();
        cameraBasis[0] = forward.x(); cameraBasis[1] = forward.y(); cameraBasis[2] = forward.z();
        cameraBasis[3] = -left.x();   cameraBasis[4] = -left.y();   cameraBasis[5] = -left.z();
        cameraBasis[6] = up.x();      cameraBasis[7] = up.y();      cameraBasis[8] = up.z();
        tanHalfFovY = (float) Math.tan(Math.toRadians(fovYDegrees) * 0.5);
        tanHalfFovX = tanHalfFovY * aspect;
        System.arraycopy(sunDirection, 0, this.sunDirection, 0, 3);
        long now = System.nanoTime();
        float seconds = lastFocusNanos == 0L ? Float.POSITIVE_INFINITY : (now - lastFocusNanos) / 1.0e9f;
        updateSunVisibility(position, level, seconds);
        updateFocus(camera, level, now, seconds);
        calibrationFollow = 1.0f - (float) Math.exp(-seconds / CALIBRATION_SECONDS);
        sunLightFollow = 1.0f - (float) Math.exp(-seconds / SUN_LIGHT_SECONDS);
        if (level != null && LensLook.getCaustics() > 0.0f) {
            waterColumns.update(level, position.x, position.z);
        }
    }

    /** The refractive index the water surface renders with, which the caustics bend light by too. */
    public void setWaterIor(float ior) {
        waterIor = ior;
    }

    /**
     * How strongly the caustics show this frame: the setting, times the share of a floor's light
     * that comes straight from the sun, faded out as the sun sets and as rain comes; none with no
     * water measured around the camera, where tracing their maps cost a millisecond for nothing.
     */
    private float causticStrength() {
        float setting = LensLook.getCaustics();
        if (setting <= 0.0f || sunDirection[1] <= 0.0f || waterColumns.texture() == 0 || !waterColumns.anyWater()) {
            return 0.0f;
        }
        float share = DEFAULT_DIRECT_SHARE;
        if (CloudField.ready()) {
            float[] light = CloudField.light();
            float direct = luminance(light[3], light[4], light[5]) * Math.max(light[1], 0.0f);
            float ambient = luminance(light[6], light[7], light[8]);
            share = direct / Math.max(direct + ambient, 1.0e-4f);
        }
        float rise = Math.clamp((sunDirection[1] - 0.02f) / 0.13f, 0.0f, 1.0f);
        return setting * share * rise * rise * (3.0f - 2.0f * rise) * (1.0f - rain);
    }

    private static float luminance(float r, float g, float b) {
        return 0.2126f * r + 0.7152f * g + 0.0722f * b;
    }

    /** Projects the sun and this frame's flare lights onto the screen; returns how many land on it. */
    private int projectSources() {
        int count = 0;
        if (sunDirection[1] > 0.0f && sunVisibility > 1.0e-3f) {
            count = addSource(count, sunDirection[0], sunDirection[1], sunDirection[2], 0.0f, true, false);
        }
        for (int i = 0; i < FlareSources.count(); i++) {
            count = addSource(count, (float) (FlareSources.x(i) - cameraX), (float) (FlareSources.y(i) - cameraY),
                    (float) (FlareSources.z(i) - cameraZ), 1.0f, false, FlareSources.held(i));
        }
        return count;
    }

    private int addSource(int count, float x, float y, float z, float w, boolean sun, boolean held) {
        clip.set(x, y, z, w);
        view.transform(clip);
        projection.transform(clip);
        if (clip.w <= 0.0f || (!sun && clip.w > MAX_LIGHT_DISTANCE)) {
            return count;
        }
        float u = clip.x / clip.w * 0.5f + 0.5f;
        float v = clip.y / clip.w * 0.5f + 0.5f;
        if (u < 0.0f || u > 1.0f || v < 0.0f || v > 1.0f) {
            return count;
        }
        int at = count * 4;
        sources[at] = u;
        sources[at + 1] = v;
        sources[at + 2] = sun ? 1.0f : held ? 2.0f : 0.0f;
        sources[at + 3] = sun ? sunVisibility : clip.w;
        return count + 1;
    }

    private void updateSunVisibility(Vec3 eye, Level level, float seconds) {
        float target = 0.0f;
        if (level != null && sunDirection[1] > 0.0f) {
            float x = sunDirection[0];
            float y = sunDirection[1];
            float z = sunDirection[2];
            // Two directions across the sun: horizontal, then the one perpendicular to both.
            float flat = (float) Math.sqrt(x * x + z * z);
            float ax = flat > 1.0e-4f ? -z / flat : 1.0f;
            float az = flat > 1.0e-4f ? x / flat : 0.0f;
            float bx = y * az;
            float by = z * ax - x * az;
            float bz = -y * ax;
            int open = 0;
            for (int ray = 0; ray < 5; ray++) {
                float a = ray == 1 ? 1.0f : ray == 2 ? -1.0f : 0.0f;
                float b = ray == 3 ? 1.0f : ray == 4 ? -1.0f : 0.0f;
                double dx = x + (a * ax + b * bx) * SUN_RAY_SPREAD;
                double dy = y + b * by * SUN_RAY_SPREAD;
                double dz = z + (a * az + b * bz) * SUN_RAY_SPREAD;
                Vec3 end = eye.add(dx * SUN_RAY_LENGTH, dy * SUN_RAY_LENGTH, dz * SUN_RAY_LENGTH);
                // Fluids too: from under water the sun shows no flare.
                BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.VISUAL,
                        ClipContext.Fluid.ANY, CollisionContext.empty()));
                if (hit.getType() == HitResult.Type.MISS) {
                    open++;
                }
            }
            target = open / 5.0f;
        }
        float follow = Float.isInfinite(seconds) ? 1.0f : 1.0f - (float) Math.exp(-seconds / SUN_VISIBILITY_SECONDS);
        sunVisibility += (target - sunVisibility) * follow;
    }

    private void updateFocus(Camera camera, Level level, long now, float seconds) {
        double target = MAX_FOCUS_DISTANCE;
        if (level != null) {
            Vec3 eye = camera.position();
            Vector3fc forward = camera.forwardVector();
            Vec3 end = eye.add(forward.x() * MAX_FOCUS_DISTANCE, forward.y() * MAX_FOCUS_DISTANCE,
                    forward.z() * MAX_FOCUS_DISTANCE);
            BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.VISUAL,
                    ClipContext.Fluid.NONE, CollisionContext.empty()));
            if (hit.getType() != HitResult.Type.MISS) {
                target = Math.max(MIN_FOCUS_DISTANCE, hit.getLocation().distanceTo(eye));
            }
        }
        lastFocusNanos = now;
        // In dioptres, so a pull between near and far moves as evenly as a lens does.
        float follow = 1.0f - (float) Math.exp(-seconds / FOCUS_SECONDS);
        focusDioptres += ((float) (1.0 / target) - focusDioptres) * follow;
    }

    /**
     * Redraws the main target from {@code sceneColorTexture} with the lens applied, nothing when
     * every effect is off, and starts the next frame's list of lights that may flare. The list
     * is cleared here rather than with the camera, because the hand is captured before the
     * camera arrives and offers its flame then.
     */
    public void render(int sceneColorTexture, int depthTexture) {
        try {
            draw(sceneColorTexture, depthTexture);
        } finally {
            FlareSources.clear();
        }
    }

    private void draw(int sceneColorTexture, int depthTexture) {
        float depthOfField = LensLook.getDepthOfField();
        float flare = LensLook.getFlare();
        // Under water the sky shows through the waves, which the runtime refracts and a march
        // along the straight view cannot: the dome's clouds stay, and land fades into the water.
        float haze = submerged ? 0.0f : SkyLook.getAerialHaze();
        if (SkyTimeJump.generation() != seenTimeJump) {
            seenTimeJump = SkyTimeJump.generation();
            forgetSky();
        }
        boolean clouds = CloudField.ready() && !domeCloudsShown;
        boolean overClouds = clouds && !submerged;
        float caustics = causticStrength();
        if ((depthOfField <= 0.0f && flare <= 0.0f && haze <= 0.0f && !clouds && caustics <= 0.0f)
                || sceneColorTexture <= 0 || depthTexture <= 0) {
            return;
        }
        RenderTarget mainTarget = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        if (!(mainTarget.getColorTexture() instanceof GlTexture targetColor)) {
            return;
        }
        screenWidth = mainTarget.width;
        screenHeight = mainTarget.height;
        ensureObjects();
        ensureBrightTexture(Math.max(1, mainTarget.width / 2), Math.max(1, mainTarget.height / 2));
        if (overClouds) {
            ensureCloudTexture(brightWidth, brightHeight);
        }

        int previousProgram = GL45C.glGetInteger(GL45C.GL_CURRENT_PROGRAM);
        int previousVertexArray = GL45C.glGetInteger(GL45C.GL_VERTEX_ARRAY_BINDING);
        int previousDrawFramebuffer = GlStateManager.getFrameBuffer(GL45C.GL_DRAW_FRAMEBUFFER);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer viewport = stack.mallocInt(4);
            GL45C.glGetIntegerv(GL45C.GL_VIEWPORT, viewport);
            viewport.get(previousViewport);
        }
        for (int unit = 0; unit < UNIT_COUNT; unit++) {
            GlStateManager._activeTexture(GL45C.GL_TEXTURE0 + unit);
            previousSamplers[unit] = GL45C.glGetInteger(GL45C.GL_SAMPLER_BINDING);
        }

        try {
            GlStateManager._glBindFramebuffer(GL45C.GL_DRAW_FRAMEBUFFER, framebuffer);
            GlStateManager._disableDepthTest();
            GlStateManager._depthMask(false);
            GlStateManager._disableCull();
            GlStateManager._disableScissorTest();
            GlStateManager._colorMask(0xF);
            GlStateManager._disableBlend(0);
            GL45C.glBindVertexArray(vertexArray);
            bindTexture(UNIT_SCENE, sceneColorTexture, sceneSampler);
            int lampCount = projectSources();

            if (flare > 0.0f) {
                GL45C.glNamedFramebufferTexture(framebuffer, GL45C.GL_COLOR_ATTACHMENT0, brightTexture, 0);
                GlStateManager._viewport(0, 0, brightWidth, brightHeight);
                brightProgram.use();
                brightProgram.set("ViewportSize", brightWidth, brightHeight);
                GL45C.glDrawArrays(GL45C.GL_TRIANGLES, 0, 3);
                GL45C.glGenerateTextureMipmap(brightTexture);
            }

            boolean glints = flare > 0.0f && sunDirection[1] > 0.0f;
            if (glints) {
                ensureGlintTextures(Math.max(1, screenWidth / 4), Math.max(1, screenHeight / 4));
                bindTexture(UNIT_DEPTH, depthTexture, depthSampler);
                GL45C.glNamedFramebufferTexture(framebuffer, GL45C.GL_COLOR_ATTACHMENT0, glintTexture, 0);
                GlStateManager._viewport(0, 0, glintWidth, glintHeight);
                glintProgram.use();
                glintProgram.set("ViewportSize", glintWidth, glintHeight);
                setView(glintProgram);
                glintProgram.set("SunDirection", sunDirection[0], sunDirection[1], sunDirection[2]);
                GL45C.glDrawArrays(GL45C.GL_TRIANGLES, 0, 3);
                GL45C.glGenerateTextureMipmap(glintTexture);

                GL45C.glNamedFramebufferTexture(framebuffer, GL45C.GL_COLOR_ATTACHMENT0, streakTexture, 0);
                bindTexture(UNIT_BRIGHT, glintTexture, brightSampler);
                streakProgram.use();
                streakProgram.set("ViewportSize", glintWidth, glintHeight);
                GL45C.glDrawArrays(GL45C.GL_TRIANGLES, 0, 3);
            }

            if (haze > 0.0f || overClouds) {
                GlStateManager._glBindFramebuffer(GL45C.GL_DRAW_FRAMEBUFFER, sampleFramebuffer);
                GlStateManager._viewport(0, 0, SAMPLE_COLUMNS, SAMPLE_ROWS);
                bindTexture(UNIT_DEPTH, depthTexture, depthSampler);
                if (overClouds) {
                    bindTexture(UNIT_PANORAMA, CloudField.panoramaTexture(), panoramaSampler);
                    bindTexture(UNIT_ATMOSPHERE, CloudField.skyTexture(), atmosphereSampler);
                }
                samplesProgram.use();
                setView(samplesProgram);
                samplesProgram.set("Clouds", overClouds ? 1.0f : 0.0f);
                samplesProgram.set("AtmosphereRadiance", CloudField.skyRadiance());
                samplesProgram.set("Aspect", aspect);
                samplesProgram.setSources(sources, lampCount);
                setSkyLight(samplesProgram);
                GL45C.glDrawArrays(GL45C.GL_TRIANGLES, 0, 3);
                GlStateManager._glBindFramebuffer(GL45C.GL_DRAW_FRAMEBUFFER, framebuffer);
            }
            if (haze > 0.0f) {
                int next = 1 - skyBandsCurrent;
                GlStateManager._viewport(0, 0, SKY_BANDS, 1);
                GL45C.glNamedFramebufferTexture(framebuffer, GL45C.GL_COLOR_ATTACHMENT0, skyBands[next], 0);
                bindTexture(UNIT_SKY_BANDS, skyBands[skyBandsCurrent], depthSampler);
                bindTexture(UNIT_SAMPLES, sampleScene, depthSampler);
                skyBandsProgram.use();
                skyBandsProgram.set("Follow", calibrationFollow);
                GL45C.glDrawArrays(GL45C.GL_TRIANGLES, 0, 3);
                skyBandsCurrent = next;

                bindTexture(UNIT_DEPTH, depthTexture, depthSampler);
                GlStateManager._viewport(0, 0, HORIZON_BINS, 1);
                GL45C.glNamedFramebufferTexture(framebuffer, GL45C.GL_COLOR_ATTACHMENT0, horizonRaw, 0);
                bindTexture(UNIT_SKY_BANDS, skyBands[skyBandsCurrent], depthSampler);
                horizonProgram.use();
                horizonProgram.set("ViewportSize", HORIZON_BINS, 1);
                setView(horizonProgram);
                horizonProgram.set("Aspect", aspect);
                horizonProgram.setSources(sources, lampCount);
                GL45C.glDrawArrays(GL45C.GL_TRIANGLES, 0, 3);

                next = 1 - horizonCurrent;
                GL45C.glNamedFramebufferTexture(framebuffer, GL45C.GL_COLOR_ATTACHMENT0, horizonFilled[next], 0);
                bindTexture(UNIT_BRIGHT, horizonRaw, depthSampler);
                bindTexture(UNIT_HORIZON, horizonFilled[horizonCurrent], depthSampler);
                horizonFillProgram.use();
                GL45C.glDrawArrays(GL45C.GL_TRIANGLES, 0, 3);
                horizonCurrent = next;

                GL45C.glNamedFramebufferTexture(framebuffer, GL45C.GL_COLOR_ATTACHMENT0, horizonSmooth, 0);
                bindTexture(UNIT_BRIGHT, horizonFilled[horizonCurrent], depthSampler);
                horizonSmoothProgram.use();
                GL45C.glDrawArrays(GL45C.GL_TRIANGLES, 0, 3);
            }

            if (overClouds) {
                GlStateManager._glBindFramebuffer(GL45C.GL_DRAW_FRAMEBUFFER, cloudFramebuffer);
                GlStateManager._viewport(0, 0, cloudWidth, cloudHeight);
                bindTexture(UNIT_DEPTH, depthTexture, depthSampler);
                bindTexture(UNIT_CIRRUS, CloudField.cirrusTexture(), sceneSampler);
                // The backend tracks no 3D bindings, so this one is taken back below.
                GlStateManager._activeTexture(GL45C.GL_TEXTURE0 + UNIT_NOISE);
                GL45C.glBindTexture(GL45C.GL_TEXTURE_3D, CloudField.noiseTexture());
                GL45C.glBindSampler(UNIT_NOISE, 0);
                cloudMarchProgram.use();
                cloudMarchProgram.set("ViewportSize", cloudWidth, cloudHeight);
                cloudMarchProgram.set("FrameIndex", cloudFrame++ % 4096);
                setView(cloudMarchProgram);
                setCloudField(cloudMarchProgram);
                GL45C.glDrawArrays(GL45C.GL_TRIANGLES, 0, 3);
                foldIntoHistory();
                GlStateManager._glBindFramebuffer(GL45C.GL_DRAW_FRAMEBUFFER, framebuffer);

                int next = 1 - calibrationCurrent;
                GlStateManager._viewport(0, 0, CALIBRATION_BINS, 3);
                GL45C.glNamedFramebufferTexture(framebuffer, GL45C.GL_COLOR_ATTACHMENT0, calibration[next], 0);
                bindTexture(UNIT_CALIBRATION_ROWS, calibration[calibrationCurrent], depthSampler);
                bindTexture(UNIT_CALIBRATION, resolvedCalibration, sceneSampler);
                bindTexture(UNIT_SAMPLES, sampleScene, depthSampler);
                bindTexture(UNIT_SAMPLE_CLOUDS, sampleClouds, depthSampler);
                bindTexture(UNIT_SAMPLE_SKY, sampleSky, depthSampler);
                cloudCalibrationProgram.use();
                cloudCalibrationProgram.set("Follow", calibrationFollow);
                cloudCalibrationProgram.set("Phase", calibrationPhase);
                cloudCalibrationProgram.set("Measure", System.nanoTime() >= skySettledNanos ? 1.0f : 0.0f);
                cloudCalibrationProgram.set("RowFollow", 1.0f - (1.0f - calibrationFollow) * (1.0f - calibrationFollow));
                calibrationPhase = 1 - calibrationPhase;
                float[] light = CloudField.light();
                cloudCalibrationProgram.set("SkyLuminance", luminance(light[6], light[7], light[8]));
                GL45C.glDrawArrays(GL45C.GL_TRIANGLES, 0, 3);
                calibrationCurrent = next;

                GL45C.glNamedFramebufferTexture(framebuffer, GL45C.GL_COLOR_ATTACHMENT0, resolvedCalibration, 0);
                bindTexture(UNIT_CALIBRATION, 0, sceneSampler);
                bindTexture(UNIT_CALIBRATION_ROWS, calibration[calibrationCurrent], depthSampler);
                calibrationResolveProgram.use();
                GL45C.glDrawArrays(GL45C.GL_TRIANGLES, 0, 3);

                ensureGlowTexture(Math.max(1, screenWidth / 4), Math.max(1, screenHeight / 4));
                GlStateManager._glBindFramebuffer(GL45C.GL_DRAW_FRAMEBUFFER, glowFramebuffer);
                GlStateManager._viewport(0, 0, glowWidth, glowHeight);
                bindTexture(UNIT_DEPTH, depthTexture, depthSampler);
                bindTexture(UNIT_PANORAMA, CloudField.panoramaTexture(), panoramaSampler);
                bindTexture(UNIT_ATMOSPHERE, CloudField.skyTexture(), atmosphereSampler);
                bindTexture(UNIT_CALIBRATION, resolvedCalibration, sceneSampler);
                glowProgram.use();
                glowProgram.set("ViewportSize", glowWidth, glowHeight);
                setView(glowProgram);
                glowProgram.set("AtmosphereRadiance", CloudField.skyRadiance());
                glowProgram.set("Aspect", aspect);
                glowProgram.setSources(sources, lampCount);
                setSkyLight(glowProgram);
                GL45C.glDrawArrays(GL45C.GL_TRIANGLES, 0, 3);
                GL45C.glGenerateTextureMipmap(glowTexture);
                GlStateManager._glBindFramebuffer(GL45C.GL_DRAW_FRAMEBUFFER, framebuffer);
            }

            GL45C.glBindTextureUnit(UNIT_WATER_COLUMNS, waterColumns.texture());
            GL45C.glBindSampler(UNIT_WATER_COLUMNS, 0);
            if (caustics > 0.0f) {
                drawCausticMaps();
            }
            // The settled sky share decides where the sky's own effects apply, so it is kept
            // whenever the sky is in view, haze or not.
            if (!submerged) {
                settleHaze(depthTexture);
            }
            hazeValid = !submerged;
            int sourceCount = flare > 0.0f ? lampCount : 0;
            boolean sun = sourceCount > 0 && sources[2] == 1.0f;
            if (sun) {
                easeSunLight(depthTexture);
            }
            sunLightValid = sun;
            if (sourceCount > 0) {
                lightSources(depthTexture, sourceCount);
            }

            // Attached every frame: a resize recreates the target, and GL may hand a deleted
            // texture's name to its replacement.
            GL45C.glNamedFramebufferTexture(framebuffer, GL45C.GL_COLOR_ATTACHMENT0, targetColor.glId(), 0);
            GlStateManager._viewport(0, 0, mainTarget.width, mainTarget.height);
            bindTexture(UNIT_DEPTH, depthTexture, depthSampler);
            bindTexture(UNIT_BRIGHT, brightTexture, brightSampler);
            GL45C.glBindTextureUnit(UNIT_GLOW, overClouds ? glowTexture : 0);
            GL45C.glBindSampler(UNIT_GLOW, brightSampler);
            bindTexture(UNIT_HORIZON, horizonSmooth, sceneSampler);
            bindTexture(UNIT_CLOUDS, cloudHistory[historyCurrent], depthSampler);
            bindTexture(UNIT_CLOUD_DISTANCE, cloudDistanceTexture, depthSampler);
            bindTexture(UNIT_CALIBRATION, resolvedCalibration, sceneSampler);
            bindTexture(UNIT_PANORAMA, overClouds ? CloudField.panoramaTexture() : 0, panoramaSampler);
            bindTexture(UNIT_ATMOSPHERE, overClouds ? CloudField.skyTexture() : 0, atmosphereSampler);
            bindTexture(UNIT_SKY_BANDS, skyBands[skyBandsCurrent], depthSampler);
            GL45C.glBindTextureUnit(UNIT_CAUSTICS, caustics > 0.0f ? causticMaps : 0);
            GL45C.glBindSampler(UNIT_CAUSTICS, causticSampler);
            GL45C.glBindTextureUnit(UNIT_HAZE, !submerged ? hazeAmount[hazeCurrent] : 0);
            GL45C.glBindTextureUnit(UNIT_SOURCES, sourceLights);
            compositeProgram.use();
            compositeProgram.set("ViewportSize", mainTarget.width, mainTarget.height);
            setView(compositeProgram);
            compositeProgram.set("Clouds", clouds ? 1.0f : 0.0f);
            compositeProgram.set("Submerged", submerged ? 1.0f : 0.0f);
            compositeProgram.set("SunHeight", sunDirection[1]);
            if (clouds) {
                setCloudField(compositeProgram);
                compositeProgram.set("AtmosphereRadiance", CloudField.skyRadiance());
                compositeProgram.set("CloudShadow", CloudField.shadowStrength());
            }
            compositeProgram.set("CloudSize", cloudWidth, cloudHeight);
            compositeProgram.set("FocusDistance", 1.0f / focusDioptres);
            compositeProgram.set("DepthOfField", depthOfField);
            compositeProgram.set("Flare", flare);
            compositeProgram.set("Aspect", aspect);
            compositeProgram.set("Haze", haze);
            compositeProgram.set("Rain", rain);
            compositeProgram.set("SkyDepth", SKY_DEPTH_AT_DEFAULT * SkyLook.getSaturation());
            compositeProgram.set("CameraY", (float) cameraY);
            compositeProgram.set("Caustics", caustics);
            if (caustics > 0.0f) {
                setWater(compositeProgram);
                compositeProgram.set("CausticOrigin", (float) Math.floorMod((int) Math.floor(cameraX), CAUSTIC_PERIOD),
                        (float) Math.floorMod((int) Math.floor(cameraZ), CAUSTIC_PERIOD));
                compositeProgram.set("CameraFraction", (float) (cameraX - Math.floor(cameraX)),
                        (float) (cameraY - Math.floor(cameraY)), (float) (cameraZ - Math.floor(cameraZ)));
            }
            compositeProgram.setSources(sources, lampCount);
            setMoon(compositeProgram);
            compositeProgram.set("CloudsShown", Math.clamp(
                    (System.nanoTime() - skySettledNanos) / (float) SKY_FADE_NANOS, 0.0f, 1.0f));
            GL45C.glDrawArrays(GL45C.GL_TRIANGLES, 0, 3);
            historyValid = overClouds;

            if (glints) {
                bindTexture(UNIT_BRIGHT, glintTexture, brightSampler);
                bindTexture(UNIT_HORIZON, streakTexture, sceneSampler);
                GlStateManager._enableBlend(0);
                GlStateManager._blendFuncSeparate(GL45C.GL_ONE, GL45C.GL_ONE, GL45C.GL_ZERO, GL45C.GL_ONE);
                GlStateManager._blendEquationSeparate(GL45C.GL_FUNC_ADD, GL45C.GL_FUNC_ADD);
                glintBlendProgram.use();
                glintBlendProgram.set("ViewportSize", mainTarget.width, mainTarget.height);
                glintBlendProgram.set("Strength", flare);
                GL45C.glDrawArrays(GL45C.GL_TRIANGLES, 0, 3);
                GlStateManager._disableBlend(0);
            }
        } finally {
            GL45C.glBindTextureUnit(UNIT_WATER_COLUMNS, 0);
            GL45C.glBindTextureUnit(UNIT_CAUSTICS, 0);
            GL45C.glBindSampler(UNIT_CAUSTICS, 0);
            GL45C.glBindTextureUnit(UNIT_SOURCES, 0);
            GL45C.glBindTextureUnit(UNIT_GLOW, 0);
            GL45C.glBindSampler(UNIT_GLOW, 0);
            GlStateManager._activeTexture(GL45C.GL_TEXTURE0 + UNIT_NOISE);
            GL45C.glBindTexture(GL45C.GL_TEXTURE_3D, 0);
            for (int unit = 0; unit < UNIT_COUNT; unit++) {
                GL45C.glBindSampler(unit, previousSamplers[unit]);
            }
            GL45C.glBindVertexArray(previousVertexArray);
            GL45C.glUseProgram(previousProgram);
            GlStateManager._glBindFramebuffer(GL45C.GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer);
            GlStateManager._viewport(
                    previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3]);
        }
    }

    /**
     * Folds this frame's march into the clouds' history, in the march's targets' size; the
     * caller rebinds its framebuffer and viewport.
     */
    private void foldIntoHistory() {
        viewProjection.set(projection).mul(view);
        double motionX = cameraX - previousCameraX;
        double motionY = cameraY - previousCameraY;
        double motionZ = cameraZ - previousCameraZ;
        boolean valid = historyValid
                && motionX * motionX + motionY * motionY + motionZ * motionZ < HISTORY_RESET_DISTANCE * HISTORY_RESET_DISTANCE;
        int next = 1 - historyCurrent;
        GL45C.glNamedFramebufferTexture(temporalFramebuffer, GL45C.GL_COLOR_ATTACHMENT0, cloudHistory[next], 0);
        GL45C.glNamedFramebufferTexture(temporalFramebuffer, GL45C.GL_COLOR_ATTACHMENT1, historyDistance[next], 0);
        GlStateManager._glBindFramebuffer(GL45C.GL_DRAW_FRAMEBUFFER, temporalFramebuffer);
        bindTexture(UNIT_CLOUDS, cloudTexture, depthSampler);
        bindTexture(UNIT_CLOUD_DISTANCE, cloudDistanceTexture, depthSampler);
        bindTexture(UNIT_HISTORY, cloudHistory[historyCurrent], sceneSampler);
        bindTexture(UNIT_HISTORY_DISTANCE, historyDistance[historyCurrent], depthSampler);
        cloudTemporalProgram.use();
        cloudTemporalProgram.set("ViewportSize", cloudWidth, cloudHeight);
        setView(cloudTemporalProgram);
        cloudTemporalProgram.set("CameraMotion", (float) motionX, (float) motionY, (float) motionZ);
        // A lightning flash lasts a few frames: through the history it lagged and lingered.
        cloudTemporalProgram.set("HistoryValid", valid && !LightningFlash.changing() ? 1.0f : 0.0f);
        cloudTemporalProgram.setMatrix("PreviousViewProjection", previousViewProjection);
        GL45C.glDrawArrays(GL45C.GL_TRIANGLES, 0, 3);
        historyCurrent = next;
        previousViewProjection.set(viewProjection);
        previousCameraX = cameraX;
        previousCameraY = cameraY;
        previousCameraZ = cameraZ;
    }

    /**
     * Writes each flare source's light into {@code sourceLights}, once, rather than in every pixel
     * of the composite; the caller attaches its own target and viewport after. The sun's eased
     * light and the bright image must be ready.
     */
    private void lightSources(int depthTexture, int count) {
        GL45C.glNamedFramebufferTexture(framebuffer, GL45C.GL_COLOR_ATTACHMENT0, sourceLights, 0);
        GlStateManager._viewport(0, 0, MAX_SOURCES, 1);
        bindTexture(UNIT_DEPTH, depthTexture, depthSampler);
        bindTexture(UNIT_BRIGHT, brightTexture, brightSampler);
        GL45C.glBindTextureUnit(UNIT_SUN, sunLight[sunLightCurrent]);
        GL45C.glBindSampler(UNIT_SUN, depthSampler);
        sourceLightProgram.use();
        sourceLightProgram.set("Near", NEAR_PLANE);
        sourceLightProgram.set("Far", farPlane);
        sourceLightProgram.set("ViewportSize", screenWidth, screenHeight);
        sourceLightProgram.setSources(sources, count);
        GL45C.glDrawArrays(GL45C.GL_TRIANGLES, 0, 3);
    }

    /**
     * Eases the sun's light for its flare toward this frame's, from the first source, the sun;
     * the caller attaches its own target and viewport after. The bright image must be ready.
     */
    private void easeSunLight(int depthTexture) {
        int next = 1 - sunLightCurrent;
        GL45C.glNamedFramebufferTexture(framebuffer, GL45C.GL_COLOR_ATTACHMENT0, sunLight[next], 0);
        GlStateManager._viewport(0, 0, 1, 1);
        bindTexture(UNIT_DEPTH, depthTexture, depthSampler);
        bindTexture(UNIT_BRIGHT, brightTexture, brightSampler);
        // Past the units the game's state tracks, as the caustics' are.
        GL45C.glBindTextureUnit(UNIT_SUN, sunLight[sunLightCurrent]);
        GL45C.glBindSampler(UNIT_SUN, depthSampler);
        sunLightProgram.use();
        sunLightProgram.set("Near", NEAR_PLANE);
        sunLightProgram.set("Far", farPlane);
        sunLightProgram.set("SunUv", sources[0], sources[1]);
        sunLightProgram.set("Aspect", aspect);
        sunLightProgram.set("Follow", sunLightFollow);
        sunLightProgram.set("HistoryValid", sunLightValid ? 1.0f : 0.0f);
        GL45C.glDrawArrays(GL45C.GL_TRIANGLES, 0, 3);
        sunLightCurrent = next;
    }

    /**
     * Folds this frame's haze into its history at the screen's size; the caller attaches its
     * own target and viewport after.
     */
    private void settleHaze(int depthTexture) {
        if (hazeAmount[0] == 0 || hazeWidth != screenWidth || hazeHeight != screenHeight) {
            if (hazeAmount[0] != 0) {
                GL45C.glDeleteTextures(hazeAmount);
            }
            for (int i = 0; i < 2; i++) {
                hazeAmount[i] = GL45C.glCreateTextures(GL45C.GL_TEXTURE_2D);
                GL45C.glTextureStorage2D(hazeAmount[i], 1, GL45C.GL_RGBA16F, screenWidth, screenHeight);
            }
            hazeWidth = screenWidth;
            hazeHeight = screenHeight;
            hazeValid = false;
        }
        viewProjection.set(projection).mul(view);
        double motionX = cameraX - hazePreviousX;
        double motionY = cameraY - hazePreviousY;
        double motionZ = cameraZ - hazePreviousZ;
        boolean valid = hazeValid
                && motionX * motionX + motionY * motionY + motionZ * motionZ < HISTORY_RESET_DISTANCE * HISTORY_RESET_DISTANCE;
        int next = 1 - hazeCurrent;
        GL45C.glNamedFramebufferTexture(framebuffer, GL45C.GL_COLOR_ATTACHMENT0, hazeAmount[next], 0);
        GlStateManager._viewport(0, 0, hazeWidth, hazeHeight);
        bindTexture(UNIT_DEPTH, depthTexture, depthSampler);
        // Past the units the game's state tracks, as the caustics' are.
        GL45C.glBindTextureUnit(UNIT_HAZE, hazeAmount[hazeCurrent]);
        GL45C.glBindSampler(UNIT_HAZE, sceneSampler);
        hazeTemporalProgram.use();
        hazeTemporalProgram.set("ViewportSize", hazeWidth, hazeHeight);
        setView(hazeTemporalProgram);
        hazeTemporalProgram.set("Haze", SkyLook.getAerialHaze());
        hazeTemporalProgram.set("Rain", rain);
        hazeTemporalProgram.set("CameraY", (float) cameraY);
        hazeTemporalProgram.set("CameraMotion", (float) motionX, (float) motionY, (float) motionZ);
        hazeTemporalProgram.set("HistoryValid", valid ? 1.0f : 0.0f);
        boolean shadows = CloudField.ready();
        hazeTemporalProgram.set("Clouds", shadows ? 1.0f : 0.0f);
        if (shadows) {
            setCloudField(hazeTemporalProgram);
            hazeTemporalProgram.set("CloudShadow", CloudField.shadowStrength());
        }
        hazeTemporalProgram.setMatrix("PreviousViewProjection", hazePreviousViewProjection);
        GL45C.glDrawArrays(GL45C.GL_TRIANGLES, 0, 3);
        hazeCurrent = next;
        hazePreviousViewProjection.set(viewProjection);
        hazePreviousX = cameraX;
        hazePreviousY = cameraY;
        hazePreviousZ = cameraZ;
    }

    /**
     * Traces this frame's caustic maps from the waves, a depth at a time, as deep as the water
     * measured around the camera reaches, and mips them; the caller attaches its own target and
     * viewport after. A floor reads the layers either side of its depth, so no deeper layer is
     * read, and canals a few blocks deep need a third of them. Every other layer is traced each
     * frame, so each follows the waves thirty times a second at sixty: all of them every frame
     * cost a millisecond and more.
     */
    private void drawCausticMaps() {
        boolean fresh = causticMaps == 0;
        if (causticMaps == 0) {
            causticSlopes = GL45C.glCreateTextures(GL45C.GL_TEXTURE_2D);
            GL45C.glTextureStorage2D(causticSlopes, 1, GL45C.GL_RG32F, CAUSTIC_GRID, CAUSTIC_GRID);
            causticTrace = GL45C.glCreateTextures(GL45C.GL_TEXTURE_2D);
            GL45C.glTextureStorage2D(causticTrace, 1, GL45C.GL_R16F, CAUSTIC_TRACE_SIZE, CAUSTIC_TRACE_SIZE);
            causticMaps = GL45C.glCreateTextures(GL45C.GL_TEXTURE_2D_ARRAY);
            int levels = 32 - Integer.numberOfLeadingZeros(CAUSTIC_MAP_SIZE);
            GL45C.glTextureStorage3D(causticMaps, levels, GL45C.GL_R16F, CAUSTIC_MAP_SIZE, CAUSTIC_MAP_SIZE, CAUSTIC_LAYERS);
            // A view must start unnamed, so glGenTextures rather than glCreateTextures.
            GL45C.glGenTextures(causticLayerViews);
            for (int layer = 0; layer < CAUSTIC_LAYERS; layer++) {
                GL45C.glTextureView(causticLayerViews[layer], GL45C.GL_TEXTURE_2D, causticMaps, GL45C.GL_R16F,
                        0, levels, layer, 1);
            }
        }
        // Each wave's phase at the period's corner, in double: time is far too large for floats.
        double seconds = System.nanoTime() * 1.0e-9;
        for (int i = 0; i < CAUSTIC_WAVES; i++) {
            double phase = WAVE_PHASE[i] - WAVE_FREQUENCY[i] * seconds;
            waves[i * 4] = (float) WAVE_X[i];
            waves[i * 4 + 1] = (float) WAVE_Z[i];
            waves[i * 4 + 2] = (float) WAVE_HEIGHT[i];
            waves[i * 4 + 3] = (float) (phase - 2.0 * Math.PI * Math.floor(phase / (2.0 * Math.PI)));
        }
        GL45C.glNamedFramebufferTexture(framebuffer, GL45C.GL_COLOR_ATTACHMENT0, causticSlopes, 0);
        GlStateManager._viewport(0, 0, CAUSTIC_GRID, CAUSTIC_GRID);
        causticSlopeProgram.use();
        causticSlopeProgram.setVectors("Waves", waves, CAUSTIC_WAVES);
        GL45C.glDrawArrays(GL45C.GL_TRIANGLES, 0, 3);
        // Past the units the game's state tracks, as the water columns' are.
        GL45C.glBindTextureUnit(UNIT_CAUSTICS, causticSlopes);
        GL45C.glBindSampler(UNIT_CAUSTICS, 0);
        int layers = Math.clamp((int) Math.floor(waterColumns.deepest() - CAUSTIC_FIRST_DEPTH) + 2, 1, CAUSTIC_LAYERS);
        int first = fresh ? 0 : causticParity;
        causticParity = 1 - causticParity;
        for (int layer = first; layer < layers; layer += fresh ? 1 : 2) {
            GL45C.glNamedFramebufferTexture(framebuffer, GL45C.GL_COLOR_ATTACHMENT0, causticTrace, 0);
            GlStateManager._viewport(0, 0, CAUSTIC_TRACE_SIZE, CAUSTIC_TRACE_SIZE);
            GL45C.glClearNamedFramebufferfv(framebuffer, GL45C.GL_COLOR, 0, NO_LIGHT);
            // Light adds up where folds of it cross.
            GlStateManager._enableBlend(0);
            GlStateManager._blendFuncSeparate(GL45C.GL_ONE, GL45C.GL_ONE, GL45C.GL_ONE, GL45C.GL_ONE);
            GlStateManager._blendEquationSeparate(GL45C.GL_FUNC_ADD, GL45C.GL_FUNC_ADD);
            causticProgram.use();
            setWater(causticProgram);
            causticProgram.set("Depth", CAUSTIC_FIRST_DEPTH + layer);
            GL45C.glDrawArraysInstanced(GL45C.GL_TRIANGLE_STRIP, 0, 2 * (CAUSTIC_GRID + 1), CAUSTIC_GRID);
            GlStateManager._disableBlend(0);
            GL45C.glNamedFramebufferTextureLayer(framebuffer, GL45C.GL_COLOR_ATTACHMENT0, causticMaps, 0, layer);
            GlStateManager._viewport(0, 0, CAUSTIC_MAP_SIZE, CAUSTIC_MAP_SIZE);
            bindTexture(UNIT_BRIGHT, causticTrace, depthSampler);
            causticFoldProgram.use();
            GL45C.glDrawArrays(GL45C.GL_TRIANGLES, 0, 3);
            GL45C.glGenerateTextureMipmap(causticLayerViews[layer]);
        }
    }

    /** The window of water columns and the sunlight the caustics bend, for a program that reads them. */
    private void setWater(Program program) {
        program.set("CameraBlock", (int) Math.floor(cameraX), (int) Math.floor(cameraY), (int) Math.floor(cameraZ));
        program.set("SunDirection", sunDirection[0], sunDirection[1], sunDirection[2]);
        program.set("WaterIor", waterIor);
    }

    /**
     * Drops every measurement of the sky and history of the clouds and the haze, after a jump in
     * the time of day: eased from the old light, the clouds took noon's colours into midnight.
     */
    private void forgetSky() {
        for (int texture : new int[] {calibration[0], calibration[1], skyBands[0], skyBands[1],
                horizonFilled[0], horizonFilled[1]}) {
            if (texture > 0) {
                GL45C.glClearTexImage(texture, 0, GL45C.GL_RGBA, GL45C.GL_FLOAT, (java.nio.FloatBuffer) null);
            }
        }
        historyValid = false;
        hazeValid = false;
        skySettledNanos = System.nanoTime() + SKY_SETTLE_NANOS;
    }

    /** Toward the moon, with whether it is up. */
    private void setMoon(Program program) {
        program.set("Moon", -sunDirection[0], -sunDirection[1], -sunDirection[2],
                -sunDirection[1] > 0.0f ? 1.0f : 0.0f);
    }

    /** Where the moon's halo and the aurora lie, which the statistics passes leave unmeasured. */
    private void setSkyLight(Program program) {
        setMoon(program);
        program.set("Aurora", SkyRenderer.auroraVisibility());
    }

    /** The depth and view ray inputs every pass that reads depth shares. */
    private void setView(Program program) {
        program.set("Near", NEAR_PLANE);
        program.set("Far", farPlane);
        program.set("CameraForward", cameraBasis[0], cameraBasis[1], cameraBasis[2]);
        program.set("CameraRight", cameraBasis[3], cameraBasis[4], cameraBasis[5]);
        program.set("CameraUp", cameraBasis[6], cameraBasis[7], cameraBasis[8]);
        program.set("TanHalfFov", tanHalfFovX, tanHalfFovY);
        program.set("ScreenSize", screenWidth, screenHeight);
    }

    private static void setCloudField(Program program) {
        float[] camera = CloudField.camera();
        float[] layers = CloudField.layers();
        float[] light = CloudField.light();
        program.set("CameraXZ", camera[0], camera[2]);
        program.set("CameraHeight", camera[1]);
        program.set("CloudBase", layers[0]);
        program.set("CloudThickness", layers[1]);
        program.set("LayerCoverage", layers[2], layers[3], layers[4]);
        program.set("Density", layers[5]);
        program.set("FeatureScale", layers[6]);
        program.set("Detail", layers[7]);
        program.set("LightDirection", light[0], light[1], light[2]);
        float[] flash = CloudField.flash();
        program.set("Flash", flash[0], flash[1], flash[2], flash[3]);
        program.set("LightIrradiance", light[3], light[4], light[5]);
        program.set("AmbientTop", light[6], light[7], light[8]);
        program.set("AmbientBottom", light[9], light[10], light[11]);
        program.set("Brightness", CloudField.brightness());
        program.set("HazeDistance", CloudField.hazeDistance());
        program.set("CirrusAngle", CloudField.cirrusAngle());
        program.set("CirrusStrength", CloudField.cirrusStrength());
        CloudField.setShapeUniforms(program.id);
    }

    public void cleanup() {
        if (brightProgram != null) {
            brightProgram.delete();
            brightProgram = null;
        }
        if (glintProgram != null) {
            glintProgram.delete();
            glintProgram = null;
            streakProgram.delete();
            streakProgram = null;
            glintBlendProgram.delete();
            glintBlendProgram = null;
        }
        if (glintTexture != 0) {
            GL45C.glDeleteTextures(glintTexture);
            GL45C.glDeleteTextures(streakTexture);
            glintTexture = streakTexture = glintWidth = glintHeight = 0;
        }
        if (glowProgram != null) {
            glowProgram.delete();
            glowProgram = null;
        }
        if (glowTexture != 0) {
            GL45C.glDeleteTextures(glowTexture);
            GL45C.glDeleteFramebuffers(glowFramebuffer);
            glowTexture = glowFramebuffer = glowWidth = glowHeight = 0;
        }
        if (horizonProgram != null) {
            horizonProgram.delete();
            horizonProgram = null;
        }
        if (horizonFillProgram != null) {
            horizonFillProgram.delete();
            horizonFillProgram = null;
            horizonSmoothProgram.delete();
            horizonSmoothProgram = null;
            skyBandsProgram.delete();
            skyBandsProgram = null;
            samplesProgram.delete();
            samplesProgram = null;
        }
        if (sampleFramebuffer != 0) {
            GL45C.glDeleteFramebuffers(sampleFramebuffer);
            GL45C.glDeleteTextures(sampleScene);
            GL45C.glDeleteTextures(sampleClouds);
            GL45C.glDeleteTextures(sampleSky);
            sampleFramebuffer = sampleScene = sampleClouds = sampleSky = 0;
        }
        if (horizonRaw != 0) {
            GL45C.glDeleteTextures(horizonRaw);
            GL45C.glDeleteTextures(horizonFilled);
            GL45C.glDeleteTextures(horizonSmooth);
            GL45C.glDeleteTextures(skyBands);
            horizonRaw = horizonFilled[0] = horizonFilled[1] = horizonSmooth = skyBands[0] = skyBands[1] = 0;
        }
        if (compositeProgram != null) {
            compositeProgram.delete();
            compositeProgram = null;
        }
        if (causticProgram != null) {
            causticProgram.delete();
            causticProgram = null;
            causticSlopeProgram.delete();
            causticSlopeProgram = null;
            causticFoldProgram.delete();
            causticFoldProgram = null;
        }
        if (causticMaps != 0) {
            GL45C.glDeleteTextures(causticSlopes);
            GL45C.glDeleteTextures(causticTrace);
            GL45C.glDeleteTextures(causticLayerViews);
            java.util.Arrays.fill(causticLayerViews, 0);
            GL45C.glDeleteTextures(causticMaps);
            causticSlopes = causticTrace = causticMaps = 0;
        }
        if (cloudMarchProgram != null) {
            cloudMarchProgram.delete();
            cloudMarchProgram = null;
            cloudCalibrationProgram.delete();
            cloudCalibrationProgram = null;
            GL45C.glDeleteTextures(calibration);
            calibration[0] = calibration[1] = 0;
            calibrationResolveProgram.delete();
            calibrationResolveProgram = null;
            GL45C.glDeleteTextures(resolvedCalibration);
            resolvedCalibration = 0;
        }
        if (cloudTexture != 0) {
            GL45C.glDeleteTextures(cloudTexture);
            GL45C.glDeleteTextures(cloudDistanceTexture);
            GL45C.glDeleteTextures(cloudHistory);
            GL45C.glDeleteTextures(historyDistance);
            GL45C.glDeleteFramebuffers(cloudFramebuffer);
            GL45C.glDeleteFramebuffers(temporalFramebuffer);
            cloudTexture = cloudDistanceTexture = cloudFramebuffer = temporalFramebuffer = cloudWidth = cloudHeight = 0;
            cloudHistory[0] = cloudHistory[1] = historyDistance[0] = historyDistance[1] = 0;
            historyValid = false;
        }
        if (cloudTemporalProgram != null) {
            cloudTemporalProgram.delete();
            cloudTemporalProgram = null;
        }
        if (hazeTemporalProgram != null) {
            hazeTemporalProgram.delete();
            hazeTemporalProgram = null;
        }
        if (sunLightProgram != null) {
            sunLightProgram.delete();
            sunLightProgram = null;
            GL45C.glDeleteTextures(sunLight);
            sunLight[0] = sunLight[1] = 0;
            sunLightValid = false;
            sourceLightProgram.delete();
            sourceLightProgram = null;
            GL45C.glDeleteTextures(sourceLights);
            sourceLights = 0;
        }
        if (hazeAmount[0] != 0) {
            GL45C.glDeleteTextures(hazeAmount);
            hazeAmount[0] = hazeAmount[1] = hazeWidth = hazeHeight = 0;
            hazeValid = false;
        }
        waterColumns.delete();
        if (vertexArray != 0) GL45C.glDeleteVertexArrays(vertexArray);
        if (framebuffer != 0) GL45C.glDeleteFramebuffers(framebuffer);
        if (brightTexture != 0) GL45C.glDeleteTextures(brightTexture);
        if (sceneSampler != 0) GL45C.glDeleteSamplers(sceneSampler);
        if (depthSampler != 0) GL45C.glDeleteSamplers(depthSampler);
        if (brightSampler != 0) GL45C.glDeleteSamplers(brightSampler);
        if (panoramaSampler != 0) GL45C.glDeleteSamplers(panoramaSampler);
        if (atmosphereSampler != 0) GL45C.glDeleteSamplers(atmosphereSampler);
        if (causticSampler != 0) GL45C.glDeleteSamplers(causticSampler);
        vertexArray = framebuffer = brightTexture = brightWidth = brightHeight = 0;
        sceneSampler = depthSampler = brightSampler = panoramaSampler = atmosphereSampler = causticSampler = 0;
    }

    private void ensureObjects() {
        if (glintProgram == null) {
            glintProgram = new Program(GLINT_FRAGMENT_SHADER);
            streakProgram = new Program(STREAK_FRAGMENT_SHADER);
            glintBlendProgram = new Program(GLINT_BLEND_FRAGMENT_SHADER);
        }
        if (brightProgram == null) {
            brightProgram = new Program(BRIGHT_FRAGMENT_SHADER);
        }
        if (glowProgram == null) {
            glowProgram = new Program(GLOW_FRAGMENT_SHADER);
        }
        if (horizonProgram == null) {
            horizonProgram = new Program(HORIZON_FRAGMENT_SHADER);
            horizonFillProgram = new Program(HORIZON_FILL_FRAGMENT_SHADER);
            horizonSmoothProgram = new Program(HORIZON_SMOOTH_FRAGMENT_SHADER);
            skyBandsProgram = new Program(SKY_BANDS_FRAGMENT_SHADER);
            samplesProgram = new Program(SAMPLES_FRAGMENT_SHADER);
        }
        if (sampleFramebuffer == 0) {
            sampleScene = GL45C.glCreateTextures(GL45C.GL_TEXTURE_2D);
            GL45C.glTextureStorage2D(sampleScene, 1, GL45C.GL_RGBA16F, SAMPLE_COLUMNS, SAMPLE_ROWS);
            sampleClouds = GL45C.glCreateTextures(GL45C.GL_TEXTURE_2D);
            GL45C.glTextureStorage2D(sampleClouds, 1, GL45C.GL_RGBA16F, SAMPLE_COLUMNS, SAMPLE_ROWS);
            sampleSky = GL45C.glCreateTextures(GL45C.GL_TEXTURE_2D);
            GL45C.glTextureStorage2D(sampleSky, 1, GL45C.GL_RGBA16F, SAMPLE_COLUMNS, SAMPLE_ROWS);
            sampleFramebuffer = GL45C.glCreateFramebuffers();
            GL45C.glNamedFramebufferTexture(sampleFramebuffer, GL45C.GL_COLOR_ATTACHMENT0, sampleScene, 0);
            GL45C.glNamedFramebufferTexture(sampleFramebuffer, GL45C.GL_COLOR_ATTACHMENT1, sampleClouds, 0);
            GL45C.glNamedFramebufferTexture(sampleFramebuffer, GL45C.GL_COLOR_ATTACHMENT2, sampleSky, 0);
            GL45C.glNamedFramebufferDrawBuffers(sampleFramebuffer,
                    new int[] {GL45C.GL_COLOR_ATTACHMENT0, GL45C.GL_COLOR_ATTACHMENT1, GL45C.GL_COLOR_ATTACHMENT2});
        }
        if (horizonRaw == 0) {
            horizonRaw = horizonTexture();
            horizonFilled[0] = horizonTexture();
            horizonFilled[1] = horizonTexture();
            horizonSmooth = horizonTexture();
            skyBands[0] = colourTexture(SKY_BANDS);
            skyBands[1] = colourTexture(SKY_BANDS);
        }
        if (compositeProgram == null) {
            compositeProgram = new Program(COMPOSITE_FRAGMENT_SHADER);
        }
        if (causticProgram == null) {
            causticProgram = new Program(CAUSTIC_VERTEX_SHADER, CAUSTIC_FRAGMENT_SHADER);
            causticSlopeProgram = new Program(CAUSTIC_SLOPE_FRAGMENT_SHADER);
            causticFoldProgram = new Program(CAUSTIC_FOLD_FRAGMENT_SHADER);
        }
        if (hazeTemporalProgram == null) {
            hazeTemporalProgram = new Program(HAZE_TEMPORAL_FRAGMENT_SHADER);
        }
        if (sunLightProgram == null) {
            sunLightProgram = new Program(SUN_LIGHT_FRAGMENT_SHADER);
            sunLight[0] = colourTexture(1);
            sunLight[1] = colourTexture(1);
            sunLightValid = false;
            sourceLightProgram = new Program(SOURCE_LIGHT_FRAGMENT_SHADER);
            sourceLights = colourTexture(MAX_SOURCES);
        }
        if (cloudMarchProgram == null) {
            cloudMarchProgram = new Program(CLOUD_MARCH_FRAGMENT_SHADER);
            cloudCalibrationProgram = new Program(CLOUD_CALIBRATION_FRAGMENT_SHADER);
            cloudTemporalProgram = new Program(CLOUD_TEMPORAL_FRAGMENT_SHADER);
            calibration[0] = colourTexture(CALIBRATION_BINS, 3);
            calibration[1] = colourTexture(CALIBRATION_BINS, 3);
            calibrationResolveProgram = new Program(CALIBRATION_RESOLVE_FRAGMENT_SHADER);
            resolvedCalibration = colourTexture(CALIBRATION_BINS, 3);
        }
        if (vertexArray == 0) {
            vertexArray = GL45C.glCreateVertexArrays();
        }
        if (framebuffer == 0) {
            framebuffer = GL45C.glCreateFramebuffers();
            GL45C.glNamedFramebufferDrawBuffer(framebuffer, GL45C.GL_COLOR_ATTACHMENT0);
        }
        if (sceneSampler == 0) {
            sceneSampler = sampler(GL45C.GL_LINEAR, GL45C.GL_LINEAR);
            depthSampler = sampler(GL45C.GL_NEAREST, GL45C.GL_NEAREST);
            brightSampler = sampler(GL45C.GL_LINEAR_MIPMAP_LINEAR, GL45C.GL_LINEAR);
            // The panorama wraps around in azimuth.
            panoramaSampler = sampler(GL45C.GL_LINEAR_MIPMAP_LINEAR, GL45C.GL_LINEAR);
            GL45C.glSamplerParameteri(panoramaSampler, GL45C.GL_TEXTURE_WRAP_S, GL45C.GL_REPEAT);
            // So does the atmosphere, which has no mipmaps.
            atmosphereSampler = sampler(GL45C.GL_LINEAR, GL45C.GL_LINEAR);
            GL45C.glSamplerParameteri(atmosphereSampler, GL45C.GL_TEXTURE_WRAP_S, GL45C.GL_REPEAT);
            // The caustic map is read across floors seen edge on.
            causticSampler = sampler(GL45C.GL_LINEAR_MIPMAP_LINEAR, GL45C.GL_LINEAR);
            // The maps hold one period of the web, which repeats.
            GL45C.glSamplerParameteri(causticSampler, GL45C.GL_TEXTURE_WRAP_S, GL45C.GL_REPEAT);
            GL45C.glSamplerParameteri(causticSampler, GL45C.GL_TEXTURE_WRAP_T, GL45C.GL_REPEAT);
            if (GL.getCapabilities().GL_EXT_texture_filter_anisotropic) {
                GL45C.glSamplerParameterf(causticSampler, EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT, 16.0f);
            }
        }
    }

    private void ensureBrightTexture(int width, int height) {
        if (brightTexture != 0 && width == brightWidth && height == brightHeight) {
            return;
        }
        if (brightTexture != 0) {
            GL45C.glDeleteTextures(brightTexture);
        }
        int levels = 32 - Integer.numberOfLeadingZeros(Math.max(width, height));
        brightTexture = GL45C.glCreateTextures(GL45C.GL_TEXTURE_2D);
        GL45C.glTextureStorage2D(brightTexture, levels, GL45C.GL_RGBA16F, width, height);
        brightWidth = width;
        brightHeight = height;
    }

    private void ensureGlintTextures(int width, int height) {
        if (glintTexture != 0 && width == glintWidth && height == glintHeight) {
            return;
        }
        if (glintTexture != 0) {
            GL45C.glDeleteTextures(glintTexture);
            GL45C.glDeleteTextures(streakTexture);
        }
        glintTexture = GL45C.glCreateTextures(GL45C.GL_TEXTURE_2D);
        int levels = Math.min(4, 32 - Integer.numberOfLeadingZeros(Math.max(width, height)));
        GL45C.glTextureStorage2D(glintTexture, levels, GL45C.GL_RGBA16F, width, height);
        streakTexture = GL45C.glCreateTextures(GL45C.GL_TEXTURE_2D);
        GL45C.glTextureStorage2D(streakTexture, 1, GL45C.GL_RGBA16F, width, height);
        glintWidth = width;
        glintHeight = height;
    }

    private void ensureGlowTexture(int width, int height) {
        if (glowTexture != 0 && width == glowWidth && height == glowHeight) {
            return;
        }
        if (glowTexture != 0) {
            GL45C.glDeleteTextures(glowTexture);
        }
        glowTexture = GL45C.glCreateTextures(GL45C.GL_TEXTURE_2D_ARRAY);
        int levels = Math.min(4, 32 - Integer.numberOfLeadingZeros(Math.max(width, height)));
        GL45C.glTextureStorage3D(glowTexture, levels, GL45C.GL_RGBA16F, width, height, GLOW_LAYERS);
        if (glowFramebuffer == 0) {
            glowFramebuffer = GL45C.glCreateFramebuffers();
        }
        int[] attachments = new int[GLOW_LAYERS];
        for (int layer = 0; layer < GLOW_LAYERS; layer++) {
            attachments[layer] = GL45C.GL_COLOR_ATTACHMENT0 + layer;
            GL45C.glNamedFramebufferTextureLayer(glowFramebuffer, attachments[layer], glowTexture, 0, layer);
        }
        GL45C.glNamedFramebufferDrawBuffers(glowFramebuffer, attachments);
        glowWidth = width;
        glowHeight = height;
    }

    /** The march's targets, in a framebuffer of their own, since they are smaller than the screen. */
    private void ensureCloudTexture(int width, int height) {
        if (cloudTexture != 0 && width == cloudWidth && height == cloudHeight) {
            return;
        }
        if (cloudTexture != 0) {
            GL45C.glDeleteTextures(cloudTexture);
            GL45C.glDeleteTextures(cloudDistanceTexture);
            GL45C.glDeleteTextures(cloudHistory);
            GL45C.glDeleteTextures(historyDistance);
        }
        if (cloudFramebuffer == 0) {
            cloudFramebuffer = GL45C.glCreateFramebuffers();
            GL45C.glNamedFramebufferDrawBuffers(cloudFramebuffer,
                    new int[] {GL45C.GL_COLOR_ATTACHMENT0, GL45C.GL_COLOR_ATTACHMENT1});
            temporalFramebuffer = GL45C.glCreateFramebuffers();
            GL45C.glNamedFramebufferDrawBuffers(temporalFramebuffer,
                    new int[] {GL45C.GL_COLOR_ATTACHMENT0, GL45C.GL_COLOR_ATTACHMENT1});
        }
        cloudTexture = GL45C.glCreateTextures(GL45C.GL_TEXTURE_2D);
        GL45C.glTextureStorage2D(cloudTexture, 1, GL45C.GL_RGBA16F, width, height);
        cloudDistanceTexture = GL45C.glCreateTextures(GL45C.GL_TEXTURE_2D);
        GL45C.glTextureStorage2D(cloudDistanceTexture, 1, GL45C.GL_RG32F, width, height);
        for (int i = 0; i < 2; i++) {
            cloudHistory[i] = GL45C.glCreateTextures(GL45C.GL_TEXTURE_2D);
            GL45C.glTextureStorage2D(cloudHistory[i], 1, GL45C.GL_RGBA16F, width, height);
            historyDistance[i] = GL45C.glCreateTextures(GL45C.GL_TEXTURE_2D);
            GL45C.glTextureStorage2D(historyDistance[i], 1, GL45C.GL_RG32F, width, height);
        }
        historyValid = false;
        GL45C.glNamedFramebufferTexture(cloudFramebuffer, GL45C.GL_COLOR_ATTACHMENT0, cloudTexture, 0);
        GL45C.glNamedFramebufferTexture(cloudFramebuffer, GL45C.GL_COLOR_ATTACHMENT1, cloudDistanceTexture, 0);
        cloudWidth = width;
        cloudHeight = height;
    }

    /** One row of {@link #HORIZON_BINS} colours, cleared to no sky. */
    private static int horizonTexture() {
        return colourTexture(HORIZON_BINS);
    }

    /** One row of {@code width} colours, cleared to none. */
    private static int colourTexture(int width) {
        return colourTexture(width, 1);
    }

    private static int colourTexture(int width, int height) {
        int texture = GL45C.glCreateTextures(GL45C.GL_TEXTURE_2D);
        GL45C.glTextureStorage2D(texture, 1, GL45C.GL_RGBA16F, width, height);
        GL45C.glClearTexImage(texture, 0, GL45C.GL_RGBA, GL45C.GL_FLOAT, (java.nio.FloatBuffer) null);
        return texture;
    }

    private static void bindTexture(int unit, int texture, int sampler) {
        GlStateManager._activeTexture(GL45C.GL_TEXTURE0 + unit);
        GlStateManager._bindTexture(texture);
        GL45C.glBindSampler(unit, sampler);
    }

    private static int sampler(int minFilter, int magFilter) {
        int sampler = GL45C.glCreateSamplers();
        GL45C.glSamplerParameteri(sampler, GL45C.GL_TEXTURE_MIN_FILTER, minFilter);
        GL45C.glSamplerParameteri(sampler, GL45C.GL_TEXTURE_MAG_FILTER, magFilter);
        GL45C.glSamplerParameteri(sampler, GL45C.GL_TEXTURE_WRAP_S, GL45C.GL_CLAMP_TO_EDGE);
        GL45C.glSamplerParameteri(sampler, GL45C.GL_TEXTURE_WRAP_T, GL45C.GL_CLAMP_TO_EDGE);
        return sampler;
    }

    private static final class Program {
        private final int id;

        Program(String fragmentSource) {
            this(VERTEX_SHADER, fragmentSource);
        }

        Program(String vertexSource, String fragmentSource) {
            int vertexShader = compile(GL45C.GL_VERTEX_SHADER, vertexSource);
            int fragmentShader = 0;
            int program = 0;
            try {
                fragmentShader = compile(GL45C.GL_FRAGMENT_SHADER, fragmentSource);
                program = GL45C.glCreateProgram();
                GL45C.glAttachShader(program, vertexShader);
                GL45C.glAttachShader(program, fragmentShader);
                GL45C.glLinkProgram(program);
                if (GL45C.glGetProgrami(program, GL45C.GL_LINK_STATUS) == GL45C.GL_FALSE) {
                    String log = GL45C.glGetProgramInfoLog(program);
                    GL45C.glDeleteProgram(program);
                    throw new IllegalStateException("Failed to link lens shader: " + log);
                }
            } finally {
                GL45C.glDeleteShader(vertexShader);
                if (fragmentShader != 0) {
                    GL45C.glDeleteShader(fragmentShader);
                }
            }
            id = program;
            GL45C.glProgramUniform1i(id, GL45C.glGetUniformLocation(id, "SceneColor"), UNIT_SCENE);
            GL45C.glProgramUniform1i(id, GL45C.glGetUniformLocation(id, "SceneDepth"), UNIT_DEPTH);
            GL45C.glProgramUniform1i(id, GL45C.glGetUniformLocation(id, "Bright"), UNIT_BRIGHT);
            GL45C.glProgramUniform1i(id, GL45C.glGetUniformLocation(id, "Glow"), UNIT_GLOW);
            // The glint passes run before the composite binds its own textures to these units,
            // and the blend after it.
            GL45C.glProgramUniform1i(id, GL45C.glGetUniformLocation(id, "Glints"), UNIT_BRIGHT);
            GL45C.glProgramUniform1i(id, GL45C.glGetUniformLocation(id, "Streaks"), UNIT_HORIZON);
            GL45C.glProgramUniform1i(id, GL45C.glGetUniformLocation(id, "HorizonSky"), UNIT_HORIZON);
            GL45C.glProgramUniform1i(id, GL45C.glGetUniformLocation(id, "HorizonRaw"), UNIT_BRIGHT);
            GL45C.glProgramUniform1i(id, GL45C.glGetUniformLocation(id, "HorizonPrevious"), UNIT_HORIZON);
            GL45C.glProgramUniform1i(id, GL45C.glGetUniformLocation(id, "Noise"), UNIT_NOISE);
            GL45C.glProgramUniform1i(id, GL45C.glGetUniformLocation(id, "Panorama"), UNIT_PANORAMA);
            GL45C.glProgramUniform1i(id, GL45C.glGetUniformLocation(id, "Cirrus"), UNIT_CIRRUS);
            GL45C.glProgramUniform1i(id, GL45C.glGetUniformLocation(id, "Atmosphere"), UNIT_ATMOSPHERE);
            GL45C.glProgramUniform1i(id, GL45C.glGetUniformLocation(id, "SampleSky"), UNIT_SAMPLE_SKY);
            GL45C.glProgramUniform1i(id, GL45C.glGetUniformLocation(id, "CloudHistory"), UNIT_HISTORY);
            GL45C.glProgramUniform1i(id, GL45C.glGetUniformLocation(id, "SkyBands"), UNIT_SKY_BANDS);
            GL45C.glProgramUniform1i(id, GL45C.glGetUniformLocation(id, "Samples"), UNIT_SAMPLES);
            GL45C.glProgramUniform1i(id, GL45C.glGetUniformLocation(id, "SampleClouds"), UNIT_SAMPLE_CLOUDS);
            GL45C.glProgramUniform1i(id, GL45C.glGetUniformLocation(id, "SkyBandsPrevious"), UNIT_SKY_BANDS);
            GL45C.glProgramUniform1i(id, GL45C.glGetUniformLocation(id, "HistoryDistance"), UNIT_HISTORY_DISTANCE);
            GL45C.glProgramUniform1i(id, GL45C.glGetUniformLocation(id, "CloudLight"), UNIT_CLOUDS);
            GL45C.glProgramUniform1i(id, GL45C.glGetUniformLocation(id, "CloudDistance"), UNIT_CLOUD_DISTANCE);
            GL45C.glProgramUniform1i(id, GL45C.glGetUniformLocation(id, "CalibrationPrevious"), UNIT_CALIBRATION_ROWS);
            GL45C.glProgramUniform1i(id, GL45C.glGetUniformLocation(id, "CalibrationRows"), UNIT_CALIBRATION_ROWS);
            GL45C.glProgramUniform1i(id, GL45C.glGetUniformLocation(id, "CloudCalibration"), UNIT_CALIBRATION);
            GL45C.glProgramUniform1i(id, GL45C.glGetUniformLocation(id, "WaterColumns"), UNIT_WATER_COLUMNS);
            GL45C.glProgramUniform1i(id, GL45C.glGetUniformLocation(id, "CausticMaps"), UNIT_CAUSTICS);
            GL45C.glProgramUniform1i(id, GL45C.glGetUniformLocation(id, "CausticSlopes"), UNIT_CAUSTICS);
            GL45C.glProgramUniform1i(id, GL45C.glGetUniformLocation(id, "CausticTrace"), UNIT_BRIGHT);
            GL45C.glProgramUniform1i(id, GL45C.glGetUniformLocation(id, "HazeAmount"), UNIT_HAZE);
            GL45C.glProgramUniform1i(id, GL45C.glGetUniformLocation(id, "HazePrevious"), UNIT_HAZE);
            GL45C.glProgramUniform1i(id, GL45C.glGetUniformLocation(id, "SunLight"), UNIT_SUN);
            GL45C.glProgramUniform1i(id, GL45C.glGetUniformLocation(id, "SunLightPrevious"), UNIT_SUN);
            GL45C.glProgramUniform1i(id, GL45C.glGetUniformLocation(id, "SourceLights"), UNIT_SOURCES);
        }

        void use() {
            GL45C.glUseProgram(id);
        }

        void set(String name, float value) {
            GL45C.glUniform1f(GL45C.glGetUniformLocation(id, name), value);
        }

        // Sizes pass ints here for vec2 uniforms; an int overload of two would send them as
        // ivec2, an error for every such uniform, so an ivec2 uniform is declared vec2 instead.
        void set(String name, float x, float y) {
            GL45C.glUniform2f(GL45C.glGetUniformLocation(id, name), x, y);
        }

        void set(String name, float x, float y, float z) {
            GL45C.glUniform3f(GL45C.glGetUniformLocation(id, name), x, y, z);
        }

        void set(String name, float x, float y, float z, float w) {
            GL45C.glUniform4f(GL45C.glGetUniformLocation(id, name), x, y, z, w);
        }

        void set(String name, int x, int y, int z) {
            GL45C.glUniform3i(GL45C.glGetUniformLocation(id, name), x, y, z);
        }

        void setMatrix(String name, Matrix4f matrix) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                GL45C.glUniformMatrix4fv(GL45C.glGetUniformLocation(id, name), false, matrix.get(stack.mallocFloat(16)));
            }
        }

        void setSources(float[] sources, int count) {
            GL45C.glUniform1i(GL45C.glGetUniformLocation(id, "SourceCount"), count);
            if (count > 0) {
                setVectors("Sources", sources, count);
            }
        }

        void setVectors(String name, float[] values, int count) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                GL45C.glUniform4fv(GL45C.glGetUniformLocation(id, name),
                        stack.mallocFloat(count * 4).put(values, 0, count * 4).flip());
            }
        }

        void delete() {
            GL45C.glDeleteProgram(id);
        }

        private static int compile(int type, String source) {
            int shader = GL45C.glCreateShader(type);
            GL45C.glShaderSource(shader, source);
            GL45C.glCompileShader(shader);
            if (GL45C.glGetShaderi(shader, GL45C.GL_COMPILE_STATUS) == GL45C.GL_FALSE) {
                String log = GL45C.glGetShaderInfoLog(shader);
                GL45C.glDeleteShader(shader);
                throw new IllegalStateException("Failed to compile lens shader: " + log);
            }
            return shader;
        }
    }
}
