# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0
"""Builds the runtime patch set the layer loads: patches/manifest.tsv, one delta (.rrpd) per replaced module
and combination of the groups that edit it, and patches.lock.

The set is built from the very d3d9.dll that ships, so the offsets and keys in the manifest are right by
construction. Passes of one group run alone on a module, and every combination of the groups that edit a
module runs too, so the layer can take the variant for whichever groups applied; a pass must find the same
sites in every combination. Every replaced module passes spirv-val when SPIRV-Tools is available; its sha256
then goes into patches.lock with validated set. Without SPIRV-Tools the build checks each output against the
lock instead, so changing a pass needs the Vulkan SDK and building the mod does not. Every delta is applied
through the layer's own rrp_apply and must give back the module the passes made.
"""

from __future__ import annotations

import ctypes
import hashlib
import itertools
import json
import os
import shutil
import subprocess
import tempfile
from dataclasses import dataclass, field
from pathlib import Path

from .passes import PASSES, Pass
from .spirv import Keyer, Module, apply_delta, embedded_modules

_BINDING_DECORATIONS = (33, 34)


@dataclass
class PassOutcome:
    id: str
    modules: int = 0
    sites: list[int] = field(default_factory=list)
    ok: bool = True
    reason: str = ""


@dataclass
class Variant:
    key: str
    offset: int
    size: int
    groups: tuple[str, ...]
    module: Module
    original: Module

    @property
    def name(self) -> str:
        return f"{self.key}:{'+'.join(self.groups)}"

    @property
    def file(self) -> str:
        return f"{self.key}.{'+'.join(self.groups)}.rrpd"


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


def _with_bumped_bindings(words: list[int], below: int, by: int) -> list[int]:
    """words with every Binding/DescriptorSet literal of an id below `below` raised by `by`, as dxvk
    rewrites them."""
    out = list(words)
    for ins in Module(words).instructions:
        if ins.op == 71 and len(ins.words) >= 4 and ins.words[2] in _BINDING_DECORATIONS and ins.words[1] < below:
            out[ins.at + 3] += by
    return out


def _layer_apply(layer: ctypes.CDLL, incoming: list[int], delta: list[int]) -> list[int] | None:
    capacity = delta[3]
    src = (ctypes.c_uint32 * len(incoming))(*incoming)
    d = (ctypes.c_uint32 * len(delta))(*delta)
    out = (ctypes.c_uint32 * capacity)()
    n = layer.rrp_apply(src, len(incoming), d, len(delta), out, capacity)
    return list(out[:n]) if n >= 0 else None


def _check_delta(layer: ctypes.CDLL, v: Variant, delta: list[int]) -> str | None:
    """The delta through the layer's applier gives back the module, and keeps the runtime's own binding
    literals wherever they arrive rewritten."""
    original = v.original.words
    if _layer_apply(layer, original, delta) != v.module.words:
        return "rrp_apply does not reproduce the module"
    if apply_delta(original, delta) != v.module.words:
        return "the Python applier disagrees with rrp_apply"
    bound = v.original.words[3]
    bumped = _layer_apply(layer, _with_bumped_bindings(original, bound, 1000), delta)
    if bumped != _with_bumped_bindings(v.module.words, bound, 1000):
        return "a rewritten binding literal does not survive the delta"
    return None


