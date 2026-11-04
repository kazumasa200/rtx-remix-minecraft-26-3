# Settings

The mod adds its own settings screen to the game. Open **Options**, then **RTX
Remix Settings...**. It is available from the title screen and from the pause
menu. Most controls apply as you move them. Block Lights and Block Glow
Intensity apply when you leave their page: Block Glow Intensity rebuilds
terrain, and Block Lights updates every loaded light.

Tooltips explain each control and mark a value you have changed as
`(overridden)`.

## Performance

The renderer's quality tier is Remix's own: pick it in the runtime's Graphics
Settings menu, which the **RTX Remix Graphics Settings** button on the landing
page opens. Minecraft receives no keyboard or mouse input while a Remix menu is
open; close it with its own close button or press **Alt+X** to return. The
menu's button for switching to the other Remix menu works too.
`/rtx preset <name>` sets the same tier for the current session only, for a
quick comparison without opening the menu; it changes nothing on these pages
and is not saved.

Distance controls trade scene coverage for performance. The two cull distances
cannot exceed Minecraft's render distance, and the far right of each of those
sliders is Auto, which follows it. Light culling chooses which chunks are
considered; light reach then decides whether each light is bright enough to
matter where you stand. Brighter lights reach further. Where more than about
four thousand lights are in reach, as in a city lit by lamps, the reach shrinks
on its own until they fit, so the lights faintest where you stand go first.
Auto view fade never overrides a stronger situational profile, so a map that
authors its own fade keeps it.

**LOD Memory** sets how much mesh data Distant Horizons' terrain may hold for
path tracing, 2048 MiB by default; the GPU uses about twice that. When it is
full, the farthest LOD terrain gives way, so a higher Distant Horizons quality
or render distance draws it less far. Raise it to see further if video memory
allows, or lower it if video memory runs short. Unlimited, past 4096 MiB, sets
no cap at all; Remix then holds everything Distant Horizons shows.

The background frame cap only lowers Minecraft's own Max Framerate;
Unlimited leaves that setting in charge. A running benchmark is never capped,
since it measures the renderer.

## Lighting

This page shapes the sky's light and appearance, clouds, and block lights.
Clouds still follow Minecraft's own cloud option: Off removes them regardless
of the selected style, while Fast and Fancy draw the same geometry here.

**Sky Appearance** adjusts the generated sky on top of its physical
scattering: saturation, which also sets how far the clear sky overhead is
deepened back toward the blue the tone mapping bleaches, a warm band toward a
low sun, the glare around the sun, rainbows opposite a low sun as rain clears,
and star brightness. It also
controls the high sky layers drawn over the dome like the stars: wispy cirrus
streaks, off by default, whose coverage regenerates their texture once the slider settles,
and aurora curtains across the northern sky on clear nights, by default only
where it is cold enough to snow. Aerial Haze fades distant land into the
colour of the sky at the horizon, thickest in low valleys and thinnest on high
ground, and thicker in the rain, when distant land melts into the overcast's
grey; the mod draws it over the image.

