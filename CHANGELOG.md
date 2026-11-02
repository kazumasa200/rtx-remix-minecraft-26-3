# Changelog

Notable changes to RTX Remix for Minecraft, newest first. Changes that a
player, a map author, or a contributor would notice go here; internal
refactoring does not.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and the project uses [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
The version in a release is the `mod_version` in `gradle.properties`, and it is
also part of every mod file name.

## Unreleased

### Added

- On Windows, avoid Vulkan startup failures on systems that cannot share the
  game window. The original presentation mode remains available through a
  launch argument; a mistyped value warns and uses the default.

### Fixed

- Distant land, such as Distant Horizons' LOD buildings, no longer shows through
  a nearby torch's glow as pale outlines at night, and the torch's flame no
  longer vanishes against it.

## 0.4.0-pre.1 - 2026-11-01

A pre-release: lightning that lights the clouds and the ground, rain puddles
reworked, a brighter rain sky, a setting for how much memory Distant Horizons'
LOD terrain may use, and fixes for yellow lines at night and torch glow.

### Added

- Lightning lights the ground around the strike for a moment, and with Unbound
  clouds lights the cloud around the bolt from within, brightest where it
  reaches into the cloud and fading over the deck around it.
- LOD Memory, a Distance & Culling setting: how much mesh data Distant Horizons'
  terrain may hold for path tracing, from 256 to 4096 MiB, or Unlimited.
- Puddle Amount and Puddle Reflection, Materials settings: how much of the
  ground rain puddles cover, and how strongly their water reflects, from clear
  water's faint reflection up to a mirror.
- A Debug page in the settings, for views that help find rendering problems.
  Show Puddle Mask paints rain puddles red by how much water stands there.

### Fixed

- A torch's glow over the sky is no longer a deep red ring that stops at the
  skyline, and with Unbound clouds the clouds around a torch no longer turn
  pink or red.
- At night, thin yellow lines no longer glow on block sides and plants that
  line up with the camera, which showed most from high up as lines running
  straight down along the world's X and Z axes; a surface seen edge on took
  the averaged glow of every lamp, torch and lava texture.
- In rain the sky shows as grey overcast brighter than the ground instead of
  as dark as a thunderstorm's; the rain fog is thinner. A thunderstorm keeps
  its darkness. An existing install keeps its own `weather/rain.conf`; delete it
  to take the new default.
- Lightning bolts are drawn in thunderstorms again, glowing and lighting the
  ground around the strike; they had been dropped from capture.
- With Distant Horizons, the LOD terrain holds 2048 MiB of meshes by default
  again, as before 0.3.0, so on graphics cards with 8 GB of memory a higher
  Distant Horizons quality no longer shrinks it to the ground around you.
- With ScalableLux installed, receiving a chunk no longer fails: its light
  engine has no vanilla sky-light source table, and the mod now leaves such
  chunks to it.
- With Unbound clouds, the glow of a torch or lamp near the camera now spreads
  over the clouds as it does over the sky, instead of stopping at them with a
  dark outline.
- Rain puddles' reflections grow gradually from their edges to a mirror in the
  middle, instead of a water surface appearing suddenly on dry ground; the
  ground around them grows wet and a little darker; puddles cover less of the
  ground; and
  puddles grow and dry smoothly instead of in visible steps, without the short
  stutter each step used to cost.
- Puddles no longer have waves: the rain ripples them with smaller, more
  numerous rings, and once it stops they lie still like mirrors. Rivers and the
  sea keep their waves.
- With Unbound clouds, the sky no longer turns into completely different clouds
  every so often as the wind carries them, or when the rain ends.
- With Unbound clouds, their shadows no longer turn the view blue, and no
  longer slide across the land while you turn the camera.
- Caustics stay on the floor of shallow water right below you instead of
  disappearing within a block and a half of the camera.
- With Unbound clouds, the sky no longer jumps slightly brighter or darker every
  few seconds while you walk.
- What other mods draw in the world, such as Litematica's selection boxes,
  MiniHUD's overlays and Axiom's tool previews, shows over the path-traced
  image, hidden behind blocks as without RTX.
- Mobs from GeckoLib mods that draw a layer over the body, such as a pet's
  armour slot, are no longer black.


## 0.3.0 - 2026-10-28

Water caustics, skies that change from day to day, a much lighter lens, and
fixes for Distant Horizons' LOD terrain flickering and leaving holes. The same
changes went out the same day as the pre-release 0.3.0-pre.1.

### Added

- Water Caustics, a Lens setting: on the floor of sunlit water, up to about
  250 blocks away, light that the waves gather plays in a moving web. It fades
  with depth, so no line marks where it ends; a low sun draws it out along its
  direction, and it stays on floors seen at a glancing angle and on the
  shallows by a shore. Terrain and passing clouds shade it, and it fades as the
  sun sets or the rain comes.
- The Unbound sky varies from day to day: some days bring more cumulus, others
  a thicker altostratus, and the sky clouds over before the rain and clears
  after it. In single-player the clouds gather ahead of the rain.
- Rarely, on a calm day, a cloud in the shape of a creeper's head.

### Changed

- New defaults: Depth of Field 0.4, Lens Flare 0.5, Moon Intensity 0.2 and
  Cloud Coverage 0.3 of their old scales. Their sliders now read 1 at these
  defaults, so values saved for them earlier are dropped.
- Glowing blocks such as sea lanterns, redstone lamps and glowstone light their
  surroundings about as far as a torch of the same light level, instead of
  about fifteen times as much, and are drawn from about as far away, so
  hundreds of distant ones no longer fill the list of lights in use; lanterns
  shine out of their glass instead of lighting only its inside.
- The water's waves no longer repeat every ten blocks in a lattice of swells
  that stood out across open water; the surface repeats only every forty
  blocks, with no lattice.
- The lens and the Unbound clouds take far less GPU time: at 4K on an RTX 5090
  the lens went from 7 to 10 milliseconds a frame to under 3.
- With Distant Horizons, the LOD terrain's memory follows the graphics card: a
  sixteenth of its video memory, at most 2 GiB as before, so a card with less
  memory keeps less LOD terrain. Distant Horizons no longer keeps a GPU copy of
  its terrain while Remix draws it.

### Fixed

- Opening a menu while chunks are loading, the first Escape menu or inventory
  above all, no longer freezes the game, for minutes on a busy server.
- With Distant Horizons, LOD terrain no longer stays drawn over nearby land
  that Minecraft's terrain has already loaded, in worlds edited without their
  heightmaps updated either.
- The ground behind and beside you is path traced before you turn to it, so it
  casts its shadows and shows in reflections, and turning round or moving no
  longer shows holes or, with Distant Horizons, LOD terrain at arm's length
  until Minecraft builds it.
- Unbound cumulus are no longer flat-topped, sheer-sided slabs: their
  outlines change with height and their surfaces puff up, so the sky breaks
  into ragged, billowing clumps.
- Unbound clouds no longer judder while you move. The sky and its reflections
  in water stepped fifteen times a second; the clouds on screen now follow every
  frame, and their reflections thirty times a second while the camera moves.
- With Distant Horizons, the edges of the land and of the LOD terrain against
  the sky no longer flicker or show a flickering blue line, and distant land no
  longer flickers in and out of the clouds' shadows.
- With Distant Horizons, there is no gap between Minecraft's terrain and the
  LOD terrain, and the LOD terrain near you keeps its place when its memory
  budget fills; the farthest gives way first.
- The sun's flare no longer flickers.
- Seen from under water, the clouds now wobble with the waves instead of
  floating over them.
- With JourneyMap installed, villagers no longer turn black.
- Block lights on builds with open air below them, such as a platform in the
  sky or a lamp under a bridge seen from its deck, now light their
  surroundings; they glowed but cast no light.
- With Distant Horizons, distant terrain no longer flickers as it changes
  detail: the old terrain stays until the terrain replacing it is ready.
- Aerial Haze no longer fades distant land toward a sky seen earlier from
  higher up. In the rain, the land at the horizon could glow white under the
  dark overcast.
- Unbound clouds seen from above no longer turn flat white when no cloud lies
  above you, as at a low Cloud Coverage.
- At a low Cloud Coverage, sparse Unbound clouds no longer stand up as walls
  under flat tops.
- At night, Unbound clouds no longer show pale rims along their edges or a
  white band at the horizon, and no longer take on the colour of nearby lamps:
  the sky behind their thin edges is the sky beside them, with the moon's glow
  only around the moon.
- Unbound clouds no longer show pale or dark rims, or pale wisps beside them,
  where the sky is brighter on one side than the other, as toward the moon or
  the sunset.
- The glow around lamps, glowing blocks and the moon lies over Unbound clouds as
  it does over everything else; it stopped at the skyline.
- With Distant Horizons, arriving somewhere new or flying fast no longer opens
  pits in the ground where the LOD terrain gave way before Minecraft's terrain
  was drawn there.

## 0.2.1 - 2026-10-26

The fork's own look since 0.2.0: sky, clouds, lens, LOD terrain and rain, and
a much lighter frame in places full of lights.

### Added

- Sky Appearance settings: saturation, sunset warmth, sun glare, rainbows
  after rain, star brightness, high cirrus and northern aurora.
- Two cloud styles. Reimagined draws large rounded blocky clouds in one or two
  thick layers. Unbound draws soft volumetric clouds out to the horizon in
  three layers, small and large cumulus and an altostratus sheet, that cast
  shadows on the land as they drift.
- Cloud coverage, speed and Unbound shape and lighting settings.
- LOD Terrain: with Distant Horizons 3.3.4 installed, its distant terrain is
  path traced beyond Minecraft's render distance, and the view fade moves out
  to its LOD radius. Experimental; needs Auto View Fade.
- Puddles on open ground in the rain: thin sheets of clear water that the
  raindrop rings ripple. They pull back from sheltered and lower ground, so a
  roof's edge leaves no straight line.
- Lens settings: a light depth of field that focuses on what the crosshair
  rests on, and lens flare from lights, such as the sun and a held torch:
  round ghosts and a rainbow ring along the line through the screen centre,
  and a starburst on the sun.
- The sun glints on rippling water.
- Aerial Haze: distant land fades into the colour of the sky, most in low
  valleys and least on high ground.
- Unbound clouds are drawn for every pixel, sharp and with the parallax of
  real clouds: you can fly into and through them, and from above you look
  down on them, with the land showing through the gaps.

### Changed

- High Cirrus is off by default. Seen from below, its streaks fanned out from
  the zenith and the sun like smears; the slider still turns it on.
- Direct sunlight is slightly warmer.
- Block lights, item lights and beacon beams are 1.5 times as bright.
- Water is clearer: a riverbed shows several blocks down, and a bright cloud's
  reflection no longer hides it in shallow water.
- Puddles reflect more of the sky, and raindrop rings land more often and
  ripple harder.
- Unbound clouds are brighter on the sun's side and greyer on the far side,
  and the bases of the cumulus are flat rather than frayed. Their sides bulge
  and round into the base instead of standing like walls.
- Rain closes the view: distant land fades into the grey of the overcast sky
  instead of standing dark against it.
- A rainy day is brighter, and a thunderstorm about as dark as rain was, so
  neither is mistaken for night.
- Where thousands of lights are in reach, as in a city lit by sea lanterns,
  the light reach shrinks until about four thousand are drawn, the faintest
  where you stand going first. The frame rate no longer sinks there, and the
  lights around you no longer go missing or leave the floor noisy. The
  `rtxremix.chunk.maxDrawnLights` JVM property sets the count.
- The lens effects take half the GPU time with Unbound clouds and aerial haze.
- Depth of field blurs half as much at the same setting.
- The daytime sky deepens to a richer blue overhead and pales to white at the
  horizon, as a clear sky does: its scattering now follows measured
  atmospheres (aerosols of an ordinary clear day, ozone, and multiple
  scattering), the glow around the sun stays close to it instead of greying
  the whole sky, and the blue overhead that the tone mapping bleaches is
  deepened back. Sky Saturation scales that, and Sun Glare now sets the glow
  close around the sun.
- Unbound clouds are lit more like real clouds: sunlit tops are bright white,
  their shaded sides cooler, and their bases grey instead of black, lit by the
  ground below and by light scattered deep inside the cloud. Sunlit clouds
  next to the sun are no longer duller than the sky behind them.
- The sun mirrored on water and puddles glows, and its glints throw streaks in
  the same six directions as the sun's starburst, scaled by Lens Flare; the
  sun's reflection is wider, and daytime bloom spreads from the brightest
  glints.

### Fixed

- Distant Horizons no longer turns Minecraft's clouds off, which removed
  every cloud while it was installed.
- Distant leaves no longer turn violet, and distant faces no longer pick up
  lines or a neighbour's glow, with high-resolution LabPBR packs. Their
  normal and specular maps stay out of the block and item atlases, and the
  atlases are stitched without a border around each sprite, which keeps every
  sprite on its own power-of-two grid so the runtime's mips never blend it with
  a neighbour. A 128-texel pack's atlases are a quarter of the size the wider
  border needed, which saves about 6 GB of video memory.
- Leaves, grass and packed ice with LabPBR subsurface scattering no longer
  glow far brighter than their surroundings at night and in the rain. The
  radiance cache no longer shades them where they are seen directly.
- `rtx screenshots refresh --resource-pack local:` accepts a pack already in
  the instance's resource packs folder.
- Unbound clouds no longer show a fine diagonal grid where the sky overhead is
  magnified.
- Seen from above the Unbound clouds, the land below keeps its colours, and
  the clouds over it are the same clouds as the ones in the sky, not a flat
  layer that appears as you climb past them.
- Distant ridges no longer show a dark outline that crawls along them against
  the sky: aerial haze now covers the pixels along a silhouette.
- In the rain, the aerial haze, the Unbound clouds over the land and the depth
  of field are no longer undone wherever rain falls.
- The aerial haze no longer flickers toward the edges of the screen while you
  look down or into trees.
- The bases of Unbound clouds near the horizon no longer show grainy patches.
- The bases of Unbound clouds overhead no longer show grain, or dark lines
  along their outlines.
- Distant towers in the rain no longer glow against the sky where lights below
  brighten the fog.
- Your own body no longer drops out of shadows and reflections where many
  entities are in view, such as rooms full of item frames.
- The sun's flare and starburst no longer shine through ceilings, glass and
  other blocks between you and the sun.
- The flare from a torch in your hand starts at the flame on screen.
- When the time of day jumps, as with `/time set`, sleeping or a camera path,
  riverbeds and shaded ground no longer keep glowing with the old daylight
  for half a minute: the runtime's radiance cache is cleared.
- Benchmarks are no longer held at 30 FPS after a minute by Minecraft's
  inactivity frame limit.

## 0.2.0 - 2026-10-25

The port to Minecraft 26.3.

### Changed

- Minecraft Java Edition 26.3 on Fabric, with Java 25.
- The game heap defaults to a fixed 16 GB.
- Changing a Block Lights multiplier updates the loaded lights in place
  instead of rebuilding all terrain, which could fill video memory.

### Added

- Raindrop rings on open water while it rains.
- Japanese translation.

### Fixed

- Distant terrain no longer shows purple tints and lines from neighbouring
  atlas sprites; every atlas sprite gets at least an eight-texel border.
- See-through sleeves on the first-person arm show the arm instead of black.
- Slab sides next to leaves or glass no longer vanish depending on the view.

## 0.1.0 - 2026-10-23

The first public release, a beta.

### Added

- Path-traced rendering of Minecraft Java Edition 1.21.1 through RTX Remix:
  terrain, entities and block entities, particles, the sky, and clouds, with
  baseline LabPBR textures built into the mod JAR through Fabric's `fabric` pack.
- Lighting that follows the game: time of day, weather, being underwater, and
  per-dimension skies, with the End's constant dark dome and a black sky with
  no ambient where a dimension has none. Map authors can give zones and biomes
  their own looks through gameplay-driven conf profiles installed beside the
  mod, with an authoring guide.
- Block lights from resource-pack rules, a Light Reach setting and an Auto
  light cull distance, flames and lights on hand-held items, and the `G` key
  to put out and relight what the local player holds.
- The Copper Beacon, a placeable and craftable directional lamp: dye and
  saturation, intensity, cone angle, redstone control, and static, rotating,
  oscillating, or patrolling motion that every player sees the same way.
- An RTX Remix settings screen in Options, a button into Remix's own Graphics
  Settings, the `/rtx` command tree, `F8` to toggle path tracing, and
  session-only quality presets through `/rtx preset <name>` or
  `-Drtxremix.preset=<name>`.
- Camera paths: record a flythrough by walking it and replay it on wall-clock
  time or one fixed step per frame. Still shots keep a pose together with its
  time of day, weather, path-tracing state, and cloud style.
- Procedural clouds, selectable in place of vanilla's.
- Steadier frames at high render distance: paced chunk delivery and unloads
  in single-player, a hold behind the loading screen after joining a world,
  and allocation-free hot paths that keep garbage collection out of the frame.
- A self-contained mod JAR that any Fabric launcher can install on its own,
  and a Windows installer for Prism Launcher and the Microsoft launcher that
  sets up Java, Fabric, the mod, and tuned JVM arguments, and repairs,
  upgrades, uninstalls, or exports an install.
- Developer tools under `tools/` through the `rtx` CLI: launching, benchmarks
  on the pinned demo world, built by [BlockWorks](https://www.blockworks.uk/),
  and the LabPBR art pack, created by an art team at NVIDIA, or on locally
  staged fixtures, JFR and Tracy profiling, and the contributor checks.
- Developer tools launch Minecraft through a Prism Launcher data folder for
  each checkout, signed in to a Microsoft account that owns Minecraft: Java
  Edition. `tools\rtx.bat prism signin` signs a checkout in, and
  `launch.jvmArgs` carries developer JVM options.
