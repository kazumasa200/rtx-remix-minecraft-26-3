# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0
import struct
from pathlib import Path

import pytest

from rtx_tools.runtime_patch import shadow
from rtx_tools.runtime_patch.passes import ShadowAbsorption, SkyFade
from rtx_tools.runtime_patch.spirv import Module, module_size

ROOT = Path(__file__).resolve().parents[2]
RUNTIME = ROOT / "remix-runtime" / "win64" / "d3d9.dll"
# The composite module of minecraft-rtx2-a00da12a and the one word the old on-disk patch rewrote.
COMPOSITE_OFFSET = 0x12431F40
GUARD_OPERAND_WORD = 33541


@pytest.fixture(scope="module")
def composite() -> bytes:
    if not RUNTIME.is_file():
        pytest.skip("no runtime staged")
    with RUNTIME.open("rb") as f:
        f.seek(COMPOSITE_OFFSET)
        blob = f.read(4 * 1024 * 1024)
    size = module_size(blob)
    if size != 147208:
        pytest.skip("not the a00da12a runtime")
    return blob[:size]


def test_sky_fade_rewrites_exactly_the_old_one_word_patch(composite):
    module = Module.from_bytes(composite)
    assert SkyFade().apply(module) == 1
    before = struct.unpack(f"<{len(composite) // 4}I", composite)
    changed = [i for i, (a, b) in enumerate(zip(before, module.words, strict=True)) if a != b]
    assert changed == [GUARD_OPERAND_WORD]
    assert (before[GUARD_OPERAND_WORD], module.words[GUARD_OPERAND_WORD]) == (1039, 848)


def test_sky_fade_leaves_a_module_without_its_pattern_alone():
    # A header and one OpCapability Shader: no primaryMiss, nothing to do.
    words = [0x07230203, 0x00010500, 0, 10, 0, (2 << 16) | 17, 1]
    module = Module(words)
    assert SkyFade().apply(module) == 0
    assert module.words == words


# A shadow ray-query module of the a00da12a runtime with one thick-translucent site.
SHADOW_OFFSET = 0x008B70C0


def test_the_caustics_water_marker_is_the_water_materials_distance():
    import re

    from rtx_tools.runtime_patch import caustics

    java = (
        Path(__file__).resolve().parents[2]
        / "src/main/java/com/nvidia/rtxremix/minecraft/texture/BlockTextureManager.java"
    ).read_text(encoding="utf-8")
    found = re.search(r"static final float WATER_MARKER_DISTANCE = ([0-9.]+)f;", java)
    assert found, "BlockTextureManager.WATER_MARKER_DISTANCE not found"
    assert float(found.group(1)) == caustics.WATER_MARKER_DISTANCE
    # No other translucent distance the mod sets falls inside the marker's band.
    for other in (0.012, 0.05, 0.35, 1.0):
        assert abs(other - caustics.WATER_MARKER_DISTANCE) > caustics.WATER_MARKER_TOLERANCE


def _module_at(offset: int) -> bytes:
    if not RUNTIME.is_file():
        pytest.skip("no runtime staged")
    with RUNTIME.open("rb") as f:
        f.seek(offset)
        blob = f.read(4 * 1024 * 1024)
    size = module_size(blob)
    if not blob.startswith(struct.pack("<I", 0x07230203)) or size == 0:
        pytest.skip("not the a00da12a runtime")
    return blob[:size]


def test_shadow_absorption_keeps_every_original_id_and_stops_the_per_hit_dimming():
    original = Module.from_bytes(_module_at(SHADOW_OFFSET))
    sites = shadow.find_sites(original)
    assert len(sites) == 1
    per_hit = original.instructions[sites[0].store_at].words[1]
    module = Module.from_bytes(original.to_bytes())
    assert ShadowAbsorption().apply(module) == 1

    def results(m):
        return {i.words[2] for i in m.instructions if i.op in (61, 12, 80, 127, 136)}

    assert results(original) <= results(module)
    assert module.words[3] > original.words[3]
    # The site's per-hit store now stores the pass's own value (the caustic factor), an id the original lacked.
    stores = [i for i in module.instructions if i.op == 62 and i.words[1] == per_hit]
    assert sum(i.words[2] >= original.words[3] for i in stores) == 1
