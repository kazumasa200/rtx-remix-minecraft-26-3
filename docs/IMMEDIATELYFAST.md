# ImmediatelyFast compatibility

The optional adapter targets ImmediatelyFast 1.17.1+26.3. It uses that artifact
as a compile-only API dependency; the renderer does not bundle its JAR or classes.
The compatibility Mixin configuration applies only to that supported version,
including its `+26.3` build metadata. Other versions warn once at startup and
skip the map/sign adapter rather than preventing the game from starting. Their
map/sign capture is untested and may be incorrect; this is launch tolerance,
not a claim of rendering compatibility. Qualify another version against its
actual implementation before extending the supported set.

The general glyph-page sizing, upload mirroring and release fixes target
Minecraft classes and remain active independently of this version gate.
ImmediatelyFast's own optimizations and settings are never disabled by it.

## Font pages

Mirrors use the destination GPU texture's actual width and height. Native-image,
packed-buffer and bitmap-staging uploads retain their existing conversions.
ImmediatelyFast's atlas sizing and resource-pack compatibility decisions remain
active. Closing a font texture removes only its mirror incarnation, and font
replacement or release invalidates prepared glyphs. Live pages survive world clears.

A 1024-square RGBA mirror needs 4 MiB per page; font usage determines the page
count. Glyph resolution, coverage and normalized texture coordinates are preserved.

## Maps

The adapter records the image-backed id returned by `prepareMapTexture` before
atlas substitution. During synchronous Remix collection, that id selects the
already-captured map image and vanilla 0..1 UVs. The raster atlas id is restored
in `finally`, including on exceptions. Raster collectors retain ImmediatelyFast's
atlas and UV adjustment. Decorations, labels, positions and transforms remain in
Minecraft's map render method. No companion atlas, readback or GPU fence is added.

## Experimental sign buffering

Raster collectors and dynamic text keep the original submission operation.
When ImmediatelyFast marks text cacheable, Remix replays cached local glyphs
instead of capturing a GPU-only sign atlas. Preparation follows vanilla integer
centering, line positions, colors, filtering and outline state, while replay
uses the current pose and light. Existing Remix consumers preserve text depth.

Equal contents with the same layout share entries; different font, filtering,
width, line height and outline layouts coexist. Preparation and emission use the
same glyph path as ordinary Remix text. Layout starts from the original messages
because Minecraft's formatted-line cache does not include width or font.
Glyph-generation changes invalidate all entries. Obfuscated text remains dynamic.
Cache-hit lookup reuses a render-thread probe. Entries expire after five seconds without
access and retained glyph weight is bounded at 65,536. Resource/world teardown
releases the cache.

ImmediatelyFast's feature batching, GUI item batching, text lookup, sorting and
framebuffer optimizations remain enabled according to its configuration. Remix
captures world submissions before the raster feature stage.
