# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0
"""Depth-dependent absorption of shadow rays through thick translucent surfaces.

After PurrsianMilkman's shadow_ray_patch.py (Apache-2.0, see packaging/licenses/runtime-patches/), rewritten
over the word-level IR so building the patch set needs no SPIRV-Tools.
"""

from __future__ import annotations

from .caustics import Caustics
from .spirv import (
    OP_BRANCH_CONDITIONAL,
    OP_CONSTANT,
    OP_FUNCTION,
    OP_LABEL,
    OP_LOAD,
    OP_STORE,
    OP_VARIABLE,
    Instruction,
    Module,
    f32,
    op,
)

_EXT_INST_IMPORT, _EXT_INST = 11, 12
_TYPE_BOOL, _TYPE_INT, _TYPE_FLOAT, _TYPE_VECTOR, _TYPE_POINTER = 20, 21, 22, 23, 32
_CONSTANT_COMPOSITE, _CONSTANT_NULL = 44, 46
_COMPOSITE_CONSTRUCT, _FNEGATE, _FADD, _FMUL, _FDIV, _VECTOR_TIMES_SCALAR = 80, 127, 129, 133, 136, 142
_SELECT, _FORD_LESS_THAN, _FORD_GREATER_THAN_EQUAL = 169, 184, 190
_PHI, _LOOP_MERGE, _BRANCH = 245, 246, 249
_RQ_INITIALIZE, _RQ_PROCEED, _RQ_INTERSECTION_TYPE = 4473, 4477, 4479
_RQ_INTERSECTION_T, _RQ_INTERSECTION_FRONT_FACE = 6018, 6025
_GLSL_EXP, _GLSL_LOG, _GLSL_FMIN, _GLSL_FMAX = 27, 28, 37, 40
_STORAGE_FUNCTION = 7
_TYPED_RESULT = {_EXT_INST, OP_LOAD, OP_CONSTANT, _COMPOSITE_CONSTRUCT, _FNEGATE}


def _string(ins: Instruction, first: int) -> str:
    raw = b"".join(w.to_bytes(4, "little") for w in ins.words[first:])
    return raw.split(b"\0", 1)[0].decode("utf-8", "replace")


class Site:
    def __init__(self, sig_at, log_at, store_at, dist, sig, loop, light, t_max):
        self.sig_at, self.log_at, self.store_at = sig_at, log_at, store_at
        self.dist, self.sig, self.loop, self.light, self.t_max = dist, sig, loop, light, t_max


def find_sites(module: Module) -> list[Site]:
    """sigma = -log(T) / vec3(distance) inside a ray-query Proceed loop that processes candidates, with the
    thin-wall test `distance < x` before it, the per-hit transmittance stored right after it, and that store
    multiplied into the ray's light later in the loop."""
    ins = module.instructions
    t = _types(module)
    if t is None:
        return []
    glsl, t_float, t_bool, t_uint, t_v3 = t
    uint0s = _uint0s(module, t_uint)
    defs = {i.words[2]: n for n, i in enumerate(ins) if i.op in _TYPED_RESULT}
    labels = {i.words[1]: n for n, i in enumerate(ins) if i.op == OP_LABEL}

    def defined(result, opcode):
        n = defs.get(result)
        return (n, ins[n]) if n is not None and ins[n].op == opcode else (None, None)

    loops = []  # (header, merge index, ray query)
    for n, i in enumerate(ins):
        if i.op == _LOOP_MERGE:
            rq = next((w.words[3] for w in ins[n + 1 : n + 15] if w.op == _RQ_PROCEED), None)
            loops.append((n, labels.get(i.words[1], -1), rq))

    sites = []
    for n, i in enumerate(ins):
        if i.op != _FDIV or i.words[1] != t_v3:
            continue
        sig, neg, d3 = i.words[2:5]
        _, negate = defined(neg, _FNEGATE)
        _, construct = defined(d3, _COMPOSITE_CONSTRUCT)
        if negate is None or construct is None or len(construct.words) != 6 or len(set(construct.words[3:])) != 1:
            continue
        log_at, log = defined(negate.words[3], _EXT_INST)
        if log is None or log.words[3:5] != (glsl, _GLSL_LOG):
            continue
        dist = construct.words[3]
        if not any(
            w.op == _FORD_LESS_THAN and w.words[1] == t_bool and w.words[3] == dist for w in ins[max(0, n - 400) : n]
        ):
            continue
        loop = max((lp for lp in loops if lp[0] < n < lp[1]), default=None, key=lambda lp: lp[0])
        if loop is None or loop[2] is None:
            continue
        header, merge, rq = loop
        if not any(
            w.op == _RQ_INTERSECTION_TYPE and w.words[3] == rq and w.words[4] in uint0s for w in ins[header:merge]
        ):
            continue
        store = None
        for j in range(n + 1, min(n + 30, len(ins))):
            if ins[j].op in (OP_LABEL, _BRANCH, OP_BRANCH_CONDITIONAL):
                break
            if ins[j].op == OP_STORE:
                store = j
                break
        if store is None:
            continue
        light = _light(ins, store, merge, t_v3, ins[store].words[1])
        if light is None:
            continue
        sites.append(Site(n, log_at, store, dist, sig, loop, light, _t_max(ins, header, rq, t_float, defs)))
    return sites


