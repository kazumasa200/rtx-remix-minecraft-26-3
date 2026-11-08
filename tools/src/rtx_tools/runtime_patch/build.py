# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0
"""Builds the runtime patch set the layer loads: patches/manifest.tsv, one .spv per replaced module, and
patches.lock.

The set is built from the very d3d9.dll that ships, so the offsets and keys in the manifest are right by
construction. Every replaced module passes spirv-val when SPIRV-Tools is available; its sha256 then goes
into patches.lock with validated set. Without SPIRV-Tools the build checks each output against the lock
instead, so changing a pass needs the Vulkan SDK and building the mod does not.
"""

from __future__ import annotations

import hashlib
import json
import os
import shutil
import subprocess
import tempfile
from dataclasses import dataclass, field
from pathlib import Path

from .passes import PASSES, Pass
from .spirv import Keyer, Module, embedded_modules


@dataclass
class PassOutcome:
    id: str
    modules: int = 0
    sites: list[int] = field(default_factory=list)
    ok: bool = True
    reason: str = ""


def _spirv_val() -> Path | None:
    for root in (os.environ.get("VULKAN_SDK"), r"C:\VulkanSDK\1.4.341.1"):
        if root and (Path(root) / "Bin" / "spirv-val.exe").is_file():
            return Path(root) / "Bin" / "spirv-val.exe"
    found = shutil.which("spirv-val")
    return Path(found) if found else None


def _validate(validator: Path, data: bytes) -> str | None:
    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp) / "m.spv"
        path.write_bytes(data)
        run = subprocess.run(
            [str(validator), "--target-env", "vulkan1.2", "--scalar-block-layout", str(path)],
            capture_output=True,
            text=True,
        )
        return None if run.returncode == 0 else (run.stderr or run.stdout).strip()[-400:]


def build(
    dll: Path, layer_dll: Path, out: Path, lock: Path, strict: bool = False, passes: list[Pass] | None = None
) -> list[PassOutcome]:
    """Writes the patch set for dll into out (its patches/ folder). Returns one outcome per pass; a pass
    outside its expected match counts, or with a module that fails validation, is left out."""
    passes = PASSES if passes is None else passes
    data = dll.read_bytes()
    keyer = Keyer(layer_dll)
    validator = _spirv_val()
    known = json.loads(lock.read_text(encoding="utf-8")) if lock.is_file() else {}

    # One entry per unique module (the runtime embeds some modules more than once).
    unique: dict[str, tuple[int, bytes]] = {}
    for embedded in embedded_modules(data):
        unique.setdefault(keyer.key(embedded.data), (embedded.offset, embedded.data))

    outcomes = {p.id: PassOutcome(p.id) for p in passes}
    edited: dict[str, tuple[int, int, bytes, str]] = {}  # key -> offset, size, new bytes, pass id
    for key, (offset, original) in unique.items():
        for p in passes:
            module = Module.from_bytes(original)
            sites = p.apply(module)
            if sites == 0:
                continue
            outcome = outcomes[p.id]
            outcome.modules += 1
            outcome.sites.append(sites)
            if key in edited:
                outcome.ok, outcome.reason = False, "a module two passes edit is not supported yet"
                continue
            edited[key] = (offset, len(original), module.to_bytes(), p.id)

    for p in passes:
        o = outcomes[p.id]
        if o.ok and o.modules not in p.expect.modules:
            o.ok, o.reason = (
                False,
                f"{o.modules} modules, expected {p.expect.modules.start}-{p.expect.modules.stop - 1}",
            )
        if o.ok and any(s not in p.expect.sites_per_module for s in o.sites):
            o.ok, o.reason = False, f"sites per module {o.sites} outside the expected range"

    lock_out: dict[str, dict] = {}
    for key, (_offset, _size, new, pass_id) in sorted(edited.items()):
        o = outcomes[pass_id]
        if not o.ok:
            continue
        digest = hashlib.sha256(new).hexdigest()
        if validator is not None:
            error = _validate(validator, new)
            if error is not None:
                o.ok, o.reason = False, f"spirv-val rejected module {key[:12]}: {error}"
                continue
        elif known.get(key, {}).get("sha256") != digest:
            o.ok, o.reason = False, f"no SPIRV-Tools and module {key[:12]} is not in the lock"
            continue
        lock_out[key] = {"pass": pass_id, "sha256": digest, "validated": True}

    if strict and any(not o.ok for o in outcomes.values()):
        bad = ", ".join(f"{o.id}: {o.reason}" for o in outcomes.values() if not o.ok)
        raise SystemExit(f"runtime patch set incomplete: {bad}")

    patches = out / "patches"
    if patches.exists():
        shutil.rmtree(patches)
    patches.mkdir(parents=True)
    lines = ["#format 1"]
    for key, (offset, size, new, pass_id) in sorted(edited.items()):
        if not outcomes[pass_id].ok or key not in lock_out:
            continue
        (patches / f"{key}.spv").write_bytes(new)
        lines.append(f"{key}\t{offset}\t{size}\t{pass_id}\t{key}.spv")
    (patches / "manifest.tsv").write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
    if validator is not None:
        lock.parent.mkdir(parents=True, exist_ok=True)
        lock.write_text(json.dumps(lock_out, indent=1, sort_keys=True) + "\n", encoding="utf-8", newline="\n")
    return list(outcomes.values())
