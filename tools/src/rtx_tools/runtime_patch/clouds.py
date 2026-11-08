# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0
"""The Unbound clouds inside the runtime: the passes that call the clouds library (library.py) where the runtime
reads the sky.

clouds-primary: the composite lays the sky matte over a primary ray that missed as skyBrightness * matte *
volume attenuation. The clouds the camera sees along that ray, marched per pixel from the runtime's own camera,
go over the matte there, premultiplied: matte * (1 - a) + clouds, in the matte's units, so they take the same
brightness and fog as the sky did and hide the sun's disc where they cover it. The composite reads the matte a
second time inside the view-distance fade, for land; that copy is left to the sky cache.

clouds-sky: every other read of the sky (the probe, for reflections, rays through glass or water and indirect
rays that missed, and the composite's second matte read) takes the clouds the layer's sky cache holds for its
direction over it the same way. The sample's result id is taken over: the sample defines a new id and the
composited sky defines the old one, so none of its uses is rewritten.

clouds-shadow: every shadow ray-query loop multiplies its ray's light by the share the clouds let through, which
is 1 unless the ray is the clouds' light's (the library gates on a distant light's length and the light's cone).
"""

from __future__ import annotations

from . import library, merge
from .spirv import OP_CONSTANT, OP_LOAD, Module, f32, op

_IMAGE_SAMPLE_EXPLICIT_LOD, _VECTOR_SHUFFLE, _VECTOR_TIMES_SCALAR, _BITCAST = 88, 79, 142, 124
_ACCESS_CHAIN, _COMPOSITE_EXTRACT, _MEMBER_NAME, _TYPE_POINTER, _TYPE_VECTOR, _TYPE_FLOAT = 65, 81, 6, 32, 23, 22
_TYPE_INT, _FSUB, _FADD = 21, 131, 129
_COMPOSITE_CONSTRUCT, _PHI, _STORE, _SELECT = 80, 245, 62, 169
_GLSL_LENGTH = 66
_RQ_WORLD_DIRECTION, _RQ_WORLD_ORIGIN = 6029, 6030
_STORAGE_UNIFORM = 2


def _first_of(module: Module, opcode: int, words: tuple) -> int | None:
    return next((i.words[1] for i in module.instructions if i.op == opcode and i.words[2:] == words), None)


class MatteSite:
    """The composite's first read of the sky matte (the primary miss) and what is in scope there."""

    def __init__(self, module: Module):
        self.ok = False
        sky_light = module.named("SkyLight")
        sky_probe = module.named("SkyProbe")
        if len(sky_light) != 1 or len(sky_probe) != 1:
            return
        ins = module.instructions
        loads = [n for n, i in enumerate(ins) if i.op == OP_LOAD and i.words[3] == sky_light[0]]
        if not loads:
            return
        n = loads[0]
        if not (ins[n + 1].op == _IMAGE_SAMPLE_EXPLICIT_LOD and ins[n + 2].op == _VECTOR_SHUFFLE
                and ins[n + 3].op == _VECTOR_TIMES_SCALAR and ins[n + 3].words[3] == ins[n + 2].words[2]):
            return
        self.shuffle, self.scale = n + 2, n + 3
        self.matte = ins[n + 2].words[2]
        # The probe branch beside it samples the world direction the matte branch has in scope.
        probe_loads = {i.words[2] for i in ins[n : n + 40] if i.op == OP_LOAD and i.words[3] == sky_probe[0]}
        samples = [i for i in ins[n : n + 40] if i.op == _IMAGE_SAMPLE_EXPLICIT_LOD and i.words[3] in probe_loads]
        # The pixel: the matte's screen coordinate starts from it, bitcast to signed.
        bitcasts = [i for i in ins[max(0, n - 20) : n] if i.op == _BITCAST]
        if len(samples) != 1 or len(bitcasts) != 1:
            return
        self.direction = samples[0].words[4]
        self.pixel = bitcasts[0].words[3]
        self.ok = True