def apply(module: Module, glass_scale: float, cloud_scale: float, cloud_distance: float, caustics: bool = False) -> int:
    """Each shadow ray-query loop adds up the optical depth between entries and exits, signed by facing so
    the candidates' order does not matter, closes an entry still open at the ray's end, and multiplies the
    ray's light by exp(-depth) once the loop is done. Returns the number of sites patched."""
    sites = find_sites(module)
    if not sites:
        return 0
    ins = module.instructions
    glsl, t_float, t_bool, t_uint, t_v3 = _types(module)
    t_pv3 = next((i.words[1] for i in ins if i.op == _TYPE_POINTER and i.words[2:] == (_STORAGE_FUNCTION, t_v3)), None)
    uint0s = _uint0s(module, t_uint)

    g = []  # declarations before the first function

    def const(value):
        result = module.new_id()
        g.append(op(OP_CONSTANT, t_float, result, f32(value)))
        return result

    if t_pv3 is None:
        t_pv3 = module.new_id()
        g.append(op(_TYPE_POINTER, t_pv3, _STORAGE_FUNCTION, t_v3))
    if uint0s:
        uint0 = min(uint0s)
    else:
        uint0 = module.new_id()
        g.append(op(OP_CONSTANT, t_uint, uint0, 0))
    k_glass, k_glass_in = const(glass_scale), const(-glass_scale)
    k_cloud, k_cloud_in = const(cloud_scale), const(-cloud_scale)
    cloud_at, one, far, eps = const(cloud_distance), const(1.0), const(1024.0), const(1.0 / 1024.0)
    v0, v1, v_eps = module.new_id(), module.new_id(), module.new_id()
    g += [
        op(_CONSTANT_NULL, t_v3, v0),
        op(_CONSTANT_COMPOSITE, t_v3, v1, one, one, one),
        op(_CONSTANT_COMPOSITE, t_v3, v_eps, eps, eps, eps),
    ]
    web = Caustics(module, glsl, t_float, t_bool, t_v3, g, const) if caustics else None
    first_function = next(n for n, i in enumerate(ins) if i.op == OP_FUNCTION)
    module.insert_after(first_function - 1, *g)

    def typed(opcode, result_type, *operands):
        result = module.new_id()
        return result, op(opcode, result_type, result, *operands)

    def ext(result_type, instruction, *operands):
        return typed(_EXT_INST, result_type, glsl, instruction, *operands)

    by_loop: dict[int, list[Site]] = {}
    for site in sites:
        by_loop.setdefault(site.loop[0], []).append(site)
    for header, loop_sites in by_loop.items():
        _, merge, rq = loop_sites[0].loop
        light, t_max = loop_sites[0].light, loop_sites[0].t_max
        depth, open_entries = module.new_id(), module.new_id()
        function = max(n for n in range(header) if ins[n].op == OP_FUNCTION)
        entry = next(n for n in range(function, header) if ins[n].op == OP_LABEL)
        module.insert_after(
            entry,
            op(OP_VARIABLE, t_pv3, depth, _STORAGE_FUNCTION),
            op(OP_VARIABLE, t_pv3, open_entries, _STORAGE_FUNCTION),
        )
        for n in range(max(0, header - 200), header):
            if ins[n].op == _RQ_INITIALIZE and ins[n].words[1] == rq:
                module.insert_after(n, op(OP_STORE, depth, v0), op(OP_STORE, open_entries, v0))

        at = merge
        while at + 1 < len(ins) and ins[at + 1].op == _PHI:
            at += 1
        a, a_load = typed(OP_LOAD, t_v3, depth)
        c, c_load = typed(OP_LOAD, t_v3, open_entries)
        nc, nc_op = typed(_FNEGATE, t_v3, c)
        unclosed, unclosed_op = ext(t_v3, _GLSL_FMAX, nc, v0)
        code = [a_load, c_load, nc_op, unclosed_op]
        if t_max is not None:
            tm, tm_load = typed(OP_LOAD, t_float, t_max)
            end, end_op = ext(t_float, _GLSL_FMIN, tm, far)
            code += [tm_load, end_op]
        else:
            end = far
        closing, closing_op = typed(_VECTOR_TIMES_SCALAR, t_v3, unclosed, end)
        total, total_op = typed(_FADD, t_v3, a, closing)
        negative, negative_op = typed(_FNEGATE, t_v3, total)
        transmitted, exp_op = ext(t_v3, _GLSL_EXP, negative)
        clamped, clamp_op = ext(t_v3, _GLSL_FMIN, transmitted, v1)
        r0, r0_load = typed(OP_LOAD, t_v3, light)
        r1, r1_op = typed(_FMUL, t_v3, r0, clamped)
        code += [closing_op, total_op, negative_op, exp_op, clamp_op, r0_load, r1_op, op(OP_STORE, light, r1)]
        module.insert_after(at, *code)

        for s in loop_sites:
            log = ins[s.log_at].words
            floor, floor_op = ext(t_v3, _GLSL_FMAX, log[5], v_eps)
            module.replace(s.log_at, floor_op, [*log[:5], floor])
            t, t_op = typed(_RQ_INTERSECTION_T, t_float, rq, uint0)
            ff, ff_op = typed(_RQ_INTERSECTION_FRONT_FACE, t_bool, rq, uint0)
            cloud, cloud_op = typed(_FORD_GREATER_THAN_EQUAL, t_bool, s.dist, cloud_at)
            k_out, k_out_op = typed(_SELECT, t_float, cloud, k_cloud, k_glass)
            k_in, k_in_op = typed(_SELECT, t_float, cloud, k_cloud_in, k_glass_in)
            # The mod's meshes run clockwise seen from outside, and the runtime's ray queries report a
            # candidate seen counter-clockwise as front facing: a front-facing hit leaves the volume.
            sign, sign_op = typed(_SELECT, t_float, ff, k_out, k_in)
            st, st_op = typed(_FMUL, t_float, sign, t)
            a0, a0_load = typed(OP_LOAD, t_v3, depth)
            a1, a1_op = typed(_VECTOR_TIMES_SCALAR, t_v3, s.sig, st)
            a2, a2_op = typed(_FADD, t_v3, a0, a1)
            c0, c0_load = typed(OP_LOAD, t_v3, open_entries)
            c1, c1_op = typed(_VECTOR_TIMES_SCALAR, t_v3, s.sig, sign)
            c2, c2_op = typed(_FADD, t_v3, c0, c1)
            per_hit, web_code = v1, []
            if web is not None and web.available:
                per_hit, web_code = web.emit(rq, t, s.dist, cloud_at)
            module.insert_after(
                s.sig_at,
                t_op,
                ff_op,
                cloud_op,
                k_out_op,
                k_in_op,
                sign_op,
                st_op,
                a0_load,
                a1_op,
                a2_op,
                op(OP_STORE, depth, a2),
                c0_load,
                c1_op,
                c2_op,
                op(OP_STORE, open_entries, c2),
                *web_code,
            )
            module.replace(s.store_at, op(OP_STORE, ins[s.store_at].words[1], per_hit))
    module.commit()
    return len(sites)


