# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0
"""The light through glass in the runtime's own shaders, after PurrsianMilkman's shadow_ray_patch.py brightness
lift, bounce_tint_patch.py and gbuf_patch.py (Apache-2.0, see packaging/licenses/runtime-patches/), over the
word-level IR. The functions they call are the media library's (runtime-patch/shaders/rrp_media.comp).

glass-light: a stained glass's colour is a hue, not a transmittance, so light through it came out a dark patch.
Where a shadow or bounce ray takes a translucent surface's colour, the colour goes through the library: through
rrp_glassLight, glass only, where it feeds the absorption by distance (the logarithm of a material's transmittance
over its measurement distance; in the shadow loops, shadow-absorption's), and through rrp_glassBounce where the
runtime's approximation for indirect rays multiplies a bounce ray by it once per surface,
clamp(colour * scale + bias, 0, 1) ^ gamma. Only modules an indirect or a shadow ray runs are touched: the
gbuffer's primary rays share the code and keep the look of glass seen directly. The pass runs before
shadow-absorption, which then finds its loops as they were.

pane-depth: the gbuffer shaders absorb a primary ray through a medium by exp(-sigma * travelled); the travelled
distance goes through rrp_paneDepth, so a pane seen directly shows its colour as a thicker glass would.
"""

from __future__ import annotations

from . import grammar, library, merge, shadow
from .spirv import OP_CONSTANT, OP_NAME, OP_STORE, Module, op

_MEMBER_NAME, _EXT_INST, _ACCESS_CHAIN, _TYPE_POINTER, _TYPE_VECTOR, _TYPE_FLOAT = 6, 12, 65, 32, 23, 22
_EXT_INST_IMPORT, _DECORATE, _MEMBER_DECORATE = 11, 71, 72
_COMPOSITE_CONSTRUCT, _COPY_OBJECT, _F_CONVERT = 80, 83, 115
_FNEGATE, _FDIV, _VECTOR_TIMES_SCALAR = 127, 136, 142
_GLSL_POW, _GLSL_EXP, _GLSL_LOG = 26, 27, 28
_STORAGE_UNIFORM = 2
_INDIRECT_FLAG = "enableTransmissionApproximationInIndirectRays"


def _types(module: Module):
    ins = module.instructions
    glsl = next((i.words[1] for i in ins if i.op == _EXT_INST_IMPORT and _text(i.words[2:]) == "GLSL.std.450"), None)
    t_float = next((i.words[1] for i in ins if i.op == _TYPE_FLOAT and i.words[2:] == (32,)), None)
    t_v3 = next((i.words[1] for i in ins if i.op == _TYPE_VECTOR and i.words[2:] == (t_float, 3)), None)
    return glsl, t_float, t_v3


def _text(words) -> str:
    raw = b"".join(w.to_bytes(4, "little") for w in words)
    return raw.split(b"\0", 1)[0].decode("utf-8", "replace")


def _results(module: Module) -> dict[int, int]:
    """Each result id's defining instruction, by index."""
    return {
        i.words[2]: n
        for n, i in enumerate(module.instructions)
        if len(i.words) > 2 and grammar.result_position(i.words) == 2
    }


def _uses(module: Module, ids: set[int]) -> dict[int, list[int]]:
    """Where each of ids is used, by instruction index, names and decorations left out."""
    uses: dict[int, list[int]] = {}
    for n, i in enumerate(module.instructions):
        if i.op in (OP_NAME, _MEMBER_NAME, _DECORATE, _MEMBER_DECORATE) or not ids.intersection(i.words[1:]):
            continue
        r = grammar.result_position(i.words)
        for p in grammar.id_positions(i.words):
            if p != r and i.words[p] in ids:
                uses.setdefault(i.words[p], []).append(n)
    return uses


def _reads_indirect_flag(module: Module) -> bool:
    """Whether the module reads RaytraceArgs.enableTransmissionApproximationInIndirectRays: an indirect ray's."""
    ins = module.instructions
    args = module.named("RaytraceArgs_natural")
    cb = module.named("cb")
    if len(args) != 1 or len(cb) != 1:
        return False
    member = next(
        (
            i.words[2]
            for i in ins
            if i.op == _MEMBER_NAME and i.words[1] == args[0] and _text(i.words[3:]) == _INDIRECT_FLAG
        ),
        None,
    )
    if member is None:
        return False
    constants = {i.words[2] for i in ins if i.op == OP_CONSTANT and len(i.words) == 4 and i.words[3] == member}
    return any(i.op == _ACCESS_CHAIN and i.words[3] == cb[0] and i.words[4] in constants for i in ins)