class LandSite:
    """The composite's last word on a pixel, radianceOutput * backgroundAlpha + alphaBlendOutput, and what the
    clouds in front of land need there: the primary ray's length (viewDistance, the Length of the view position
    the fade scales the world direction by) and whether the pixel's view Z is the miss sentinel, which a primary
    ray that crossed glass or water into the sky also carries."""

    def __init__(self, module: Module, direction: int):
        self.ok = False
        ins = module.instructions
        defs = {i.words[2]: i for i in ins if len(i.words) > 2 and i.op in (OP_LOAD, _VECTOR_TIMES_SCALAR, 12, 180)}
        finals = []
        for n, i in enumerate(ins):
            if i.op != _FADD or len(i.words) != 5:
                continue
            scaled, blend = defs.get(i.words[3]), defs.get(i.words[4])
            after = ins[n + 1] if n + 1 < len(ins) else None
            if (scaled is not None and scaled.op == _VECTOR_TIMES_SCALAR and blend is not None and blend.op == OP_LOAD
                    and after is not None and after.op == _COMPOSITE_CONSTRUCT and after.words[3] == i.words[2]
                    and len(after.words) == 5):
                radiance = defs.get(scaled.words[3])
                if radiance is not None and radiance.op == OP_LOAD:
                    finals.append((n, scaled, radiance))
        lengths = [i.words[4] for i in ins if i.op == _VECTOR_TIMES_SCALAR and i.words[3] == direction
                   and defs.get(i.words[4]) is not None and defs[i.words[4]].op == 12
                   and defs[i.words[4]].words[4] == _GLSL_LENGTH]
        view_z = module.named("PrimaryLinearViewZ")
        if len(finals) != 1 or len(lengths) != 1 or len(view_z) != 1:
            return
        fetch_loads = {i.words[2] for i in ins if i.op == OP_LOAD and i.words[3] == view_z[0]}
        fetches = {i.words[2] for i in ins if i.op == 95 and i.words[3] in fetch_loads}  # OpImageFetch
        extracts = {i.words[2] for i in ins if i.op == _COMPOSITE_EXTRACT and i.words[3] in fetches}
        sentinel = [i.words[2] for i in ins if i.op == 180 and i.words[3] in extracts]  # OpFOrdEqual
        if len(sentinel) != 1:
            return
        n, scaled, radiance = finals[0]
        self.scaled = next(k for k in range(n - 4, n) if ins[k].words[2:3] == (scaled.words[2],))
        self.radiance = radiance.words[2]
        self.view_distance = lengths[0]
        self.missed = sentinel[0]
        self.ok = True


def _sky_brightness(module: Module, code: list[list[int]]) -> int | None:
    """Code that loads cb.skyBrightness, copied from the composite's own chain beside its matte read; its id."""
    ins = module.instructions
    matte = module.named("SkyLight")
    cb = module.named("cb")
    if len(matte) != 1 or len(cb) != 1:
        return None
    load = next(n for n, i in enumerate(ins) if i.op == OP_LOAD and i.words[3] == matte[0])
    for k in range(load - 1, max(0, load - 6), -1):
        if ins[k].op == _ACCESS_CHAIN and ins[k].words[3] == cb[0]:
            chain, value = module.new_id(), module.new_id()
            t_float = next(i.words[1] for i in ins if i.op == OP_LOAD and i.words[3] == ins[k].words[2])
            code += [op(_ACCESS_CHAIN, ins[k].words[1], chain, *ins[k].words[3:]), op(OP_LOAD, t_float, value, chain)]
            return value
    return None


def _camera_position(module: Module, code: list[list[int]]) -> int | None:
    """Code that loads the runtime camera's position (cb.camera.viewToWorld translation); its id."""
    ins = module.instructions
    cb = module.named("cb")
    camera = module.named("Camera_natural")
    if len(cb) != 1 or len(camera) != 1:
        return None
    members = {i.words[2]: i for i in ins if i.op == _MEMBER_NAME and i.words[1] == camera[0]}
    view_to_world = next((m for m, i in members.items() if merge._string(i.words[3:]) == "viewToWorld"), None)
    if view_to_world is None:
        return None
    # The runtime's own chain to cb.camera.viewToWorld gives every type and constant the load needs.
    chains = {i.words[2]: i for i in ins if i.op == _ACCESS_CHAIN and len(i.words) == 5}
    constants = {i.words[2]: i for i in ins if i.op == OP_CONSTANT}
    for n, i in enumerate(ins):
        if i.op != _ACCESS_CHAIN or len(i.words) != 5:
            continue
        base = chains.get(i.words[3])
        if base is None or base.words[3] != cb[0]:
            continue
        index = constants.get(i.words[4])
        if index is None or index.op != OP_CONSTANT or index.words[3] != view_to_world:
            continue
        load = next((j for j in ins[n + 1 : n + 3] if j.op == OP_LOAD and j.words[3] == i.words[2]), None)
        extract = next((j for j in ins[n + 1 : n + 4] if j.op == _COMPOSITE_EXTRACT and j.words[3] == load.words[2]),
                       None) if load else None
        if extract is None:
            return None
        t_float = _first_of(module, _TYPE_FLOAT, (32,))
        t_v4 = _first_of(module, _TYPE_VECTOR, (t_float, 4))
        t_v3 = _first_of(module, _TYPE_VECTOR, (t_float, 3))
        chain_camera, chain_matrix = module.new_id(), module.new_id()
        matrix, columns, column, position = module.new_id(), module.new_id(), module.new_id(), module.new_id()
        code += [
            op(_ACCESS_CHAIN, base.words[1], chain_camera, cb[0], base.words[4]),
            op(_ACCESS_CHAIN, i.words[1], chain_matrix, chain_camera, i.words[4]),
            op(OP_LOAD, load.words[1], matrix, chain_matrix),
            op(_COMPOSITE_EXTRACT, extract.words[1], columns, matrix, 0),
            op(_COMPOSITE_EXTRACT, t_v4, column, columns, 3),  # the matrices are stored by column
            op(_VECTOR_SHUFFLE, t_v3, position, column, column, 0, 1, 2),
        ]
        return position
    return None


