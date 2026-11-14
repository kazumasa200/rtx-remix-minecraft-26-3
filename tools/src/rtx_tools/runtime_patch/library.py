# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0
"""The libraries the passes merge into the runtime's modules, compiled by glslang: the clouds,
runtime-patch/shaders/rrp_clouds.comp, which includes the mod's own cloud_field.glsl, and glass and water as the
shadow-absorption group sees them, rrp_media.comp.

The compiled modules are kept under runtime-patch/shaders/compiled/ with one digest of all their sources, so
building the mod needs no Vulkan SDK; with the SDK the build recompiles and rewrites the files there when they
differ, as it rewrites patches.lock. frame_layout.txt lists the frame block's members and offsets as the compiled
clouds library lays them out, which RuntimeFrame writes and RuntimeFrameTest checks.
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
SOURCES = (
    SHADERS / "rrp_clouds.comp",
    SHADERS / "rrp_cloud_pass.comp",
    SHADERS / "rrp_media.comp",
    SHADERS / "rrp_hdr.comp",
    SHADERS / "rrp_frame.glsl",
    FIELD / "cloud_field.glsl",
)
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
    names = {
        i.words[2]: _text(i.words[3:]) for i in module.instructions if i.op == _MEMBER_NAME and i.words[1] == block
    }
    offsets = {
        i.words[2]: i.words[4]
        for i in module.instructions
        if i.op == _MEMBER_DECORATE and i.words[1] == block and i.words[3] == _OFFSET
    }
    return [(names[m], offsets[m]) for m in sorted(names)]


def _text(words) -> str:
    raw = b"".join(w.to_bytes(4, "little") for w in words)
    return raw.split(b"\0", 1)[0].decode("utf-8")


def _compiled(name: str, digest: str) -> bytes:
    """compiled/<name>.spv from runtime-patch/shaders/<name>.comp, recompiled when glslang is at hand."""
    target = SHADERS / "compiled" / f"{name}.spv"
    tool = _glslang()
    if tool is None:
        if not target.is_file() or DIGEST.read_text(encoding="utf-8").strip() != digest:
            raise ValueError("the runtime patch shaders' sources changed; rebuild them with the Vulkan SDK's glslang")
        return target.read_bytes()
    with tempfile.TemporaryDirectory() as tmp:
        out = Path(tmp) / f"{name}.spv"
        run = subprocess.run(
            [
                str(tool),
                "-V",
                "--target-env",
                "spirv1.5",
                f"-I{SHADERS}",
                f"-I{FIELD}",
                str(SHADERS / f"{name}.comp"),
                "-o",
                str(out),
            ],
            capture_output=True,
            text=True,
        )
        if run.returncode != 0:
            raise ValueError(f"glslang failed on {name}.comp: {(run.stdout + run.stderr).strip()[-600:]}")
        data = out.read_bytes()
    target.parent.mkdir(parents=True, exist_ok=True)
    if not target.is_file() or target.read_bytes() != data:
        target.write_bytes(data)
    return data


def _write_if_changed(path: Path, text: str) -> None:
    if not path.is_file() or path.read_text(encoding="utf-8") != text:
        path.write_text(text, encoding="utf-8", newline="\n")


@cache
def clouds_library() -> Module:
    """The library the passes merge: rrp_clouds.comp."""
    digest = sources_digest()
    module = Module.from_bytes(_compiled("rrp_clouds", digest))
    if _glslang() is not None:
        _write_if_changed(LAYOUT, "".join(f"{name} {offset}\n" for name, offset in frame_layout(module)))
        _write_if_changed(DIGEST, digest + "\n")
    return module


@cache
def media_library() -> Module:
    """The library the shadow-absorption group merges: rrp_media.comp."""
    return Module.from_bytes(_compiled("rrp_media", sources_digest()))


@cache
def hdr_library() -> Module:
    """The library the hdr-output group merges: rrp_hdr.comp."""
    return Module.from_bytes(_compiled("rrp_hdr", sources_digest()))


@cache
def cloud_pass() -> bytes:
    """The layer's own pass: rrp_cloud_pass.comp, shipped beside the deltas."""
    return _compiled("rrp_cloud_pass", sources_digest())
