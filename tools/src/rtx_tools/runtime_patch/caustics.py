# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0
"""Caustics in the sun's direct light: a shadow ray that leaves water through its surface carries the light the
waves there gather onto the point it started from.

The waves are a fixed sum of sines, each rounded to whole waves over PERIOD blocks, a period that divides the
scene origin's 4096-block grid so the web does not jump when the origin moves. At the point where the shadow ray
crosses the surface, sunlight refracted at a surface point x lands t c grad h(x) aside of it, t the distance
the ray travelled in the water and c = 1 - 1 / ior. A few damped fixed-point steps find the x whose light lands
where the ray started, and the light landing there against flat water is 1 / |det J(x)| with J = I - t A,
A being c times the surface's Hessian with its sign turned (a crest is a converging lens). Taken where the light
came from instead, every bright patch drew as wide as the surface that gathered it, not as the thin line it
lands in. The factor is clamped, since a fold of light would be infinite, divided by its mean at that depth so
the waves move light about and add none, and greyed out deeper down, where the steps no longer settle and the
folds of many waves cross.
"""

from __future__ import annotations

import math
import random

from .spirv import OP_CONSTANT, Module, op

PERIOD = 64.0
WAVES = 32
SHORTEST_WAVE, LONGEST_WAVE = 0.6, 3.0
WAVE_SLOPE, WAVE_SPEED, GRAVITY = 0.4, 0.7, 9.81
WATER_IOR = 1.33
BRIGHTEST = 8.0
# Depths at which the mean light is tabulated, and the depths over which the web greys out into flat light,
# as the folds of many waves cross.
TABLE_DEPTHS = 10
GREY_FROM, GREY_TO = 5.0, 9.0
STEPS, STEP = 4, 0.6
# shortcut: a diagnostic, light through water turns black; set RTXREMIX_CAUSTIC_TELLTALE=1 to build it, never ship it.
TELLTALE = __import__("os").environ.get("RTXREMIX_CAUSTIC_TELLTALE", "")

_EXT_INST, _TYPE_INT, _TYPE_VECTOR, _TYPE_POINTER, _TYPE_STRUCT = 12, 21, 23, 32, 30
_MEMBER_NAME, _CONSTANT_COMPOSITE, _ACCESS_CHAIN, _VARIABLE, _LOAD = 6, 44, 65, 59, 61
_VECTOR_SHUFFLE, _COMPOSITE_EXTRACT, _COMPOSITE_CONSTRUCT = 79, 81, 80
_FNEGATE, _FADD, _FSUB, _FMUL, _FDIV, _VECTOR_TIMES_SCALAR, _DOT = 127, 129, 131, 133, 136, 142, 148
_SELECT, _LOGICAL_AND, _FORD_LESS_THAN, _FORD_GREATER_THAN_EQUAL = 169, 167, 184, 190
_RQ_WORLD_DIRECTION, _RQ_WORLD_ORIGIN = 6029, 6030
_GLSL_COS = 14
_GLSL_FABS, _GLSL_SIN, _GLSL_FMIN, _GLSL_FMAX, _GLSL_FMIX, _GLSL_SMOOTHSTEP = 4, 13, 37, 40, 46, 49
_STORAGE_UNIFORM = 2


def waves() -> list[tuple[float, float, float, float, float]]:
    """(kx, kz, height, angular frequency, phase) per wave, lengths spread evenly in scale, every heading alike."""
    rng = random.Random(0x57415645)
    out = []
    for i in range(WAVES):
        length = SHORTEST_WAVE * (LONGEST_WAVE / SHORTEST_WAVE) ** ((i + rng.random()) / WAVES)
        heading = rng.random() * 2.0 * math.pi
        across = PERIOD / length
        kx = 2.0 * math.pi * round(across * math.cos(heading)) / PERIOD
        kz = 2.0 * math.pi * round(across * math.sin(heading)) / PERIOD
        k = math.hypot(kx, kz)
        height = WAVE_SLOPE * (0.6 + 0.8 * rng.random()) / math.sqrt(WAVES) / k
        out.append((kx, kz, height, math.sqrt(GRAVITY * k) * WAVE_SPEED, rng.random() * 2.0 * math.pi))
    return out


def gathered(t: float, x, z):
    """The shader's clamped 1 / |det J| at floor points (x, z) for a path of t blocks in water, at time 0."""
    import numpy as np

    c = 1.0 - 1.0 / WATER_IOR
    table = waves()
    sx, sz = x.copy(), z.copy()
    for _ in range(STEPS):
        gx, gz = np.zeros_like(x), np.zeros_like(x)
        for kx, kz, height, _, phase in table:
            co = np.cos(kx * sx + kz * sz + phase)
            gx += height * kx * co
            gz += height * kz * co
        sx += STEP * (x - t * c * gx - sx)
        sz += STEP * (z - t * c * gz - sz)
    a = np.zeros((3,) + x.shape)
    for kx, kz, height, _, phase in table:
        a += c * height * np.array([kx * kx, kx * kz, kz * kz])[:, None, None] * np.sin(kx * sx + kz * sz + phase)
    det = (1 - t * a[0]) * (1 - t * a[2]) - (t * a[1]) ** 2
    return 1.0 / np.maximum(np.abs(det), 1.0 / BRIGHTEST)


