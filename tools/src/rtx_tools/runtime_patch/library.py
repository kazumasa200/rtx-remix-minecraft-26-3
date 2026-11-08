# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0
"""The clouds library the passes merge into the runtime's modules: runtime-patch/shaders/rrp_clouds.comp, which
includes the mod's own cloud_field.glsl, compiled by glslang.

The compiled module is kept under runtime-patch/shaders/compiled/ with a digest of its sources, so building the
mod needs no Vulkan SDK; with the SDK the build recompiles and rewrites the three files there when they differ,
as it rewrites patches.lock. frame_layout.txt lists the frame block's members and offsets as the compiled
module lays them out, which RuntimeFrame writes and RuntimeFrameTest checks.
"""

from __future__ import annotations

import hashlib
import os
import shutil
import subprocess
import tempfile
from functools import cache
from pathlib import Path

from .spirv import Module

ROOT = Path(__file__).resolve().parents[4]
SHADERS = ROOT / "runtime-patch" / "shaders"
FIELD = ROOT / "src" / "main" / "resources" / "assets" / "rtx-remix-renderer" / "shaders"
SOURCES = (SHADERS / "rrp_clouds.comp", SHADERS / "rrp_frame.glsl", FIELD / "cloud_field.glsl")
COMPILED = SHADERS / "compiled" / "rrp_clouds.spv"
DIGEST = SHADERS / "compiled" / "rrp_clouds.sources.sha256"
LAYOUT = SHADERS / "compiled" / "frame_layout.txt"

_MEMBER_NAME, _NAME, _MEMBER_DECORATE, _DECORATE = 6, 5, 72, 71
_OFFSET, _ARRAY_STRIDE = 35, 6


def _glslang() -> Path | None:
    for root in (os.environ.get("VULKAN_SDK"), r"C:\VulkanSDK\1.4.341.1"):
        if root and (Path(root) / "Bin" / "glslangValidator.exe").is_file():
            return Path(root) / "Bin" / "glslangValidator.exe"
    found = shutil.which("glslangValidator")
    return Path(found) if found else None


def sources_digest() -> str:
    """The sources' digest, line endings normalised so every checkout agrees."""
    h = hashlib.sha256()
    for path in SOURCES:
        h.update(path.name.encode())
        h.update(path.read_bytes().replace(b"\r\n", b"\n"))
    return h.hexdigest()


def frame_layout(module: Module) -> list[tuple[str, int]]:
    """The frame block's members and their byte offsets, in order."""
    words = {i.words[1]: i for i in module.instructions if i.op == _NAME}
    block = next(k for k, i in words.items() if _text(i.words[2:]) == "RrpFrame")
    names = {i.words[2]: _text(i.words[3:]) for i in module.instructions if i.op == _MEMBER_NAME and i.words[1] == block}
    offsets = {i.words[2]: i.words[4] for i in module.instructions
               if i.op == _MEMBER_DECORATE and i.words[1] == block and i.words[3] == _OFFSET}
    return [(names[m], offsets[m]) for m in sorted(names)]


def _text(words) -> str:
    raw = b"".join(w.to_bytes(4, "little") for w in words)
    return raw.split(b"\0", 1)[0].decode("utf-8")


@cache
def clouds_library() -> Module:
    digest = sources_digest()
    tool = _glslang()
    if tool is not None:
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "rrp_clouds.spv"
            run = subprocess.run(
                [str(tool), "-V", "--target-env", "spirv1.5", f"-I{SHADERS}", f"-I{FIELD}",
                 str(SHADERS / "rrp_clouds.comp"), "-o", str(out)],
                capture_output=True, text=True,
            )
            if run.returncode != 0:
                raise ValueError(f"glslang failed on rrp_clouds.comp: {(run.stdout + run.stderr).strip()[-600:]}")
            data = out.read_bytes()
        module = Module.from_bytes(data)
        layout = "".join(f"{name} {offset}\n" for name, offset in frame_layout(module))
        COMPILED.parent.mkdir(parents=True, exist_ok=True)
        if not COMPILED.is_file() or COMPILED.read_bytes() != data:
            COMPILED.write_bytes(data)
        for path, text in ((DIGEST, digest + "\n"), (LAYOUT, layout)):
            if not path.is_file() or path.read_text(encoding="utf-8") != text:
                path.write_text(text, encoding="utf-8", newline="\n")
        return module
    if not COMPILED.is_file() or DIGEST.read_text(encoding="utf-8").strip() != digest:
        raise ValueError("the clouds library's sources changed; rebuild it with the Vulkan SDK's glslang")
    return Module.from_bytes(COMPILED.read_bytes())