def _types(module: Module):
    ins = module.instructions
    glsl = next((i.words[1] for i in ins if i.op == _EXT_INST_IMPORT and _string(i, 2) == "GLSL.std.450"), None)
    t_float = next((i.words[1] for i in ins if i.op == _TYPE_FLOAT and i.words[2:] == (32,)), None)
    t_bool = next((i.words[1] for i in ins if i.op == _TYPE_BOOL), None)
    t_uint = next((i.words[1] for i in ins if i.op == _TYPE_INT and i.words[2:] == (32, 0)), None)
    t_v3 = next((i.words[1] for i in ins if i.op == _TYPE_VECTOR and i.words[2:] == (t_float, 3)), None)
    found = (glsl, t_float, t_bool, t_uint, t_v3)
    return None if None in found else found


def _uint0s(module: Module, t_uint: int) -> set[int]:
    return {
        i.words[2] for i in module.instructions if i.op == OP_CONSTANT and i.words[1] == t_uint and i.words[3:] == (0,)
    }


def _light(ins, store, merge, t_v3, per_hit):
    """The variable the per-hit transmittance is multiplied into: load P, load R, R * P, store R."""
    for j in range(store, merge):
        if not (ins[j].op == OP_LOAD and ins[j].words[1] == t_v3 and ins[j].words[3] == per_hit):
            continue
        for k in range(j + 1, min(j + 4, len(ins))):
            if ins[k].op == OP_LOAD and ins[k].words[1] == t_v3:
                light = ins[k].words[3]
                mul, put = ins[k + 1], ins[k + 2]
                if (
                    mul.op == _FMUL
                    and mul.words[1] == t_v3
                    and put.op == OP_STORE
                    and put.words[1:3] == (light, mul.words[2])
                ):
                    return light
                break
    return None


def _t_max(ins, header, rq, t_float, defs):
    """The variable the ray query's tMax was loaded from, so the end of the ray can close an entry."""
    for j in range(header, -1, -1):
        if ins[j].op == _RQ_INITIALIZE and ins[j].words[1] == rq:
            n = defs.get(ins[j].words[-1])
            if n is not None and ins[n].op == OP_LOAD and ins[n].words[1] == t_float:
                return ins[n].words[3]
            return None
        if j < header - 200:
            return None
    return None