def mean_light() -> list[float]:
    """Per whole block of path in water, the mean of gathered over a sixteenth of the period's area (the waves are
    alike everywhere): the factor is divided by it, so the waves move light about and add none."""
    import numpy as np

    xs = np.linspace(0.0, PERIOD / 4.0, 256, endpoint=False)
    x, z = np.meshgrid(xs, xs)
    return [float(gathered(float(t), x, z).mean()) for t in range(TABLE_DEPTHS)]


def _string(words) -> str:
    raw = b"".join(w.to_bytes(4, "little") for w in words)
    return raw.split(b"\0", 1)[0].decode("utf-8", "replace")


class Caustics:
    """Declares what the caustic code needs once per module, then emits it per site."""

    def __init__(self, module: Module, glsl: int, t_float: int, t_bool: int, t_v3: int, g: list, const):
        ins = module.instructions
        self.module, self.glsl, self.t_float, self.t_bool, self.t_v3 = module, glsl, t_float, t_bool, t_v3
        self.t_v2 = next((i.words[1] for i in ins if i.op == _TYPE_VECTOR and i.words[2:] == (t_float, 2)), None)
        if self.t_v2 is None:
            self.t_v2 = module.new_id()
            g.append(op(_TYPE_VECTOR, self.t_v2, t_float, 2))
        # The runtime's clock: RaytraceArgs.timeSinceStartSeconds of the constant buffer named cb.
        member = next(
            (
                (i.words[1], i.words[2])
                for i in ins
                if i.op == _MEMBER_NAME and _string(i.words[3:]) == "timeSinceStartSeconds"
            ),
            None,
        )
        cb = module.named("cb")
        pointers = {i.words[1]: i.words[3] for i in ins if i.op == _TYPE_POINTER and i.words[2] == _STORAGE_UNIFORM}
        variables = {i.words[2]: i.words[1] for i in ins if i.op == _VARIABLE}
        self.cb = next((v for v in cb if v in variables and pointers.get(variables[v]) == (member or (None,))[0]), None)
        self.available = member is not None and self.cb is not None
        if not self.available:
            return
        self.member = member[1]
        self.t_pf = next((p for p, pointee in pointers.items() if pointee == t_float), None)
        if self.t_pf is None:
            self.t_pf = module.new_id()
            g.append(op(_TYPE_POINTER, self.t_pf, _STORAGE_UNIFORM, t_float))
        t_int = next((i.words[1] for i in ins if i.op == _TYPE_INT and i.words[2:] == (32, 1)), None)
        if t_int is None:
            t_int = module.new_id()
            g.append(op(_TYPE_INT, t_int, 32, 1))
        self.index = module.new_id()
        g.append(op(OP_CONSTANT, t_int, self.index, self.member))
        self.waves = []
        c = 1.0 - 1.0 / WATER_IOR
        for kx, kz, height, frequency, phase in waves():
            k = module.new_id()
            g.append(op(_CONSTANT_COMPOSITE, self.t_v2, k, const(kx), const(kz)))
            curvature = module.new_id()
            # c * height * (kx kx, kx kz, kz kz): A's entries per unit sine, with the refraction folded in.
            g.append(
                op(
                    _CONSTANT_COMPOSITE,
                    t_v3,
                    curvature,
                    const(c * height * kx * kx),
                    const(c * height * kx * kz),
                    const(c * height * kz * kz),
                )
            )
            lean = module.new_id()  # height * (kx, kz): the slope per unit cosine
            g.append(op(_CONSTANT_COMPOSITE, self.t_v2, lean, const(height * kx), const(height * kz)))
            self.waves.append((k, const(frequency), const(phase), curvature, lean))
        self.zero_v3, self.zero_v2 = module.new_id(), module.new_id()
        g.append(op(46, t_v3, self.zero_v3))  # OpConstantNull
        g.append(op(46, self.t_v2, self.zero_v2))
        self.bend, self.step = const(c), const(STEP)
        self.one, self.floor, self.brightest = const(1.0), const(1.0 / BRIGHTEST), const(BRIGHTEST)
        self.water_from = const(1.5)
        self.zero, self.last_depth = const(0.0), const(TABLE_DEPTHS - 1.0)
        self.table = [(const(float(j)), const(1.0 / m)) for j, m in enumerate(mean_light())]
        self.grey_from, self.grey_to = const(GREY_FROM), const(GREY_TO)

    def emit(self, rq: int, t: int, dist: int, cloud_distance: int) -> tuple[int, list[list[int]]]:
        """The caustic factor for a crossing at t of ray query rq (1 for anything but water), and its code."""
        module, code = self.module, []

        def typed(opcode, result_type, *operands):
            result = module.new_id()
            code.append(op(opcode, result_type, result, *operands))
            return result

        def ext(result_type, instruction, *operands):
            return typed(_EXT_INST, result_type, self.glsl, instruction, *operands)

        f, v2, v3, b = self.t_float, self.t_v2, self.t_v3, self.t_bool
        origin = typed(_RQ_WORLD_ORIGIN, v3, rq)
        direction = typed(_RQ_WORLD_DIRECTION, v3, rq)
        along = typed(_VECTOR_TIMES_SCALAR, v3, direction, t)
        point = typed(_FADD, v3, origin, along)
        xz = typed(_VECTOR_SHUFFLE, v2, point, point, 0, 2)
        time = typed(_LOAD, f, typed(_ACCESS_CHAIN, self.t_pf, self.cb, self.index))
        starts = [typed(_FSUB, f, phase, typed(_FMUL, f, frequency, time)) for _, frequency, phase, _, _ in self.waves]
        # The surface point whose refracted light lands at xz: x <- x + STEP (xz - t c grad h(x) - x).
        reach = typed(_FMUL, f, t, self.bend)
        source = xz
        for _ in range(STEPS):
            slope = self.zero_v2
            for (k, _, _, _, lean), start in zip(self.waves, starts, strict=True):
                cosine = ext(f, _GLSL_COS, typed(_FADD, f, typed(_DOT, f, k, source), start))
                slope = typed(_FADD, v2, slope, typed(_VECTOR_TIMES_SCALAR, v2, lean, cosine))
            aim = typed(_FSUB, v2, xz, typed(_VECTOR_TIMES_SCALAR, v2, slope, reach))
            source = typed(_FADD, v2, source, typed(_VECTOR_TIMES_SCALAR, v2, typed(_FSUB, v2, aim, source), self.step))
        a = self.zero_v3
        for (k, _, _, curvature, _), start in zip(self.waves, starts, strict=True):
            wave = ext(f, _GLSL_SIN, typed(_FADD, f, typed(_DOT, f, k, source), start))
            a = typed(_FADD, v3, a, typed(_VECTOR_TIMES_SCALAR, v3, curvature, wave))
        # J = I - t A: det = (1 - t Axx)(1 - t Azz) - (t Axz)^2.
        ta = typed(_VECTOR_TIMES_SCALAR, v3, a, t)
        xx = typed(_FSUB, f, self.one, typed(_COMPOSITE_EXTRACT, f, ta, 0))
        zz = typed(_FSUB, f, self.one, typed(_COMPOSITE_EXTRACT, f, ta, 2))
        xzv = typed(_COMPOSITE_EXTRACT, f, ta, 1)
        det = typed(_FSUB, f, typed(_FMUL, f, xx, zz), typed(_FMUL, f, xzv, xzv))
        spread = ext(f, _GLSL_FMAX, ext(f, _GLSL_FABS, det), self.floor)
        gathered = typed(_FDIV, f, self.one, spread)
        # Divided by the mean light at this path length, read off the table with tent weights.
        depth = ext(f, _GLSL_FMIN, ext(f, _GLSL_FMAX, t, self.zero), self.last_depth)
        norm = self.zero
        for at, inverse in self.table:
            off = ext(f, _GLSL_FABS, typed(_FSUB, f, depth, at))
            weight = ext(f, _GLSL_FMAX, typed(_FSUB, f, self.one, off), self.zero)
            norm = typed(_FADD, f, norm, typed(_FMUL, f, weight, inverse))
        balanced = typed(_FMUL, f, gathered, norm)
        grey = ext(f, _GLSL_SMOOTHSTEP, self.grey_from, self.grey_to, t)
        gathered = ext(f, _GLSL_FMIX, balanced, self.one, grey)
        water = typed(
            _LOGICAL_AND,
            b,
            typed(_FORD_GREATER_THAN_EQUAL, b, dist, self.water_from),
            typed(_FORD_LESS_THAN, b, dist, cloud_distance),
        )
        if TELLTALE == "1":
            gathered = self.zero
        elif TELLTALE == "stripes":  # 2 * fract(x of the crossing): one stripe a block
            gathered = typed(_FMUL, f, ext(f, 10, typed(_COMPOSITE_EXTRACT, f, xz, 0)), self.brightest)
        elif TELLTALE == "depth":  # the path length in water, a gradient over 4 blocks
            gathered = typed(_FMUL, f, t, self.one)
        factor = typed(_SELECT, f, water, gathered, self.one)
        return typed(_COMPOSITE_CONSTRUCT, v3, factor, factor, factor), code