def apply_primary(module: Module) -> int:
    """clouds-primary on one module: 2 when it patched the composite's primary miss and its land, 0 elsewhere."""
    if not MatteSite(module).ok:
        return 0
    fn = merge.merge(module, library.clouds_library(), "rrp_cloudsPrimary")
    fn_land = merge.merge(module, library.clouds_library(), "rrp_cloudsLand")
    site = MatteSite(module)
    land = LandSite(module, site.direction)
    if not land.ok:
        raise ValueError("the composite's final combination was not found")
    ins = module.instructions
    t_float = _first_of(module, _TYPE_FLOAT, (32,))
    t_v3 = _first_of(module, _TYPE_VECTOR, (t_float, 3))
    t_v4 = _first_of(module, _TYPE_VECTOR, (t_float, 4))

    def constant(value: float) -> int:
        return next(i.words[2] for i in ins if i.op == OP_CONSTANT and i.words[1] == t_float and i.words[3] == f32(value))

    one, zero = constant(1.0), constant(0.0)

    def over(code: list[list[int]], under: int, clouds: int, scale: int | None) -> int:
        """under * (1 - clouds.a) + clouds.rgb (times scale): the clouds laid over what lies behind them."""
        rgb, alpha, keep, kept, mixed = (module.new_id() for _ in range(5))
        code += [
            op(_VECTOR_SHUFFLE, t_v3, rgb, clouds, clouds, 0, 1, 2),
            op(_COMPOSITE_EXTRACT, t_float, alpha, clouds, 3),
            op(_FSUB, t_float, keep, one, alpha),
            op(_VECTOR_TIMES_SCALAR, t_v3, kept, under, keep),
        ]
        if scale is not None:
            lit = module.new_id()
            code.append(op(_VECTOR_TIMES_SCALAR, t_v3, lit, rgb, scale))
            rgb = lit
        code.append(op(_FADD, t_v3, mixed, kept, rgb))
        return mixed

    # The primary miss: over the sky matte, before the sky's brightness and the fog's attenuation.
    code: list[list[int]] = []
    camera = _camera_position(module, code)
    if camera is None:
        raise ValueError("the composite's camera position was not found")
    clouds, call = merge.call(module, site.shuffle, fn, [site.direction, camera, site.pixel], t_v4)
    code += call
    mixed = over(code, site.matte, clouds, None)
    module.insert_after(site.shuffle, *code)
    scale = list(ins[site.scale].words)
    scale[3] = mixed
    module.replace(site.scale, scale)

    # Land: the clouds between the camera and the surface, over everything else the pixel shows, in radiance
    # (the matte's units times the sky's brightness). A miss, or sky seen through glass, marches nothing.
    code = []
    camera = _camera_position(module, code)
    brightness = _sky_brightness(module, code)
    if brightness is None:
        raise ValueError("the composite's sky brightness was not found")
    limit = module.new_id()
    code.append(op(_SELECT, t_float, limit, land.missed, zero, land.view_distance))
    clouds, call = merge.call(module, land.scaled, fn_land, [site.direction, camera, site.pixel, limit], t_v4)
    code += call
    mixed = over(code, land.radiance, clouds, brightness)
    module.insert_after(land.scaled - 1, *code)
    scaled = list(ins[land.scaled].words)
    scaled[3] = mixed
    module.replace(land.scaled, scaled)
    module.commit()
    return 2


