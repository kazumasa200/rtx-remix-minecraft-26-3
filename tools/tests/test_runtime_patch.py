# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0
import struct
from pathlib import Path

import pytest

from rtx_tools.runtime_patch.passes import SkyFade
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
    changed = [i for i, (a, b) in enumerate(zip(before, module.words)) if a != b]
    assert changed == [GUARD_OPERAND_WORD]
    assert (before[GUARD_OPERAND_WORD], module.words[GUARD_OPERAND_WORD]) == (1039, 848)


def test_sky_fade_leaves_a_module_without_its_pattern_alone():
    # A header and one OpCapability Shader: no primaryMiss, nothing to do.
    words = [0x07230203, 0x00010500, 0, 10, 0, (2 << 16) | 17, 1]
    module = Module(words)
    assert SkyFade().apply(module) == 0
    assert module.words == words

