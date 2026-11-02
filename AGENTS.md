# AGENTS.md: rtx-remix-minecraft

Context for AI coding agents working in this repository.

## Project Overview

`rtx-remix-minecraft` is a Fabric mod that renders Minecraft 26.3 through
NVIDIA RTX Remix. It captures terrain, entities, particles, sky, and clouds from
the client and submits them to the Remix runtime as a path-traced scene. The
Java facade and JNI bridge are tracked under `rtxremix-java/`; the native
runtime and the Remix C API come from dxvk-remix. A change often spans all
three layers.

This branch (`26.3`) is a fork that ports the upstream 1.21.1 mod to Minecraft
26.3 and adds its own rendering features. `PORTING.md` (Japanese) records what
the port changed and why, feature by feature. It is ignored by git because it
holds machine-local paths, so it exists only in the maintainer's checkout.

## Source Of Truth

Prefer these sources in this order:

1. source under `src/main/java/`
2. `build.gradle`
3. the Architecture section below
4. `README.md`, the guides under `docs/`, and the local `PORTING.md` where it
   exists

This file must describe the code as it is. When you find it disagreeing with the
code, report the mismatch to the user and rewrite this file to the newer state
in the same change; do the same for the guides under `docs/`. Never work around
a stale sentence silently.

There is deliberately no standalone architecture document. One existed and went
stale faster than the code it described. Code standards and review expectations
are in [CONTRIBUTING.md](CONTRIBUTING.md) and apply to agent-written code too. A
subdirectory may carry its own `AGENTS.md`; read the nearest one as well.

Mixin class names keep their 1.21.1 names so the fork diffs cleanly against
upstream: `WorldRendererMixin` targets `LevelRenderer`, for example. Comments
and guides use the 26.3 vanilla class names.

## Build, Run, Package, Test

A JDK 25 (with `JAVA_HOME` set, since the native build reads `jni.h` from it),
Windows, and the MSVC toolchain are required. Gradle fetches a missing Remix
runtime automatically. [Getting Started](docs/GETTING_STARTED.md) covers local
overrides.

```powershell
.\gradlew.bat build
.\tools\rtx.bat launch
.\gradlew.bat test
.\gradlew.bat package -Parch=win64
```