def _sky_reads(module: Module) -> list[tuple[int, int]]:
    """(index of the sample, its world direction) for every sky read clouds-sky covers: each SkyProbe sample, and
    every SkyLight sample but the composite's first (clouds-primary's), whose direction is the probe sample's
    beside it."""
    ins = module.instructions
    probe = module.named("SkyProbe")
    matte = module.named("SkyLight")
    if len(probe) != 1:
        return []
    probe_loads = {i.words[2] for i in ins if i.op == OP_LOAD and i.words[3] == probe[0]}
    sites = [(n, i.words[4]) for n, i in enumerate(ins) if i.op == _IMAGE_SAMPLE_EXPLICIT_LOD and i.words[3] in probe_loads]
    if len(matte) == 1:
        matte_loads = {i.words[2] for i in ins if i.op == OP_LOAD and i.words[3] == matte[0]}
        matte_samples = [n for n, i in enumerate(ins) if i.op == _IMAGE_SAMPLE_EXPLICIT_LOD and i.words[3] in matte_loads]
        for n in matte_samples[1:]:
            beside = [d for m, d in sites if n < m < n + 40]
            if len(beside) == 1:
                sites.append((n, beside[0]))
    return sorted(sites)


def apply_sky(module: Module) -> int:
    """clouds-sky on one module; the number of sky reads it covered."""
    if not _sky_reads(module):
        return 0
    fn = merge.merge(module, library.clouds_library(), "rrp_cloudsSky")
    sites = _sky_reads(module)
    t_float = _first_of(module, _TYPE_FLOAT, (32,))
    t_v3 = _first_of(module, _TYPE_VECTOR, (t_float, 3))
    t_v4 = _first_of(module, _TYPE_VECTOR, (t_float, 4))
    one = next(i.words[2] for i in module.instructions
               if i.op == OP_CONSTANT and i.words[1] == t_float and i.words[3] == f32(1.0))
    for n, direction in sites:
        sample = list(module.instructions[n].words)
        if sample[1] != t_v4:
            raise ValueError("a sky sample that is not a vec4")
        sky, taken = sample[2], module.new_id()
        sample[2] = taken
        clouds, code = merge.call(module, n, fn, [direction], t_v4)
        rgb, cloud_rgb, alpha, keep, kept, mixed, w = (module.new_id() for _ in range(7))
        code += [
            op(_VECTOR_SHUFFLE, t_v3, rgb, taken, taken, 0, 1, 2),
            op(_VECTOR_SHUFFLE, t_v3, cloud_rgb, clouds, clouds, 0, 1, 2),
            op(_COMPOSITE_EXTRACT, t_float, alpha, clouds, 3),
            op(_FSUB, t_float, keep, one, alpha),
            op(_VECTOR_TIMES_SCALAR, t_v3, kept, rgb, keep),
            op(_FADD, t_v3, mixed, kept, cloud_rgb),
            op(_COMPOSITE_EXTRACT, t_float, w, taken, 3),
            op(_COMPOSITE_CONSTRUCT, t_v4, sky, mixed, w),
        ]
        module.replace(n, sample)
        module.insert_after(n, *code)
    module.commit()
    return len(sites)


def apply_shadow(module: Module) -> int:
    """clouds-shadow on one module; the number of shadow loops it covered."""
    from . import shadow

    if not shadow.find_sites(module):
        return 0
    fn = merge.merge(module, library.clouds_library(), "rrp_cloudShadow")
    sites = shadow.find_sites(module)
    ins = module.instructions
    t_float = _first_of(module, _TYPE_FLOAT, (32,))
    t_v3 = _first_of(module, _TYPE_VECTOR, (t_float, 3))
    far = module.new_id()
    module.insert_after(next(n for n, i in enumerate(ins) if i.op == 54) - 1, op(OP_CONSTANT, t_float, far, f32(1.0e30)))
    loops = {}
    for site in sites:
        loops.setdefault(site.loop[0], site)
    for header, site in loops.items():
        _, merge_label, rq = site.loop
        at = merge_label
        while at + 1 < len(ins) and ins[at + 1].op == _PHI:
            at += 1
        origin, direction, factor = module.new_id(), module.new_id(), None
        code = [op(_RQ_WORLD_ORIGIN, t_v3, origin, rq), op(_RQ_WORLD_DIRECTION, t_v3, direction, rq)]
        if site.t_max is not None:
            t_max = module.new_id()
            code.append(op(OP_LOAD, t_float, t_max, site.t_max))
        else:
            t_max = far
        factor, call = merge.call(module, at, fn, [origin, direction, t_max], t_float)
        light, scaled = module.new_id(), module.new_id()
        code += call + [
            op(OP_LOAD, t_v3, light, site.light),
            op(_VECTOR_TIMES_SCALAR, t_v3, scaled, light, factor),
            op(_STORE, site.light, scaled),
        ]
        module.insert_after(at, *code)
    module.commit()
    return len(loops)