def light_sites(module: Module) -> list[tuple[int, int | None]]:
    """(index of the OpFConvert that brings a translucent surface's colour to float, the material's measurement
    distance or None) for every colour a shadow or bounce ray takes: a half Pow converted once and used once,
    by a store or a Log after the translucent scale and bias (no distance), or by the Log of an absorption by
    distance whose divisor vec3(d, d, d) follows within six instructions."""
    glsl, t_float, t_v3 = _types(module)
    if None in (glsl, t_float, t_v3):
        return []
    if not _reads_indirect_flag(module) and not shadow.find_sites(module):
        return []
    ins = module.instructions
    results = _results(module)
    structs = set(module.named("TranslucentMaterialArgs_natural"))
    chains = {
        i.words[1] for i in ins if i.op == _TYPE_POINTER and i.words[2] == _STORAGE_UNIFORM and i.words[3] in structs
    }
    candidates = []
    for n, i in enumerate(ins):
        if i.op != _F_CONVERT or i.words[1] != t_v3:
            continue
        p = results.get(i.words[3])
        if p is not None and ins[p].op == _EXT_INST and ins[p].words[3:5] == (glsl, _GLSL_POW):
            candidates.append(n)
    uses = _uses(module, {ins[n].words[2] for n in candidates})
    sites = []
    for n in candidates:
        colour = ins[n].words[2]
        used = uses.get(colour, [])
        if len(used) != 1:
            continue
        use = ins[used[0]]
        stored = use.op == OP_STORE and use.words[2] == colour
        logged = use.op == _EXT_INST and use.words[3:5] == (glsl, _GLSL_LOG) and use.words[5] == colour
        if not (stored or logged):
            continue
        if any(w.op == _ACCESS_CHAIN and w.words[1] in chains for w in ins[max(0, n - 30) : n]):
            sites.append((n, None))
            continue
        if not logged:
            continue
        dist = next(
            (
                w.words[3]
                for w in ins[used[0] + 1 : used[0] + 7]
                if w.op == _COMPOSITE_CONSTRUCT
                and w.words[1] == t_v3
                and len(w.words) == 6
                and len(set(w.words[3:])) == 1
            ),
            None,
        )
        if dist is not None and results.get(dist, len(ins)) < n:
            sites.append((n, dist))
    return sites


def apply_light(module: Module) -> int:
    """glass-light on one module; the number of colours it covered."""
    if not light_sites(module):
        return 0
    lib = library.media_library()
    bounce, light = merge.merge(module, lib, "rrp_glassBounce"), merge.merge(module, lib, "rrp_glassLight")
    _, _, t_v3 = _types(module)
    sites = light_sites(module)
    for n, dist in sites:
        words = list(module.instructions[n].words)
        colour, raw = words[2], module.new_id()
        words[2] = raw
        fn, args = (bounce, [raw]) if dist is None else (light, [raw, dist])
        lifted, code = merge.call(module, n, fn, args, t_v3)
        module.replace(n, words, *code, op(_COPY_OBJECT, t_v3, colour, lifted))
    module.commit()
    return len(sites)


def pane_sites(module: Module) -> list[tuple[int, int, int]]:
    """(index of the VectorTimesScalar, the travelled distance, the measurement distance) for every primary
    ray's absorption, exp(-(-log(T) / vec3(d)) * travelled), in a gbuffer module (one that names
    PrimaryAttenuation)."""
    if not module.named("PrimaryAttenuation"):
        return []
    glsl, t_float, t_v3 = _types(module)
    if None in (glsl, t_float, t_v3):
        return []
    ins = module.instructions
    results = _results(module)

    def defined(result, opcode):
        k = results.get(result)
        return ins[k] if k is not None and ins[k].op == opcode else None

    sites = []
    for n, i in enumerate(ins[:-1]):
        if i.op != _VECTOR_TIMES_SCALAR or i.words[1] != t_v3:
            continue
        after = ins[n + 1]
        if after.op != _EXT_INST or after.words[3:5] != (glsl, _GLSL_EXP) or after.words[5] != i.words[2]:
            continue
        negated = defined(i.words[3], _FNEGATE)
        divided = defined(negated.words[3], _FDIV) if negated else None
        if divided is None:
            continue
        logged = defined(divided.words[3], _FNEGATE)
        construct = defined(divided.words[4], _COMPOSITE_CONSTRUCT)
        log = defined(logged.words[3], _EXT_INST) if logged else None
        if log is None or log.words[3:5] != (glsl, _GLSL_LOG) or construct is None:
            continue
        if len(construct.words) != 6 or len(set(construct.words[3:])) != 1:
            continue
        sites.append((n, i.words[4], construct.words[3]))
    return sites


def apply_pane_depth(module: Module) -> int:
    """pane-depth on one module; the number of absorptions it covered."""
    if not pane_sites(module):
        return 0
    fn = merge.merge(module, library.media_library(), "rrp_paneDepth")
    _, t_float, _ = _types(module)
    sites = pane_sites(module)
    for n, travelled, dist in sites:
        words = list(module.instructions[n].words)
        deeper, code = merge.call(module, n, fn, [travelled, dist], t_float)
        words[4] = deeper
        module.replace(n, *code, words)
    module.commit()
    return len(sites)
