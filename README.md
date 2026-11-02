# RTX Remix for Minecraft 26.3

**English** | [日本語](README.ja.md)

Play Minecraft Java Edition 26.3 with real-time path tracing on a GeForce RTX
GPU, powered by NVIDIA RTX Remix.

This is a community fork of NVIDIA's
[RTX Remix for Minecraft](https://github.com/NVIDIAGameWorks/rtx-remix-minecraft),
which targets Minecraft 1.21.1. The fork ports it to 26.3 and gives it its own
sky, clouds, lens effects and rain. NVIDIA does not maintain or support this
fork; report its problems [here](https://github.com/kazumasa200/rtx-remix-minecraft-26-3/issues),
not upstream.

![The sun over the sea, with Unbound clouds, a starburst and its glint on the water](docs/images/water-glint.webp)

| ![Unbound clouds in a blue sky over a treehouse among cherry trees](docs/images/treehouse-clouds.webp) | ![Sunlight and a starburst between skyscrapers](docs/images/skyscrapers-sun.webp) |
|---|---|
| Unbound clouds and the sky | Sunlight between skyscrapers |
| ![Sunrise colouring the clouds and the water](docs/images/sunrise-water.webp) | ![Clouds reflected in a river between snowy forests](docs/images/snowy-river.webp) |
| Sunrise on the water | Clouds reflected in a snowy river |
| ![A wet city street in the rain, reflecting the buildings](docs/images/rainy-street.webp) | ![Street lights reflected on a wet road at night](docs/images/rainy-night-street.webp) |
| A wet street in the rain | Street lights on a wet road at night |
| ![Torches lighting village houses at night](docs/images/village-torches.webp) | ![An aurora and the moon over a snowfield](docs/images/aurora-snow.webp) |
| Torchlight in a village at night | Aurora and moonlight over snow |
| ![City lights across the water on a misty night](docs/images/misty-city-night.webp) | ![Overhead wires and a lamp in the night rain](docs/images/night-rain-wires.webp) |
| A city across the water on a misty night | Overhead wires in the night rain |
| ![Sunlight playing on the floor of a shallow pond](docs/images/pond-caustics.webp) | ![Light playing on the sea floor, seen from under water](docs/images/underwater-caustics.webp) |
| Light on the floor of a pond | Light on the sea floor, from under water |
| ![Unbound clouds at sunset over the city and the sea](docs/images/sunset-clouds-city.webp) | ![A lens flare from a tower's lights over the city in the rain](docs/images/rain-tower-flare.webp) |
| Sunset clouds over the city | A lens flare in the rain |
| ![Lamps along a park path in the rain, reflected on the wet ground](docs/images/rainy-park-lamps.webp) | ![Park lamps glowing in the night fog, with a lens flare](docs/images/night-park-fog.webp) |
| Lamps along a park path in the rain | Park lamps in the night fog |
| ![Bamboo, a lamp and lit skyscrapers by a river at night](docs/images/bamboo-river-towers.webp) | ![Looking up at a city plaza and its lit towers on a foggy night](docs/images/foggy-city-plaza.webp) |
| Bamboo and skyscrapers by a river at night | A city plaza on a foggy night |

The sea and treehouse screenshots were taken in the
[showcase world](#showcase-world-and-art-pack) with the RTX art pack; the others
in other worlds and resource packs.

## What This Fork Adds

- **Minecraft 26.3.** The port follows vanilla's render-state and frame-graph
  pipeline.
- **A physically based sky.** The atmosphere is computed from measured values,
  aerosols and ozone included, with multiple scattering, so a clear sky
  deepens to blue overhead and pales to white at the horizon. Rainbows after
  rain, high cirrus and a northern aurora come with it.
- **Volumetric clouds.** Unbound draws soft clouds for every pixel out to the
  horizon, with white sunlit tops and grey bases, and casts their shadows on
  the land; you can fly into them. Reimagined draws large, rounded blocky
  clouds.
- **Lens effects.** A light depth of field that focuses on what the crosshair
  rests on, lens flare and a starburst on the sun, distant haze, and glints on
  rippling water.
- **Rain.** Puddles form on open ground, and distant land fades into the
  overcast.
- **Distant terrain.** With Distant Horizons 3.3.4 installed, its terrain
  beyond the render distance is path traced too (experimental).
- **Lighter in busy places.** Where thousands of lights are in reach, as in a
  city lit by sea lanterns, about 4,000 are drawn, so the frame rate holds.

[Settings](docs/SETTINGS.md) lists every option and
[CHANGELOG.md](CHANGELOG.md) every release.

## What To Expect

This is a beta. Upstream was built as a technology showcase and tuned against
one showcase world, built for that project by
[BlockWorks](https://www.blockworks.uk/) (see
[Showcase World And Art Pack](#showcase-world-and-art-pack)), and the places
that world visits are where it looks and runs best. The fork is developed and
checked on one Windows machine with an RTX 5090. It ships with
[known issues](https://github.com/kazumasa200/rtx-remix-minecraft-26-3/issues),
and there are certainly bugs yet to be discovered.

Three areas have had less attention than the rest:

- **Other mods.** It has had limited testing alongside other mods, and a
  renderer that takes over the scene the way this one does is likely to
  conflict with anything that changes rendering or the chunk pipeline.
  Distant Horizons 3.3.4 is the one tested companion.
- **A full playthrough.** Blocks, mobs, dimensions, and situations the showcase
  never reaches have had less care, and some will look wrong or run slowly.
- **Other versions.** It targets Minecraft 26.3 only. For 1.21.1, use
  [upstream](https://github.com/NVIDIAGameWorks/rtx-remix-minecraft).

If something breaks, [report it](docs/PLAYING.md#reporting-a-bug); if you can
fix it, see [Contributing](#contributing).

## Download

Download the release archive from the
[Releases page](https://github.com/kazumasa200/rtx-remix-minecraft-26-3/releases/latest).

## Install in three steps

1. Extract the release archive.
2. Double-click `install.bat` and follow the menu.
3. Launch the **NVIDIA RTX Remix for Minecraft** profile from your launcher.

The step-by-step version, with launcher advice and troubleshooting, is in
[Playing](docs/PLAYING.md).

## Requirements

- Windows 10 or 11, x64. This fork's releases carry no ARM64 build.
- Minecraft Java Edition 26.3, on a Microsoft account that owns the game.
- A GeForce RTX 30-series or newer GPU with NVIDIA driver 616.92 or newer.
- About 5 GB of free disk space while installing; about 3 GB stays in use.
- An internet connection while the installer runs.
- 16 GB of system memory is recommended.

Java 25, Fabric, and the RTX Remix runtime are installed for you.

Building and running from source has its own
[prerequisites](docs/GETTING_STARTED.md#prerequisites), including Prism
Launcher, signed in to a Microsoft account that owns Minecraft: Java Edition.

## Windows Vulkan presentation

On Windows, a separate presentation window avoids a Vulkan startup failure on
some systems without changing image quality. To use the original presentation
on a system that supports it, add `-Drtxremix.presentation=parent` to your
launcher's JVM arguments and restart; see [Tuning](docs/TUNING.md#presentation).

## Crash Reports And Privacy

The mod collects no data of its own. The RTX Remix runtime it carries can send
basic session information and crash reports to NVIDIA. Its First Use Guide asks
about both, with both on by default, and the Permissions tab of the RTX Remix
Graphics Settings menu changes them at any time.
[Crash Reports And Privacy](docs/PLAYING.md#crash-reports-and-privacy) lists
what is sent.

## What it does

**Every block, mob, and item is lit by a path tracer.** Light bounces off walls,
through glass, and out of torches the way it does in a film renderer, so shadows,
reflections, and the colour a nearby block casts on its neighbours are all
computed rather than painted in.

**The look follows the game.** Time of day, weather, and being underwater each
bring their own lighting, and a map author can attach a look to a place in the
world. Nothing needs switching on.

**A new block.** The Copper Beacon is a directional lamp you can place or
carry, and its beam is a real light that picks out walls, rain, and anything
standing in it.

**Camera flythroughs.** Record a path by walking it, then replay it, for a video
or for an exact before-and-after comparison.

**A settings screen in the game.** Distances, framerate, sky, clouds, lens,
lighting, exposure, and materials, all in Options, with no config files to
edit, and a button into Remix's own Graphics Settings for the quality tier.

## Showcase World And Art Pack

Two optional extras go with the mod. The developer launcher in
[`tools/`](tools/README.md) downloads both the first time it runs and launches
into the world with the pack on.

- **The showcase world**, the map the renderer was tuned against, was built for
  this project by BlockWorks: [website](https://www.blockworks.uk/),
  [YouTube](https://www.youtube.com/@BlockWorksYT),
  [X](https://x.com/BlockWorksYT), and
  [Instagram](https://www.instagram.com/blockworks/).
- **The RTX art pack**, a texture resource pack, was created by an art team at
  NVIDIA. Its normal and specular maps follow the
  [LabPBR standard](https://shaderlabs.org/wiki/LabPBR_Material_Standard), so
  other renderers and shader packs that read LabPBR may be able to use it too,
  but it has only been tested with this mod. Its `_sss` subsurface maps are
  this mod's own addition, described in
  [Resource Packs](docs/RESOURCE_PACKS.md).

## Start Here

| You want to | Read |
|---|---|
| Play with path tracing | [Playing](docs/PLAYING.md) |
| Check the known issues | [Issue tracker](https://github.com/kazumasa200/rtx-remix-minecraft-26-3/issues) |
| Play on a server | [Multiplayer](docs/PLAYING.md#multiplayer) |
| Configure the renderer | [Settings](docs/SETTINGS.md) |
| Look up a command, a key, or a mod-added block | [In-Game Guide](docs/IN_GAME_GUIDE.md) |
| Give your own map its own lighting | [Gameplay-driven profiles](packaging/rtx-remix/mods/minecraft-logic/README.md) |
| Make a texture pack this renderer understands | [Resource Packs](docs/RESOURCE_PACKS.md) |
| Record a camera flythrough | [Camera Paths](docs/CAMERA_PATHS.md) |
| Install, repair, or uninstall in detail | [packaging/INSTALL.md](packaging/INSTALL.md) |
| Build and run from source | [Getting Started](docs/GETTING_STARTED.md) |
| Tune the developer knobs behind the renderer | [Tuning](docs/TUNING.md) |
| Diagnose a rendering or native integration problem | [Rendering Diagnostics](docs/RENDERING_DIAGNOSTICS.md) |
| Contribute a change | [CONTRIBUTING.md](CONTRIBUTING.md) |
| Report a bug | [Reporting a Bug](docs/PLAYING.md#reporting-a-bug) |
| Report a security vulnerability | [SECURITY.md](SECURITY.md) |
| See what changed | [CHANGELOG.md](CHANGELOG.md) |

## Source And Tooling Layout

| Path | Purpose |
|---|---|
| `src/` | Mod implementation and JVM tests |
| `rtxremix-java/` | Minecraft-agnostic Java facade and JNI bridge over the RTX Remix C API |
| `tools/` | Developer launcher, benchmarks, profiling, SDK staging, and development installation |
| `packman/` | Packman bootstrap and runtime package pins |
| `packaging/` | Public installer, runtime defaults, and distribution payload |
| `docs/` | Player, authoring, and source-build guides |

NVIDIA maintains some release and CI tooling for upstream outside this
repository. It is not required by the public Gradle build, the developer tools,
or the installed mod.

## Contributing

External contributions are welcome, and the gaps above are where help matters
most: compatibility with other mods, coverage of the blocks, mobs, and places a
real playthrough reaches, and performance on hardware we do not have. Fork this
repository, create a development branch, and submit a pull request. Sign off
each commit with `git commit -s` to certify it under the
[Developer Certificate of Origin](https://developercertificate.org/); there is
no Contributor License Agreement to sign. Your GitHub handle and GitHub's
no-reply email address are fine for the commit and the sign-off. See
[CONTRIBUTING.md](CONTRIBUTING.md) for the full guidelines.

## License

This project is licensed under the Apache License, Version 2.0 (`Apache-2.0`).
See the [LICENSE](LICENSE) file. Attribution notices are in [NOTICE](NOTICE),
and third-party software distributed with this project is documented in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Release archives also carry
the RTX Remix runtime, whose NVIDIA components are covered by their own license
terms; THIRD_PARTY_NOTICES.md lists them.

NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR
MICROSOFT. Minecraft is a trademark of Mojang Synergies AB.

NVIDIA, GeForce, and RTX are trademarks and/or registered trademarks of NVIDIA
Corporation in the U.S. and other countries.