**Cloud Style** picks one of four looks. Classic voxelizes Minecraft's own
clouds, Procedural draws puffy meshes, and Reimagined draws large rounded
blocky clumps in one or two thick layers; all three are world geometry, so
they cast shadows but end where the view distance does. Unbound marches soft
volumetric clouds for every pixel of the screen, out to the horizon, over the
sun, moon and high sky layers, in three layers: small cumulus just above
Minecraft's cloud height, large cumulus above them, and a thin altostratus
sheet far higher, each shading the ones below. You can fly into and through
them, and from above you look down on them with the land showing through the
gaps. They are drawn over the image rather than as world geometry, so the
runtime casts no shadows from them; the shadows they drop on the land, drifting
with them, are drawn over the image too, as dark as the Cloud Shadows amount
(at the default about as dark as a block's shadow at noon). The Unbound sky
changes a little from day to day, with more cumulus on some days and a thicker
altostratus on others, and it clouds over before the rain and clears after it. In single-player the clouds gather
ahead of the rain; on a server they gather as it starts, since only the server
knows when it will come. Rarely, on a calm day, a cloud takes the shape of a
creeper's head.
Coverage and speed apply to Reimagined and Unbound; for Unbound, Coverage sets an
ordinary day's cover, which the weather moves a little either way. Coverage, like
Moon Intensity, reads 1 at its default and scales from there; the default
covers about three tenths of the sky. The other
Unbound rows set
the large cumulus thickness (the small cumulus are half as tall), density,
brightness, edge detail and how far rain thickens them toward an overcast sky.

**Block Lights** opens a table of multipliers for normal light brightness,
grouping related block lights into a row; the far left is Off. The Copper
Beacon is separate: right-click a placed beacon for its own controls. Every
vanilla block that gives off light has a row, except the ones that come in
large fields: lava, magma, portals, glow lichen and sea pickles glow through
their surfaces instead, so **Block Glow Intensity** under Materials controls
them. Emitting surfaces are not sampled directly, so a room lit only by them
can be darker than one with separate lights. At 1 a light block lights about
as far as a torch of the same light level: a glowing block such as a sea
lantern, and a lantern's glass, shine from just outside their faces.

The table commits changed rows together when you leave, then updates the
loaded block lights in place over the next few frames; terrain is not rebuilt.
It has its own Reset: the Lighting page's Reset does not reach into it, but
Reset All on the landing page does.

## Image & Exposure

This page adjusts tonemapping and eye adaptation. Taking control of a value
here masks that option in a map's lighting profiles for the rest of the
session. Only these controls and Smooth Relief Maps on the Materials page have
that rule; see [Where A Setting Can Come From](#where-a-setting-can-come-from)
below.

**Lens** draws a camera lens over the path-traced image. Depth of Field blurs
what is nearer or farther than the block under the crosshair, focusing over a
third of a second as an eye would; your own hand stays sharp. Depth of Field
and Lens Flare read 1 at their defaults, a light blur and a faint flare. Lens Flare draws
round ghosts and a rainbow ring along the line from a bright light through the
screen centre, for the sun and for lamps within 64 blocks such as a held torch,
and a starburst on the sun; a light behind a block or lost in the daylight does
not flare, nor does the sun behind a roof or glass. It also sets the glow
around the sun mirrored on water and puddles and the streaks on its glints.
Water Caustics draws the moving web of light that the waves gather on the
floor of sunlit water up to about 250 blocks away, sharpest a few blocks down
and fading out by about thirty; land between the floor and the sun and the
Unbound clouds' shadows put it out, and it fades as the sun sets or the rain
comes. All three are drawn by the mod, not the Remix
runtime, and 0 turns any of them off.

## Materials

**Block Glow Intensity** changes the light emitted by glowing block surfaces,
rather than the separate sources in Block Lights. Applying it recreates shared
terrain materials and rebuilds the loaded terrain, so edits wait until you
leave the page.

**Smooth Relief Maps** samples normal and height maps smoothly while block
textures keep their pixelated look, which removes banding from surface bumps
and parallax depth. It is a Remix option, so its default is the value Remix
loaded at launch, and a change applies at once.

**Puddle Amount** sets how much of open ground rain puddles cover. In the rain
the ground around them grows wet and a little darker, and water stands in the
deeper parts.

**Puddle Reflection** sets how strongly the standing water reflects. At 0 it
reflects as clear water does, a few percent seen from above, which hardly shows
on bright ground; higher values make part of it metal, coloured and darkened as
the ground beneath, up to a mirror at 1.

Both rebuild every puddle, so they apply once the slider stops.

**Puddles in Dry Weather** keeps the ground soaked and the puddles standing
when it is not raining, in biomes where it rains; without rain their water is
still. Off, they dry up about two minutes after the rain stops.

## Debug

Views for finding rendering problems, never needed to play. Each is a switch
and shows at once.

**Show Puddle Mask** paints rain puddles red, by how much water stands there:
where they lie and how their edges fade.

**Show Dome Clouds Only** leaves the Unbound clouds as the runtime draws them on
the sky dome, without the lens drawing them again per pixel or drawing their
shadows: whether a flaw in the clouds comes from the per-pixel clouds.

Add a debug view here, as a switch in `SettingGroup.DEBUG_VIEWS`, rather than as
code put in for one investigation and taken out after it.

## Resetting

Every page has **Reset to Defaults**, which restores the controls drawn on that
page after asking for confirmation. The landing page has **Reset All**, which
restores every setting the mod owns, including the Block Lights table.

Both save immediately.

Restart the game after a reset if you had changed anything on the Image &
Exposure page or Smooth Relief Maps. Those values are handed to the Remix
runtime, which has no operation for taking a value back, so a reset writes the
default rather than releasing the control. A restart hands them back to the
lighting profiles.

## Where A Setting Can Come From

Rendering options can come from several layers. When they disagree, the later
one in this list wins.

**Conf files.** `rtx.conf` in the game folder sets the baseline look. On top of
it, the gameplay profiles fade in and out with the time of day, the weather,
whether you are underwater, and which biome or authored zone you are standing
in. This is the layer a map author writes, and it is described in
[Gameplay-driven profiles](../packaging/rtx-remix/mods/minecraft-logic/README.md).

**This settings screen.** Most controls drive the mod directly and never argue
with a profile. Image & Exposure and Smooth Relief Maps are different: moving
a control, or loading a saved value for it, hands that option to you until you
restart the game, masking any profile that wanted to author it. If you decide
the picture is too dark, a map cannot darken it again a minute later.

**The Remix runtime's overlays, under Alt+X.** The developer menu is the
advanced one, aimed at people tuning the renderer itself; the Graphics Settings
menu opens from the button on the landing page. Remix saves changes made in
either separately from the mod's own settings. A Graphics Settings change wins
over the profiles. A developer-menu change can be held back by a stronger
layer, such as a profile or a value this settings screen has written. The menu
then reports the option as blocked and offers to unblock it, which removes the
stronger value. If a setting will not stick, that is the first place to look.
The developer menu's Option Layers view names the owner of any option.

## The Settings File

Your changes live in `config/rtxremix.json` inside the game folder. It is
written when you close a settings screen.

The file records only what differs from the default. Return a control to its
default and its entry disappears; when nothing differs, the file is deleted.
A missing or empty file means you are on defaults.

The file carries a version. A file written by a different version is ignored
rather than converted, because the meaning of a stored value can change between
versions and a silently misread value is worse than a reset. An entry naming a
setting that no longer exists, or holding a value outside the setting's range,
is skipped and reported in `logs/latest.log`. The rest of the file still loads.

You can delete the file to start over.

## Developer Notes

The registry is built after `initializeRemix()` because its getters and
appliers bind to the live renderer managers. A renderer reinitialization path
must rebuild those bindings. `RtxSettingsFactory` defines controls,
`RtxOptionFactory` adapts them to vanilla `OptionInstance`, and
`RtxSettingsCategoryScreen` commits pending values on close.

### Deferred Does Not Mean Apply On Release

`RtxSetting.deferSliderApply()` makes
`RtxOptionFactory.sliderApplyPolicy` return `applyValueImmediately() == false`.
In Minecraft 26.3, `OptionInstance.OptionInstanceSliderButton` then rearms
`delayedApplyAt = now + 600 ms` on each drag step and applies the pending value
from `extractWidgetRenderState` after that pause. Its `onRelease` does not
apply it. The category's `close()` also calls
`OptionsList.applyUnsavedChanges()`.

An expensive applier can therefore run once per long pause during a drag, not
only once at its end. `clouds.vanillaEdgeSmoothness` and `clouds.size` accept
that debounce. Block Glow Intensity does not use `deferSliderApply`: movement
only stores the pending multiplier. On close,
`BlockTextureManager.commitTerrainEmissionMultiplier()` reports whether it
changed; only then does `RtxRemixMod.rebuildTerrainForMaterialSettings()`
recreate shared materials and recapture terrain. Keep this separate from the
Block Lights transaction.

Ordinary `OptionInstance` sliders apply live, but their change-consumer runs only
when the value differs from the option's current value. Returning to a displayed
default is still a real write when the old value differs. A runtime-backed
Image & Exposure setting remains a User Settings opinion even after that reset;
the API has no clear operation.

### Settings Backed By A Remix Option

`RtxSettingsFactory.remixBool` binds a boolean Remix option, such as Smooth
Relief Maps, without a default authored in Java. It reads the option once
through `GetConfigVariableResolved` while the registry is built, before the
settings file loads, so the default is whatever `rtx.conf` and Remix's own
saved settings resolved to. After that the setting reports its own writes.
Remix resolves a `SetConfigVariable` write at a later option update, and a read
before then returns the previous value, so reading the option back after a
click could save or display the old one. The page therefore does not follow
later changes made in a Remix menu. Once the mod has written the option, a
developer-menu edit goes to the `rtx.conf` layer, beneath the mod's User
Settings value, so Remix reports it as blocked. Unblocking it there removes the
mod's value, and the page keeps showing its last value until the next launch.

A runtime that does not define the option fails the startup read. The setting
is then left off the page and the log names the option, so an older runtime
still starts.

### Widget Layout And Labels

`OptionListWidget` normally pairs two 150 px controls per row, or uses one
310 px control. A `Label: Value` string has a 146 px budget after its margins;
the 98 px footer buttons have 94 px. Overflow scrolls as a marquee through
`ClickableWidget.drawScrollableText`, rather than wrapping. A screenshot then
catches an arbitrary clipped label or value.

When adding a control, register its widest value in `SettingsRowWidthTest` and
add its label and tooltip in `assets/rtx-remix-renderer/lang/en_us.json`.
The test measures real default-font advances. Keep values short and shorten
labels before values; an overflowing existing label is not a precedent.

Do not refresh an `OptionsSubScreen` with `rebuildWidgets()`: its persistent
`HeaderAndFooterLayout` would acquire another list and dark background.
Open a fresh screen instance, as the settings reset paths do.

## Going Further

| You want to | Read |
|---|---|
| Look up a command or a key | [In-Game Guide](IN_GAME_GUIDE.md) |
| Give your own map its own lighting | [Gameplay-driven profiles](../packaging/rtx-remix/mods/minecraft-logic/README.md) |
| Make a texture pack this renderer understands | [Resource Packs](RESOURCE_PACKS.md) |
| Record a camera flythrough | [Camera Paths](CAMERA_PATHS.md) |
| Install, repair, or uninstall | [Playing](PLAYING.md) |