`tools\rtx.bat launch` builds the mod, installs it into a checkout-specific
Prism Launcher instance, and starts Minecraft through Prism. Installed Prism
reuses its normal account profile; portable Prism keeps its profile under
`run-prism/`. Use `tools\rtx.bat prism signin` with a Microsoft account that
owns Minecraft: Java Edition. See
[Getting Started](docs/GETTING_STARTED.md#sign-in-to-prism).

`classes` depends on `setupRuntime`, which compiles the JNI bridge, fetches a
missing runtime, and stages it along with `rtx.conf`, the Packman runtime
textures and the `rtx-remix/` conf payload, and migrates `run/` resource packs.
Even `test` therefore needs the C++ toolchain, PowerShell and Packman access.
`build` leaves a lightweight JAR in `build/libs/`; `package` embeds the runtime
and payload in `build/distributions/`. It verifies inclusion of every
`packaging/licenses/` file and every `packaging/rtx-remix/` file, and fails if
the runtime payload contains a Python interpreter or bindings. Staged and
packaged contents must agree with `NativeExtractor`. See
[Getting Started](docs/GETTING_STARTED.md) and [`build.gradle`](build.gradle)
for the staging inventory.

Distant Horizons is an optional compile-only dependency (Modrinth Maven, never
shipped). Its mixins live in a separate `rtx-remix-renderer.distanthorizons.mixins.json`
that loads only when Distant Horizons is installed (`lod/dh/`).

Do not run Gradle while a client from the same checkout is up: staging can fail
on locked DLLs or replace its live configuration. Stop the client or use another
worktree. Build guards should prevent a silently wrong artifact, as the
mixed-runtime-source guard does, rather than police checkout cleanliness.

Keep the unconditional tuning arguments in `loom.runs.client.vmArgs` in
`build.gradle` and `RECOMMENDED_JVM_ARGS` in `packaging/installer.ps1`
identical, and when `RECOMMENDED_JVM_ARGS` changes, append its outgoing value to
`PREVIOUS_RECOMMENDED_JVM_ARGS`. Opt-in benchmark and profiling arguments are
excluded. See [JVM Arguments](docs/TUNING.md#jvm-arguments). The Fabric API
version in `build.gradle` and `packaging/installer.ps1` must match too.

## Runtime And Install Model

The mod needs the native runtime, not only Java code, and `NativeExtractor`
unpacks the bundled payload on first launch, version or architecture change,
or when `remix-sdk/` or its `d3d9.dll` is missing. The version marker includes
the build's timestamp and commit, so every new build extracts in full once.
Deleting `.rtx-remix-version` also forces full re-extraction. Full extraction
replaces the mod-owned `remix-sdk/`, `rtx.conf`, and the shipped runtime
textures in `textures/`, leaving anything else in that folder alone, so a JAR
installed without the release installer is complete. With a matching marker and
runtime present, launch restores a missing `rtx.conf`, a missing runtime
texture, and any missing shipped files under `rtx-remix/` without replacing
existing files. The `rtx-remix/` payload is author-owned: existing files,
including edited confs, are never overwritten by `NativeExtractor`, even during
full extraction (`tools\rtx.bat install` does overwrite shipped payload files in
a development instance). Deleting a shipped conf restores its default on the
next launch. A missing or unacquirable `water.conf` prevents renderer
initialization and the error names the file; any other conf that fails to
acquire is skipped with a warning naming it, and `/rtx conf reload` retries it.

Baseline LabPBR textures live in `src/main/resources/assets/` and load through
Fabric's `fabric` resource pack below user packs. Development setup, `rtx install`,
and the release installer use `packaging/migrate_resource_packs.ps1` to remove
their exactly identified legacy baseline copies and migrate existing pack order.
`assets/minecraft/atlases/blocks.json` and `items.json` filter LabPBR `_n`, `_s`
and `_sss` sprites out of vanilla's atlases; the mod reads those maps from the
resources directly (`PbrAtlasFilterTest`).

## Architecture

**Orchestration.** `RtxRemixMod` is the Fabric entrypoint: lifecycle hooks, key
bindings, and the `/rtx` command tree. `RemixRenderer` is the render thread's
bridge into Remix and owns the frame. Minecraft 26.3 renders a level in two
halves, extraction into render states and submission to a
`SubmitNodeCollector`; Remix captures from the extracted states and replaces
the submits it renders itself (see
[Cancelling Vanilla Render Work](CONTRIBUTING.md#cancelling-vanilla-render-work)).
Other mods draw into the main target during the level render, through Fabric's
`LevelRenderEvents` or at the return of `LevelRenderer.render` (Litematica,
MiniHUD, Axiom), and the opaque Remix image covered them: `ModOverlayLayer`
clears the main target's colour to transparent at the main pass's start and
gives it the last Remix frame's depth, keeps the colour once the level render
returns, and lays it over the image after the lens and weather. Vanilla's block
outline is cancelled while RTX is on, since Remix draws its own.
Thread-safe stores wrap Remix-side meshes, instances, textures, materials, and
lights over raw `long` handles from the JNI wrapper. `JoinBurstHold` keeps
vanilla's terrain-download screen up for a bounded wait for stable frames after
a world join, hiding first-sight mesh, pipeline, and BLAS costs without holding
the screen indefinitely.

**Presentation.** `gl/RemixPresentationWindow` hosts Vulkan in a child of
Minecraft's SDL/OpenGL window by default; `-Drtxremix.presentation=parent`
selects the original host. Invalid values warn and use the default child host.
Mirrored input never bubbles into SDL twice, and
cursor messages stay with SDL. `GlSurfaceMixin` records the completed texture's
existing framebuffer for the GL-to-Vulkan copy. Window operations run on SDL's
owner thread; `WindowMixin` completes parent destruction synchronously before
the deferred native shutdown, including when an event-thread mod queues SDL
operations. Independent teardown steps report accumulated errors after release;
callbacks remain allocated while live windows or classes can invoke them.

**Sky.** `SkyRenderer` branches on the dimension's sky type: the End keeps a
constant dark dome and a skyless dimension a black one with no sky ambient. In
the Overworld, `AtmosphericSkyGenerator` computes the atmosphere with compute
shaders (`sky_atmosphere.comp`) into the texture of a Remix dome light, which
primary rays that miss show as the visible sky: Rayleigh scattering, aerosols
of an ordinary clear day and an ozone layer, with the higher orders of
scattering from a per-height table (Hillaire 2020) and each step's light
integrated in closed form over steps that lengthen away from the eye, so the
zenith stays blue and the horizon whitens. The sun's aureole is a narrow lobe
over the aerosols' phase; boosting the whole phase greyed the zenith. The sun's
disc is painted in as a spread splat with a wide, faint skirt
(`LightManager.SUN_DISC_GAIN`) so that reflections, which read the dome
texture, glint; the distant sun light gives up the same share. `SkyLayers`
draws cirrus and aurora, `SkyRenderer` the stars and celestial bodies, in the
sky matte, which `rtx.skyForceHDR` keeps scene-linear; `SkyLook` holds the sky
appearance settings.

**Overlay input.** `RemixInputGate` owns the Minecraft-side capture session for
the Remix overlays; `KeyboardMixin` and `MouseMixin` route SDL input events
through it. Capture follows whether a native menu is shown, which `rtx.showUI`
records: `GameRendererMixin` has the gate read it through
`GetConfigVariableResolved` at the head of `GameRenderer.extract`, before the
frame's GUI is extracted. The mod opens and closes menus by writing it, and the
menus' own close and switch controls, the runtime's menu hotkey, and its First
Use Guide change it too. Alt+X without Control toggles the developer menu over
gameplay or a screen. The only other open command in the mod is the RTX Remix
Graphics Settings button on the settings landing page; both open through
`RtxRemixMod.openRemixMenu`, and the button queues its request so the click
finishes dispatching first. Opening, by the mod or the runtime, finalizes held
key bindings, vanilla's F3 combination state, and active mouse interactions
because their releases will not reach Minecraft. Capture excludes Minecraft
input and cursor-lock requests, and screens see an off-screen cursor to clear
hover state. Remix resolves a write a frame or more later, so after the mod's
own write the gate ignores reads until one agrees with it. After
`OWN_WRITE_OVERDUE_FRAMES` frames without one, it logs that a config layer above
Derived Settings likely sets `rtx.showUI` and follows reads again. The native
controls take effect only because `SetConfigVariable` writes `NoSave` options
such as `rtx.showUI` to the Derived layer, where the menus write them; a User
Settings value would mask every native close. The packaged `rtx.conf` parks the
runtime binding on a chord Windows cannot deliver.

**Camera paths.** A time-aware shape-preserving cubic Hermite path over
nonuniform waypoint times with shortest-route unwrapped yaw, a strict version 1
JSON codec, and world-scoped storage. Playback applies each pose at the
pre-`GameRenderer` hook. The default clock follows wall time with bounded stall
smoothing (`PresentationClock`); the fixed-60 clock (`FixedStepClock`) advances
one deterministic sample per rendered frame, which benchmarks reuse with their
own frame index. Still shots for the screenshot tool use their own codec
(`StillShotCodec`, version 2 adds the cloud style, version 1 still reads).
`docs/CAMERA_PATHS.md` is the canonical guide.

**Gameplay-driven conf layers.** `ConfLayerDriver` fades a fixed table of mod
layers (water, view distance and its LOD extension, volumetric settling, rain,
thunder, and four times of day) by gameplay state. Option layers come from a mod
root shipping archetypes, acquired at init, and a world root inside a save
supplying zone and biome profiles, acquired on join. A world layer sits just
above its mod counterpart, so a map overrides per option rather than replacing
an archetype, and it needs the integrated server, so it is single-player only.
Profiles resolve above `rtx.conf` and below user settings and quality presets.
`ViewDistanceFade` sets the view-distance fade from Minecraft's render distance,
or from the LOD radius while LOD terrain draws.

**Clouds.** Four styles (`CloudStyle`). Classic (the default) voxelizes
vanilla's cloud texture with vanilla's world-time period and phase. Procedural
draws puffy meshes: identity and motion are stateless functions of cell
coordinates and synchronized world time, so clients reconstruct the same clouds
with no network state; one worker builds the mesh palette lazily, the render
thread uploads one mesh per frame, and each visible cloud submits one palette
mesh with one transform. Reimagined uses the texture-chunk path over a generated
mask (`ReimaginedCloudMask`) in one or two thick layers. Unbound is not drawn by
`CloudRenderer`: `sky/VolumetricClouds` ray-marches three cloud layers (small
and large cumulus, altostratus) and the cirrus on the GPU
(`volumetric_clouds.comp`) into a panorama shown on a dome in the sky category.
The panorama is marched from where the camera stands, fifteen times a second
while the camera holds still and thirty while it moves: marched fifteen
times a second on the move, the clouds' parallax stepped, in the sky and in
their reflections, and marched every frame, its march, readback and upload cost
about a millisecond and a half of each frame at 4K. On screen the lens draws them per pixel instead, and the shadows they cast on
the land, since the runtime casts none (see Lens). The dome stays as what the
lens replaces, and its opacity toward the sun hides the sun's disc in the
atmosphere's dome texture, so a glint on water goes out under a cloud; the
sun's distant light keeps its strength. The density field, lighting, march and
shadow live once in `cloud_field.glsl`, which both include (`CloudField`). A
sample is lit by the sun through light steps that double in length so they
cross the layer, by light scattered many times that thins as one over the
optical depth toward the sun, and by the sky through the cloud above it and the
ground's light through the cloud below it, so bases stay grey rather than
black. A cumulus layer's cover is broad weather cells read flat and clumps read
through the layer's height as well, about a noise cell from base to top, so a
cloud's outline changes on the way up and the sky breaks into ragged clumps;
read flat, every cloud was its outline stood up, a flat-topped, sheer-sided
slab. Above the flat base the detail noise's Perlin-Worley puffs the surface
in, and out half as far, which a thin edge grew into crumbs.
`CloudWeather` sets each layer's coverage and how calm the day is.
Every layer keeps its share of the Cloud Coverage setting, and two moods a day,
which turn into the next day's overnight, move it a little either way: how
unstable the air is grows the cumulus, and how moist it is spreads the large
cumulus and the altostratus.
A lightning bolt lights the cloud around where it reaches into their base
(`LightningFlash`, `Flash` in `cloud_field.glsl`): its light falls off over
tens of blocks, so the cloud around the bolt glows and the deck beyond stays
dark; while it flashes the panorama is marched every frame and neither march
keeps its history, which made the light lag and linger.
The large cumulus and the altostratus close in ahead of the rain, and broken
cumulus linger after it. A layer's cover climbs from its edge over at least a
quarter of the field's span, since a span as small as a low coverage stood
sparse clouds up as walls. The day and the hour
come from the world clock, so players agree; only the integrated server's
weather timers say when rain will come, so the sky closes ahead of it in
single-player alone; the closing ahead of the rain eases over at least 2400
ticks, since the server's timer jumps when the rain ends, after a night slept
through and on a weather command, and the sky turned into other clouds at once
each time. The clouds' frame wraps at `VolumetricClouds.WRAP_PERIOD`, which
every noise period in `cloud_field.glsl` divides (`VolumetricCloudsTest`); at a
wrap most of them did not divide, the whole sky changed whenever the wind or the
camera carried the frame across it. `CloudShapes` adds a shaped cloud to the field: rarely, on
a calm day, a creeper's head, a function of its cell in the clouds' wrapped
frame, the weather and the world's day, so clients agree with no network state.
It lies inside a bounding sphere that the march clips its ray to and crosses by
the distance to the surface, and takes the sky's light from its sides, through
the cloud between a point and its surface, rather than down a column.

**Terrain.** Minecraft workers build section meshes, a low-concurrency executor
merges them into chunk-column assets split into byte-capped pages per category,
and the render thread publishes them under a frame budget and retires replaced
pages through a bounded queue. Concurrent vanilla section builds are capped.
Visibility is vanilla's `SectionOcclusionGraph`; the 1.21.1 allocation-free
walk and coarse frustum index were not ported. Vanilla compiles a section only
once it is in view, and the path tracer needs the ground behind and beside the
camera, so `UnseenSectionCompiles` hands vanilla's section updates the dirty
sections outside the frustum, a few a frame and nearest first, while vanilla's
own compile queue is short (`LevelExtractorMixin`). Hidden sections inside the
frustum, such as caves under the ground in view, are left alone: compiling them
rebuilt the columns on screen one after another, and the runtime's depth left a
rebuilt column out for a while, which the lens read as sky. While capture is on,
integrated-server chunk delivery and chunk unloads are paced by measured
render-thread cost under separate budgets, with chunk caps on delivery and a
ring-edge bypass for unloads (`LocalChunkBatchPacing`, `PacedUnloadQueue`,
`ChunkDataSenderMixin`); the client light engine publishes its live section map
as its snapshot (`ClientLightSnapshotPolicy`, `LightStorageMixin`) while the
integrated server's engines, which have real readers on other threads, publish
an immutable layered view instead of vanilla's whole-map clone
(`ServerLightSnapshot`); and the client's sky-light surface walk is seeded at
the packet's `WORLD_SURFACE` height (`SeededSurfaceScan`,
`SkyLightSurfaceScanPolicy`), with capture on or off. `VanillaSectionBuilds`
keeps real copies until every build that latched capture off has finished.
Each policy has a `rtxremix.*` kill switch listed in `docs/TUNING.md`;
`rtxremix.light.vanillaSnapshot` covers both light snapshot policies.
Replacement light engines may omit vanilla's sky-source palette. The surface
hook then calls the original initialization operation instead of seeding a
palette that does not exist; ScalableLux keeps its own initialization hooks,
storage, propagation and scheduling.

**LOD terrain.** With Distant Horizons 3.3.4 installed, `lod/LodTerrain` builds
its far terrain as retained Remix instances beyond Minecraft's own terrain: the
LOD mod decides which sections exist and which are drawn, three low-priority
threads build each section's mesh against the current clip (one fell some
eighty sections behind a camera flying across a city), and the render
thread installs them under a frame budget and a budget of mesh data, the LOD
Memory setting, 2 GiB by default or no cap at all (Remix's buffers and acceleration structures
take about twice that; a budget worked out from the card's memory held an 8 GB
card to 512 MiB, where 2 GiB had run). The
clip is the chunks Minecraft draws, each column up to its highest section that
holds a block, as the column's published meshes hold it (`ChunkManager.isColumnDrawn`;
the heightmap is no measure, since a world edited without updating it keeps a
surface far above its blocks, and a captured section is drawn only a few frames
later, once its column is rebuilt, so going by the capture left pits after a
jump), rebuilt when that set changes, at most every
four frames, or the camera crosses a chunk: a section the set now covers retires
at once and one it changed is rebuilt first, so the LOD terrain meets
Minecraft's with neither a gap nor a square drawn over it. A build that
finishes against an older clip is dropped when the set now covers its section,
and otherwise installed and rebuilt first; installed with nothing queued, it
stayed over Minecraft's terrain for good. When the mesh budget
fills, the farthest sections give way, nearest first, and none is drawn beyond
the nearest given up; that radius grows back by a tenth as the camera moves
while a quarter of the budget is free. The LOD mod
swaps detail once its own buffers are uploaded, before these threads have built
the meshes, so a section it hides lingers, still drawn, while one it shows over
the same ground awaits its mesh, and built ones are held hidden until the
lingering ones go: the detail changes at once rather than through a hole. It
draws nothing while capture is on, so its uploads complete without filling GPU
buffers (`LodBufferContainerMixin`), and stopping capture rebuilds them. Its
vanilla fade is skipped then too (`ClientApiFadeMixin`): fading the main
target's terrain toward its empty textures drew black wherever the main target's
depth held land.

**Allocation.** The collector's concurrent work is what stretches frames at
high render distance, so the hot vanilla paths that run per section or per
frame are ported allocation-free and kept that way: biome colour blending
(`ClientWorldColorMixin`, `BiomeMixin`, `BiomeColorGeneration`). Retained
section meshes live in C-allocated direct buffers outside the JDK's
direct-memory budget (`SectionMeshData`, `DirectBufferPool`) and render-region
section snapshots (`SectionCopy`, through `SectionCopyMixin`) in pooled heap
arrays (`RenderRegionStoragePool`), both under explicit reference counts: the
creator's reference travels worker, `BlockDataQueue`, `TerrainState` entry; a
column build retains each snapshot before submission and releases in its
executor task's `finally`; the last release returns the buffers and makes the
surfaces unreadable while the cached hashes stay readable for diffing.

**Textures and atlases.** Atlas capture produces per-sprite materials plus
atlas-level PBR, emissive, and luminance textures. Every atlas is stitched
without a sprite border (`StitcherMixin`, `AtlasPadding`,
`-Drtxremix.atlas.vanillaPadding` restores vanilla's): the runtime mips the
whole atlas by halving it, and with no border the stitcher puts power-of-two
sprites on multiples of their own size, so no mip blends a sprite with its
neighbour (`AtlasSprites.offGrid` counts the exceptions in the block atlas log
line). Vanilla animates sprites on the GPU, so `SpriteAtlasTextureMixin` reads
each animation's state after `TextureAtlas.cycleAnimationFrames` and
`AtlasAnimationForwarder` rebuilds the frames on the CPU, LabPBR channels in
phase, and sends them in one batched `UpdateTextureFromMemory` call. Glyph pages
are mirrored on upload (`CommandEncoderGlyphMirrorMixin`,
`BitmapGlyphStagingMixin`), because vanilla assembles them on the GPU and never
keeps them on the CPU. `BlockTextureManager` keeps only mostly translucent
sprites translucent; doors, flowers and the like become cutout. Only terrain
whose sprite has texels in the emissive atlas takes an atlas material that
samples it (`_emissive` buckets, `getAtlasMaterialWithoutEmission` for the
rest): a face seen edge on samples the atlas's coarsest mip, where the glow of
every lamp, torch and lava texel is averaged, and drew yellow lines along the
planes through the camera at night.

**Water and weather.** `WaterNormals` holds the water normal map, and raindrop
rings from `RainRipples` are written into it as texture content each frame by
`RainSurfaces` (`-Drtxremix.rain.ripples=false` turns them off). The texture
covers 40 blocks: the ocean flipbook, whose frame spans ten, laid three times
over at one, two and four times its size, turned and on different frames, each
without its longest waves, which drew a lattice of swells at whatever size a
layer was laid. A frame is composed on a thread of its own and uploaded once
done. `Puddles` and `PuddleMask` lay rain puddles on open ground as a decal
(`DECAL_STATIC`, alpha-blended), which the runtime folds into the ground
beneath: by its opacity it blends the ground's colour, reflectance, roughness
and normal toward its own. Each tile of 16 blocks has a texture, 32 texels a
block, whose colour is a copy of every top (`BlockTextureManager.spriteSamples`
of the model variant's upward face, tinted) a little darker, so the decal shades
the ground only slightly (any other colour darkens it by the opacity), and
whose alpha is how wet the ground is, over most open ground. Two more textures,
roughness and metal share, bring the reflection only where water stands, a
fifth of open ground at the default Puddle Amount: from wet ground's sheen at a
puddle's edge to a mirror over its middle. Water over every wet place covered
nearly all of it. The metal, by the Puddle Reflection setting, is coloured and
darkened as metal is, since water's 4% head-on reflection showed almost
nothing over bright ground and lightening the colour to make up for the metal
blew it out to white. Its normals are the puddles' own ring texture
(`WaterNormals.puddleTexture`: smaller and more rings, no waves, flat once the
rain stops). A clear-water sheet before it
reflected by the runtime's Schlick Fresnel alone, strong at a grazing view
whatever the index, so it began on a sharp edge however its materials were
stepped. Tiles are retraced in turn within a millisecond a frame as the wetness
changes, so an edge creeps rather than jumps.
`RemixWeatherRenderer` redraws vanilla's rain and snow over the Remix image,
occluded through the interop depth texture; rain refracts a copy of the main
target as the lens left it, since it draws opaquely. Vanilla's own weather and
cloud draws are skipped.

**Lens.** `lens/LensEffects` is a GL pass after the Remix composite and before
the weather: depth of field focused on what the crosshair rests on, flares from
the sun and the brightest drawn lights (`FlareSources`; the terrain light loop
and the first-person flame offer them, and the list is cleared after the lens
draws), aerial haze, thickened by rain, and the Unbound clouds and their
shadows on the land. Under water there is no haze, and the clouds are left to
the runtime's image, which shows them through the waves. The sun flares only as
far as rays cast toward it through the loaded world meet no block and the depth
around it on screen shows sky: the depth alone let it through glass, which reads
as sky, and lit ceilings. The sun's light for its flare is eased over a tenth of
a second in a pass of one texel.
It works on the runtime's tone-mapped image and the interop depth, which is
not antialiased, so haze, clouds and shadows treat a sky pixel beside the land
as land. The depth is jittered from frame to frame as well, so a silhouette
flips between land and sky; the haze's amount is kept in a history reprojected
by the camera's motion and clamped to its neighbours' range, and without it
the edges of the land and the LOD terrain against the sky flickered. The same
history settles the share of each pixel that shows sky, by which the clouds
replace the dome's, the sky is deepened and the clouds' shadow darkens only the
land's share (a sky pixel beside the land takes the land's distance, and
darkening it drew a flickering blue line along ridges), and the shadow itself,
taken at a distance from the camera settled over frames: the jitter moves
distant land seen at a grazing angle by tens of blocks, and its shadow came and
went; settled along the view axis, the distance changed as the camera turned
and the shadow was taken short of the land or past it. Haze fades
land toward the sky the image
shows at the land's own height, kept per band of elevation, times how each
screen column's sky differs from its band where the column was read. A band's
trust falls while it shows no sky and climbs back while it does, and readers
pass over by it to the nearest band in view, so land below the horizon takes the
horizon's sky, not one seen earlier from higher up; the residual row of the
clouds' calibration does the same. Turning to a neighbour at once stepped the
sky whenever walking bobbed a band at the edge of the view in and out of it. The column's
read takes only sky that runs on for a few rows, never a lone texel with
nothing drawn. Fading toward a column's own lowest sky made whole towers glow
in the rain, where light from below brightens fog that has no depth. The sky
bands and the clouds' calibration loop over one coarse sample of the screen
taken once a frame. A shadow darkens the land per
channel by the share of its light that comes straight from the sun or moon,
taken most of the way to grey, since the direct light's share is warm and left
every shadow cold blue. Drawn over the finished image, it cannot tell the sun's
light from a lamp's, so a torch under a cloud's shadow dims with the land.
The clouds are marched at half resolution for every pixel, between the camera
and the land or through the whole sky, and folded into a history reprojected
by the camera's motion. Their light is brought to the image's exposure by
comparing the dome's opaque clouds on screen with the panorama, per brightness
band of one stop, less the atmosphere they let through, since at night the sky
outshines a moonlit cloud; the sky matte is scene-linear, so sunlit cloud is not
clipped at 1 nor its shade crushed by an sRGB decode. Until any band has been measured,
as when the only clouds lie below the camera, each band holds a guess from how
bright the clear upper sky shows against its radiance; without it the clouds'
scene-linear light went onto the image unscaled and showed flat white. Over the
sky they replace the dome's clouds outright, over the sky behind those: read out
of the image through thin dome clouds, using the panorama the dome shows
(`VolumetricCloudGenerator.shownPanoramaTexture`), and where they are thick, the
atmosphere the dome light shows (`CloudField.skyTexture`). The image shows that
atmosphere about half as bright as the clouds' calibration would bring it, and
its tone mapper mixes the channels, so the clear sky on screen is measured
against it twice more: per brightness band, a mean in stops that drops the
samples more than a stop from the plain mean, so no star, moon or lamp's bloom
passes for sky, and per band of elevation for what that still leaves. Each of
these rows reads every other sample each way on one thread per band, and a
median found by halving there cost four to seven milliseconds a frame at 4K;
the composite reads the rows once resolved, one linear read each. A
sky kept per band of elevation instead held whatever it last saw, the day's
white at the horizon into the night, and spread the moon's glow and the lamps'
bloom along its band, which the clouds' thin edges showed as pale rims. Within a
band the atmosphere still ran from half to twice the image's sky, toward the moon
and away, and showed as pale or dark rims where the dome's clouds outlast the
marched ones, so a quarter-resolution pass also maps, read coarse, how the
image's clear sky around differs from it, in stops, up to twice it. The same pass
maps what the image shows beyond that over clear sky, and beyond their light and
the sky behind them over the dome's thick clouds: the runtime's bloom, which the
composite takes out of the image before it replaces the clouds and lays back
over all of it, since a lamp's glow otherwise stopped at the skyline. A torch
beside the camera at night brightens the sky many times over; taken as an error
of the atmosphere and put back only over the clouds, its glow was cut off at
them, with a dark rim wherever thin dome cloud stood beside. Around the lamps
the flare sources place on screen, the clear sky is not measured and all the
image shows over the sky expected there counts as glow, kept as the image shows
it where the dome holds no thick cloud: measured as sky, a torch's bloom turned
the clouds behind it red, and laid back coarse it spread into a wide pink ring.
A lamp's flame and bloom lie in front of everything but carry the depth of
what lies behind, so around a lamp the haze and the clouds only brighten the
image and, after sunset, the clouds' shadows leave it: faded, clouded or
shaded with the distant land behind, the flame went out and the land showed
through the glow as pale outlines.
Keeping the image wherever the two marches
agreed drew the panorama's speckle and outlines, which the denoiser smooths out
of the image, onto the bases of clouds overhead. The panorama copy is mipmapped
and read over the pixel's footprint, its colour averaged over neighbours of
matching opacity and its opacity only deep inside a cloud, because the runtime
mips the dome's texture and its denoiser smooths the march's speckle out of the
image but keeps the outlines. Before the clouds, the clear sky toward the zenith
is deepened back toward its blue, which the tone mapper's shoulder bleaches;
only colour leaning the way the sky's band of elevation does, since a lamp's
orange glow over the sky was pushed to red and stopped at the skyline.
After the composite, the sun mirrored on level water glows and its glints
streak: quarter-resolution passes keep the near-saturated pixels whose view ray,
turned back up off the horizontal, nears the sun, and streak only those that
stand out from their neighbours, in the starburst's six directions.
The floors of sunlit water get caustics as far as the water is measured.
`WaterColumns` keeps, in a ring texture 512 columns across around the camera,
refreshed a row a frame and a row within 32 blocks of it, each column's water
surface, floor (searched 32 blocks down) and the top that shades it. The 32
summed waves, whose lengths never line up and which run every way alike, are
rounded to whole waves over 40 blocks, so the surface repeats every 40 blocks
and one traced period serves all the water there is. A grid over that period,
sixteen cells a block, is tilted by the waves' slope, bends the sunlight down
to ten depths, no deeper than the deepest water measured and every other depth
each frame, and is drawn where the light lands, each triangle
adding the surface it covered over the floor it covers, then folded back onto
the period into a mipmapped array of one layer a depth at 32 texels a block;
where folds of light cross they add up, and a low sun draws the web out along
its direction. The composite reads the layers either side of a floor's depth
with anisotropic filtering over the floor a pixel covers, so distant floors
settle to their mean instead of showing the web as a pattern and a floor seen
edge on keeps its web. The web greys out with depth and is gone by the deepest
floor measured, so no depth draws a line where it stops. The waves are not the
runtime's normal map. Seen from above, the interop depth through water is the
runtime's unbent path, which lies below the floor, so the lens follows the view
bent where it crosses the surface, column by column, to the ground it meets: a
floor rising toward a shore, or the side of a column that stands higher. Seen
from under water the depth is the real point. A march over the column tops
toward the sun and the clouds' shadow put the web out.

**Particles.** Slot preparation and CPU mesh snapshots run on a dedicated worker
and are double buffered: the render thread publishes the previous frame's
payload at the top of the frame and joins the worker after submit, so particles
trail the scene by exactly one frame. Only `SINGLE_QUADS` particles are
captured; item-atlas particles are alpha-tested. Vanilla particle colours are
lightmap tints, not albedos, and the runtime lights a blended particle from the
radiance cache with no self-shadowing, so `ParticleTint` draws soot near-black
to keep smoke dark over a lit floor.

**Entities.** Entities and block entities that vanilla already draws are
captured in place, so a model is evaluated once rather than once for OpenGL and
again for Remix; entities outside vanilla's frustum are swept up closest-first
under a budget, because a path tracer needs them for shadows and reflections,
and an entity that owns a Remix light this frame is swept at any distance so
its light never arrives without its housing. The local player is always swept,
even when vanilla's own draws use up the budget, because in first person its
hidden body is captured nowhere else. Rigid occurrences retain instances
keyed by owner, category, layer, and part; strips and beams stream transient
meshes per frame. `OverlayComposite` paints cutout overlay layers, such as a
villager's profession, a sheep's wool or a GeckoLib model's layer drawn again
vertex by vertex, into a copy of the body texture so they do not render black. `RemixBlockDamagePass` submits static terrain damage
decals. `CopperBeaconBlockEntityRenderer` submits the articulated beacon model;
the mount stays in terrain. `CopperBeaconPose` is shared with the per-frame disk
light. Server-authoritative block-entity state owns the axis parameters and
anchor tick; clients reconstruct motion from those and synchronized world time.

**Lights.** Block lights are created, updated, and destroyed on the render
thread under their own budget, from resource-pack rules keyed by block state.
A glowing cube's and a lantern's sphere stands a little out of the faces around
it, at the brightness at which the part that stands out gives off a torch's
power per light level (`BlockLightDefaults.torchParityIntensity`): vanilla rates a light
by its level alone, and at the plain light-level radiance a glowing cube gave
off about fifteen times a torch while a lantern's flame lit only its glass.
Changing the block-light multipliers updates existing lights in place over a few
frames (`ChunkManager.refreshBlockLightIntensities`); terrain is not rebuilt. A
lightning bolt carries a bluish sphere light a few blocks up from the strike
while it shows, which lights no fog: lit in the volumetrics, the whole sky
turned white with each strike. A
hand-held item whose placed block shows a lit particle gets the same element in
hand, emitted once the item's pose is on the matrix stack
(`ItemLayerRenderStateMixin`), so first person, the hidden shadow model, and
other players share one hook. `HeldLightState` is the one decision on whether a
held item's lit element is burning: the local player's Toggle Held Lights key
puts out everything in their own hands, and by default an open flame goes out
for any holder whose eyes are under water. When the time of day jumps by more
than a hundredth of a day in one frame, `LightManager` clears the runtime's
radiance cache (`rtx.spatiallyHashedRadianceCache.resetHistory`), which would
otherwise keep the old light for half a minute where few paths refresh it.

Terrain block lights are per-frame draws, not retained instances: every frame
`ChunkManager.draw` submits each surviving `TerrainLight` (the `RemixLight`
plus its position, power, rule output, and last drawn frame), so not drawing a
light is a complete cull. Culling applies the column Chebyshev box and the
section connectivity flood in `TerrainState`, then a power-aware reach test with
hysteresis. When more lights pass than `ChunkManager.MAX_DRAWN_LIGHTS`, the next
frames raise the reach test's weight floor for every light alike until the
count fits, and skip unread any section no light could pass: the runtime drops
every light past 65,535, and its sampler draws candidates evenly from the whole
list. A light cull distance of 0 means Auto: `ChunkManager` resolves it,
and any explicit value, against Minecraft's render distance and pushes the
radius into `TerrainState` only on change, so `TerrainState` is always finite.
The flood rebuilds into a second set in per-frame slices and swaps it in when
complete; a section added mid-build marks the set dirty rather than expanding a
set about to be replaced. Every section holding a block is captured, a solid one
included, so the flood crosses a missing section as open air between the lowest
and highest captured heights, and a section that replaces air it crossed with
anything sealed marks the set dirty. `LightManager` lights are never culled. Some small
emitters use `BuiltinEmission` instead of built-in analytic-light rules to avoid
diluting the sampler's candidate pool.

## Invariants

- Every Remix handle is created, updated, and destroyed on the render thread;
  workers hand over CPU data through bounded, generation-tagged queues
  (`BlockDataQueue`, `ChunkManager`).
- A retained instance is submitted where it is created or mutated, never once
  per frame (`ChunkManager`, `RemixEntitySubmitter`, `LodTerrain`).
- A prepared particle payload holds world-space vertices and a live bucket, so
  every path that suspends or tears down rendering discards it rather than
  publishing it, including a world swap that keeps the connection alive
  (`RemixRenderer`).
- A coplanar decal stack, such as a banner's pattern layers, is one mesh in
  paint order, because Remix orders coplanar decals by primitive index rather
  than by depth; text turns draw order into depth the same way
  (`RemixEntitySubmitter`).
- A local first-person light is submitted with a zero volumetric radiance scale,
  because the volumetric grid reuses the previous frame's radiance and a
  camera-locked emitter would smear into distant geometry (`LightManager`).
- Ordinary classes must not live in the mixin package (`MixinPackageBoundaryTest`).
- A mixin that skips vanilla render work skips a whole draw or submit, never
  part of a `RenderPass`'s setup: pipelines carry their own state, so nothing
  needs replaying, but a half-skipped pass leaves uniforms and samplers half set
  (`ChunkSectionsToRenderMixin`, `WorldRendererMixin`). Raw GL calls outside
  renderpearl save and restore what they change.
- Never cancel `LevelRenderer.render()` or `LevelExtractor.extract()`. Remix is
  hosted inside vanilla's frame and captures entities and block entities at
  their submit points; skip submits, never extraction. See
  [Cancelling Vanilla Render Work](CONTRIBUTING.md#cancelling-vanilla-render-work).
- A terrain recapture must clear Remix terrain, reload vanilla terrain, and
  explicitly queue the previous built set. Snapshot `builtSections` before
  `LevelExtractor.allChanged()` forgets it, then call `recaptureTerrainForRemix`,
  which queues the set for vanilla's next view-area rebuild.
  `RtxRemixMod.rebuildLoadedTerrain()` owns this sequence. A setting that only
  changes light radiance uses `refreshBlockLightIntensities()` instead. Commit
  settings once per transaction, never once per slider step.
- Schedule world-transition work through `client.execute`, not a flag drained
  by a world-render hook: those hooks stop when `client.level` is null, so the
  clear would land in the next world's initialization. Re-check the session
  after an executor hop.
- `JOIN` and `DISCONNECT` are not paired across server reconfiguration. A join
  handler must release the previous session's state before acquiring new state.
- Keep terrain budget loops allocation-free and do not time individual native
  calls with additional `System.nanoTime` reads. Reuse `ProfileScope`, the
  loop's deadline read, and clock-free `TerrainNativeCallStats` tallies.
  Avoid per-block/per-quad capture allocation and per-frame render allocation.
- Vanilla section capture has one path (`SectionBuilderMixin`); LOD-mod terrain
  is a separate source (`LodTerrain`).
- Every retained `SectionMeshData` or render-region reference is released
  exactly once: `SectionMeshData` rejects an over-release, `DirectBufferPool`
  ignores a buffer that is already idle, and `RenderRegionStoragePool` relies
  on its callers' counts (`SectionMeshData`, `DirectBufferPool`,
  `RenderRegionStoragePool`).
- A queued chunk unload is cancelled by the column's re-send, and a queued
  column that reaches the client ring's edge is sent at once regardless of
  budget (`PacedUnloadQueue`).
- Nothing a `ServerLightSnapshot` has published is ever mutated; the engine
  copies a nibble array before its first write after a publish
  (`LightStorageMixin`).
- The camera schema stays strict version 1 and its discriminator stays
  `"catmull-rom"` (`CameraPathCodec`); still shots are a separate file with
  their own strict codec, never extra waypoint fields (`StillShotCodec`).
- Authored camera paths stay separate from launcher-owned fixture staging,
  and shot sets live in the directory the trigger names, never inside a world
  fixture (`CameraController`).
- A value meant to stay dynamically blended is written as a conf layer, never
  through a user-layer config write (`ConfLayerDriver`).
- `remix-sdk/` and `rtx.conf` stay mod-owned and the `rtx-remix/` payload
  author-owned, and the native compile tasks, `setupRuntime`, and `package` stay
  aligned with the packaged contents they verify (`NativeExtractor`,
  `build.gradle`).
- The launcher's account options (`--username`, `--accessToken`, `--uuid`,
  `--xuid`, `--clientId`) are blanked in the process command line before the
  runtime loads, because its crash dumps copy that line (`RemixRenderer`,
  `CommandLineRedaction`).
- Vanilla's shared worker pool and section-builder concurrency stay capped
  (`WorkerPoolCap`, `SectionBuildConcurrency`).
- Nothing but the render thread reads client light (`Level.getBrightness`,
  `LevelLightEngine`) while capture is on: the published snapshot is then the
  engine's live map (`ClientLightSnapshotPolicy`, `LightStorageMixin`).
- There is one overlay toggle path. `RemixInputGate` alone owns the capture
  session. Every open request goes through `RtxRemixMod.openRemixMenu`, and
  every menu the runtime shows or hides reaches the gate through
  `RtxRemixMod.followRemixMenuVisibility`, which `GameRendererMixin` calls at
  the start of each frame and which allocates nothing on the Java heap. The
  packaged runtime menu binding stays away from Alt+X (`KeyboardMixin`,
  `MouseMixin`, `RtxSettingsScreen`, `packaging/rtx.conf`).
- The placed Copper Beacon is not a `BlockLightDefaults` rule: its block entity
  owns a dynamic articulated light, and a static rule would light it twice
  (`LightManager`, `CopperBeaconBlockEntityRenderer`).
- Whether a held item's lit element burns is decided once, in `HeldLightState`;
  the flame, the smoke, the hand light, and the held beam all consult it
  (`HeldItemEffectsRenderer`, `LightManager`).
- A terrain light's cull is the decision not to draw it this frame, never a
  destroy; the radius `TerrainState` holds is finite and pushed only on
  change, because a changed radius dirties the whole flood, and the draw
  budget raises the weight floor, never that radius (`ChunkManager`,
  `TerrainState`).
- Keep `rtx.showUICursor = false` in `packaging/rtx.conf`. The mod does not
  write this key; the runtime software cursor can leave the OS cursor hidden.
- Change `BlockLightDefaults` and `BlockLightIntensityControls` together.
  Their rule-id sets are validated during resource reload, not just unit tests.
- Run entity capture on the render thread. Vanilla shares mutable renderer and
  model instances; gathering entities in parallel races on their part angles.
- Atlases are stitched without sprite borders; a wider border to fight mip
  bleeding costs atlas area fourfold for 128-texel packs (`AtlasPadding`).
- The Unbound clouds' density, lighting and march are written once, in
  `cloud_field.glsl`; the dome's march and the lens's per-pixel march include
  it, so the clouds on screen and the panorama they replace agree
  (`CloudField`, `VolumetricCloudGenerator`, `LensEffects`).
- Every noise period in `cloud_field.glsl` divides `VolumetricClouds.WRAP_PERIOD`,
  or the whole sky changes when the clouds' frame wraps (`VolumetricCloudsTest`).
- Anything drawn after the lens reads the main target, never the runtime's raw
  image, or it undoes the lens where it draws (`RemixWeatherRenderer`).
- The developer tools launch only through a signed-in Prism; they never pass
  Prism's `--offline` or launch `runClient` (`test_public_boundary.py`).

## Focused Engineering Guides

- [Rendering Diagnostics](docs/RENDERING_DIAGNOSTICS.md): lifecycle, recapture,
  material updates, entity precision, and runtime surface-capacity symptoms.
- [Tuning](docs/TUNING.md): queue waiting, retirement gates, worker limits, and
  JVM argument ownership. Named source classes own the defaults.
- [Benchmarking](tools/docs/BENCHMARKING.md) and
  [Profiling](tools/docs/PROFILING.md): counter interpretation and measurement.
- [Getting Started](docs/GETTING_STARTED.md): runtime staging, initialization
  failures, debug views, and worktree setup. Debug-view indices belong to the
  matching dxvk-remix source, not this repository.
- `PORTING.md` (local, untracked): what the 26.3 port changed, the fork's own
  features, and what the runtime was found to do (Japanese).

## Integrated JNI Wrapper

`rtxremix-java/` is a Minecraft-agnostic Java facade and JNI shim over the
RTX Remix C API. It must never import `net.minecraft` or `net.fabricmc`;
`RemixJavaBoundaryTest.shimSourcesDoNotImportMinecraftOrFabric` enforces this
boundary across `rtxremix-java/src/`. Keep game-state capture and Fabric
integration in the mod, not in the shim. Wrapper and affected mod callers
change in the same pull request. This fork has not changed the wrapper since
the upstream import; its features reuse the existing batched
`updateTextureFromMemory`, config-variable and config-layer entry points.

The wrapper's source of truth, in order, is:

1. `rtxremix-java/src/com/nvidia/rtxremix/`: `RemixAPI.java` is the facade,
   `RemixBridge.java` declares static JNI entry points, and the other API types
   are data holders, builders, constants, handles, and small utilities such as
   `CommandLineRedaction`.
2. `rtxremix-java/native/remixbridge.cpp`: loads `d3d9.dll`, calls
   `remixapi_InitializeLibrary`, stores the native interface, and forwards calls.
3. `rtxremix-java/native/include/remix_c.h`: the copied C API header.
4. `rtxremix-java/native/build_native.bat`: the MSVC bridge build.
5. `packman/remix-sdk.packman.xml`: the published runtime pins.
6. SDK source management in `tools/src/rtx_tools/sdk.py` and fetching in `build.gradle`.

The root Gradle build compiles the Java sources directly and builds
`remixbridge.dll` into `build/native/{win64|arm64}/`. There is no separate
wrapper Java build or example launcher. The native build needs Visual Studio
C++ tools and `JAVA_HOME` pointing to a 64-bit JDK 25. It uses the
copied header unless `REMIX_INCLUDE` explicitly selects another directory.
The supported targets are Windows x64 and ARM64; agent shells may be POSIX
even though the Gradle wrapper and native compiler entry points are batch scripts.

Runtime payloads live in `remix-runtime/{win64|arm64}/`, outside `build/` so
Gradle `clean` cannot traverse Packman links into the shared package cache.
The runtime textures' link, `remix-runtime/textures/`, sits beside them. Packman
makes these links as junctions or directory symlinks depending on the machine.
Treat them as read-only, and keep every Packman link under
`remix-runtime/`, which git has never tracked: a checkout of a commit that
tracks files under a link writes and deletes them in the shared cache. Staging and packaging combine the selected
SDK with the separately built bridge; never write the bridge into a
Packman-managed directory. The SDK contains `d3d9.dll`, dependent runtime DLLs,
and USD plugin payload under `usd/`. SDK payloads and bridge outputs stay
ignored by Git.

Gradle fetches a missing SDK before `syncRuntimeSdk` and fetches the selected
architectures before packaging. Existing runtime directories and local overrides
are left alone. Run source-management commands from the repository root:

- `tools\rtx.bat sdk status --arch {win64|arm64}` reports the staged source and
  local-copy freshness without changing it.
- `tools\rtx.bat sdk local --arch {win64|arm64} [PATH | --path <path>]` replaces
  the selected link with a snapshot copy and writes `.local-sdk-source`.
  Without a path, it reuses the sentinel's source, then `sdk.localPath` from
  ignored `tools/config.local.json`, then `../dxvk-remix/_output`. Relative
  paths start at the repository root. Worktrees can set that local default
  once or pass a path. `sdk restage` refreshes the recorded snapshot, and
  `sdk auto-restage [on|off]` controls whether launch re-stages a changed local
  build (on by default).
- `tools\rtx.bat sdk packman --arch {win64|arm64|all}` clears local staging
  and restores the pinned runtime. It is a no-op on the pinned version unless
  `--force` is passed.
- `tools\rtx.bat sdk tracy-tools` fetches the pinned Tracy tools.
- Bridge-only builds use `gradlew.bat compileNativeWin64 compileNativeArm64`,
  or `rtxremix-java/native/build_native.bat x64` or `arm64`.

The C API is reached only through JNI, never a JNA interface table. Java-side
handles stay raw `long` values, not JNA `Pointer`s or wrapper objects. Native
methods that require direct `ByteBuffer`s must never receive heap buffers;
document buffer directness on every new JNI entry point.

## Runtime And Java API Changes

A change to native API functions, structure layouts, atlas animation forwarding,
or runtime assets may span the separate native runtime, the integrated wrapper,
and the mod, so check all three layers explicitly. Renderer behavior and the
C API surface belong in dxvk-remix; marshalling and Java API types belong in
the shim.

Once the matching runtime package is published, update both architecture pins
in `packman/remix-sdk.packman.xml`, the copied header, JNI marshalling in
`rtxremix-java/native/remixbridge.cpp`, `RemixBridge.java`, `RemixAPI.java`,
affected data-holder types, and mod callers together. The copied header
matches the pinned runtime, which can be ahead of dxvk-remix `main`; never
edit it in isolation. From the repository root, restore the SDKs with
`.\tools\rtx.bat sdk packman --arch all` and rebuild the bridges with
`.\gradlew.bat compileNativeWin64 compileNativeArm64`.

`StructType.java` constants must exactly match `REMIXAPI_STRUCT_TYPE_*` in
the copied header. Parallel API additions can claim the same number; after
rebasing, assign any colliding new type the next free value in all four places:

1. `public/include/remix/remix_c.h` in dxvk-remix.
2. `src/dxvk/rtx_render/rtx_remix_specialization.inl` in dxvk-remix.
3. `rtxremix-java/native/include/remix_c.h` here.
4. `rtxremix-java/src/com/nvidia/rtxremix/StructType.java` here.

The copied header defines `REMIXAPI_VERSION_{MAJOR,MINOR,PATCH}`. The JNI
bridge passes those constants through `REMIXAPI_VERSION_MAKE(...)` to
`remixapi_InitializeLibrary`; version changes must keep the header, bridge,
facade, and runtime payload in step.

Verify the full root `build`, `test`, and `tools\rtx.bat launch` path before
requesting review. Compilation and JVM tests do not exercise JNI loading or
rendering. Open the pull request only once the runtime package that implements
the change is published and pinned: the runtime is distributed as binaries, so
the package is what makes a change public. The header and facade must never run
ahead of it.

## Tests

JVM tests live under `src/test/java/` and `gradlew.bat test` runs them all;
`TerrainStateTest` and its `terrain/` neighbours cover the chunk pipeline.
The suite does not cross the JNI boundary or render, so a rendering change is
verified by launching with `tools\rtx.bat launch` and looking, or with the
screenshot tool's shot sets (`tools\rtx.bat screenshots refresh`).

Python tooling uses `tools\rtx.bat check --tests` for its test suite.
Its shared defaults pin the demo world and art pack without selecting a
graphics preset; machine-local choices belong in ignored
`tools/config.local.json`. See `tools/AGENTS.md` for the configuration and
benchmark contracts.

Keep implementation plans and task checklists outside the repository. Source
and maintained guides are the durable record, not planning scaffolding.
