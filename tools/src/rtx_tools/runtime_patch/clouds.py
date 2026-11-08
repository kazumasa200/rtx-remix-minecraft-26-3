# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0
"""The Unbound clouds inside the runtime: the passes that call the clouds library (library.py) where the runtime
reads the sky.

clouds-primary: the composite lays the sky matte over a primary ray that missed as skyBrightness * matte *
volume attenuation. The clouds the camera sees along that ray, marched per pixel from the runtime's own camera,
go over the matte there, premultiplied: matte * (1 - a) + clouds, in the matte's units, so they take the same
brightness and fog as the sky did and hide the sun's disc where they cover it. The composite reads the matte a
second time inside the view-distance fade, for land; that copy is left to the sky cache.
"""

from __future__ import annotations

from . import library, merge
from .spirv import OP_CONSTANT, OP_LOAD, Module, f32, op

_IMAGE_SAMPLE_EXPLICIT_LOD, _VECTOR_SHUFFLE, _VECTOR_TIMES_SCALAR, _BITCAST = 88, 79, 142, 124
_ACCESS_CHAIN, _COMPOSITE_EXTRACT, _MEMBER_NAME, _TYPE_POINTER, _TYPE_VECTOR, _TYPE_FLOAT = 65, 81, 6, 32, 23, 22
_TYPE_INT, _FSUB, _FADD = 21, 131, 129
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
    """clouds-primary on one module; 1 when it patched the composite's primary miss, 0 elsewhere."""
    if not MatteSite(module).ok:
        return 0
    fn = merge.merge(module, library.clouds_library(), "rrp_cloudsPrimary")
    site = MatteSite(module)
    t_float = _first_of(module, _TYPE_FLOAT, (32,))
    t_v3 = _first_of(module, _TYPE_VECTOR, (t_float, 3))
    t_v4 = _first_of(module, _TYPE_VECTOR, (t_float, 4))
    one = next(i.words[2] for i in module.instructions
               if i.op == OP_CONSTANT and i.words[1] == t_float and i.words[3] == f32(1.0))
    code: list[list[int]] = []
    camera = _camera_position(module, code)
    if camera is None:
        raise ValueError("the composite's camera position was not found")
    clouds, call = merge.call(module, site.shuffle, fn, [site.direction, camera, site.pixel], t_v4)
    code += call
    rgb, alpha, keep, kept, mixed = (module.new_id() for _ in range(5))
    code += [
        op(_VECTOR_SHUFFLE, t_v3, rgb, clouds, clouds, 0, 1, 2),
        op(_COMPOSITE_EXTRACT, t_float, alpha, clouds, 3),
        op(_FSUB, t_float, keep, one, alpha),
        op(_VECTOR_TIMES_SCALAR, t_v3, kept, site.matte, keep),
        op(_FADD, t_v3, mixed, kept, rgb),
    ]
    module.insert_after(site.shuffle, *code)
    scale = list(module.instructions[site.scale].words)
    scale[3] = mixed
    module.replace(site.scale, scale)
    module.commit()
    return 1