def build(
    dll: Path, layer_dll: Path, out: Path, lock: Path, strict: bool = False, passes: list[Pass] | None = None
) -> list[PassOutcome]:
    """Writes the patch set for dll into out (its patches/ folder). Returns one outcome per pass; a group with
    a pass outside its expected match counts, or with a module that fails validation, is left out."""
    passes = PASSES if passes is None else passes
    data = dll.read_bytes()
    keyer = Keyer(layer_dll)
    layer = ctypes.CDLL(str(layer_dll))
    layer.rrp_apply.argtypes = [ctypes.c_void_p, ctypes.c_size_t, ctypes.c_void_p, ctypes.c_size_t,
                                ctypes.c_void_p, ctypes.c_size_t]
    layer.rrp_apply.restype = ctypes.c_int64
    validator = _spirv_val()
    known = json.loads(lock.read_text(encoding="utf-8")) if lock.is_file() else {}

    # One entry per unique module (the runtime embeds some modules more than once).
    unique: dict[str, tuple[int, bytes]] = {}
    for embedded in embedded_modules(data):
        unique.setdefault(keyer.key(embedded.data), (embedded.offset, embedded.data))

    outcomes = {p.id: PassOutcome(p.id) for p in passes}
    failed_groups: dict[str, str] = {}
    variants: list[Variant] = []
    for key, (offset, original_bytes) in unique.items():
        original = Module.from_bytes(original_bytes)
        alone: dict[str, int] = {}
        for p in passes:
            sites = p.apply(Module.from_bytes(original_bytes))
            if sites:
                alone[p.id] = sites
                outcomes[p.id].modules += 1
                outcomes[p.id].sites.append(sites)
        groups = sorted({p.group for p in passes if p.id in alone})
        for size in range(1, len(groups) + 1):
            for subset in itertools.combinations(groups, size):
                module = Module.from_bytes(original_bytes)
                for p in passes:
                    if p.group not in subset or p.id not in alone:
                        continue
                    sites = p.apply(module)
                    if sites != alone[p.id]:
                        for g in subset:
                            failed_groups.setdefault(g, f"{p.id} finds {sites} sites beside {subset}, {alone[p.id]} alone")
                variants.append(Variant(key, offset, len(original_bytes), subset, module, original))

    for p in passes:
        o = outcomes[p.id]
        if o.modules not in p.expect.modules:
            o.ok, o.reason = False, f"{o.modules} modules, expected {p.expect.modules.start}-{p.expect.modules.stop - 1}"
        elif any(s not in p.expect.sites_per_module for s in o.sites):
            o.ok, o.reason = False, f"sites per module {o.sites} outside the expected range"
        if not o.ok:
            failed_groups.setdefault(p.group, f"{p.id}: {o.reason}")

    lock_out: dict[str, dict] = {}
    deltas: dict[str, list[int]] = {}
    for v in variants:
        if any(g in failed_groups for g in v.groups):
            continue
        new = v.module.to_bytes()
        digest = hashlib.sha256(new).hexdigest()
        if validator is not None:
            error = _validate(validator, new)
        elif known.get(v.name, {}).get("sha256") != digest:
            error = "no SPIRV-Tools and the module is not in the lock"
        else:
            error = None
        if error is None:
            try:
                delta = v.module.delta(v.original)
            except ValueError as e:
                error = str(e)
        if error is None:
            error = _check_delta(layer, v, delta)
        if error is not None:
            for g in v.groups:
                failed_groups.setdefault(g, f"module {v.name[:12]}: {error}")
            continue
        deltas[v.name] = delta
        lock_out[v.name] = {"groups": list(v.groups), "sha256": digest, "validated": True}

    for p in passes:
        o = outcomes[p.id]
        if o.ok and p.group in failed_groups:
            o.ok, o.reason = False, failed_groups[p.group]
    if strict and any(not o.ok for o in outcomes.values()):
        bad = ", ".join(f"{o.id}: {o.reason}" for o in outcomes.values() if not o.ok)
        raise SystemExit(f"runtime patch set incomplete: {bad}")

    patches = out / "patches"
    if patches.exists():
        shutil.rmtree(patches)
    patches.mkdir(parents=True)
    lines = ["#format 2"]
    for v in sorted(variants, key=lambda v: v.name):
        if v.name not in deltas or any(g in failed_groups for g in v.groups):
            continue
        delta = deltas[v.name]
        (patches / v.file).write_bytes(b"".join(w.to_bytes(4, "little") for w in delta))
        lines.append(f"{v.key}\t{v.offset}\t{v.size}\t{','.join(v.groups)}\t{v.file}")
    (patches / "manifest.tsv").write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
    if validator is not None:
        kept = {k: e for k, e in lock_out.items() if not any(g in failed_groups for g in e["groups"])}
        lock.parent.mkdir(parents=True, exist_ok=True)
        lock.write_text(json.dumps(kept, indent=1, sort_keys=True) + "\n", encoding="utf-8", newline="\n")
    return list(outcomes.values())
