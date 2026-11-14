# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0
"""HDR output in the runtime's two image-finishing passes, over the word-level IR, calling the library
runtime-patch/shaders/rrp_hdr.comp. Both take over a result id and leave every other id as it was (glass.py's way).

hdr-combine: the local tonemapper's final combine (the module naming FinalCombineArgs_natural) stores texelFinal,
ACES'd and clamped to [0, 1], into OutputTexture. That value goes through rrp_hdrCombine with the exposed linear
texelFinal before the ACES (the OpVectorTimesScalar named texelFinal) and the module's own ACES matrices, so the
module writes scRGB when the frame says so and exactly what it wrote otherwise.

hdr-encode: the sRGB-and-dither pass (SRGBDitherArgs_natural) clamps its result to [0, 1] before writing it back in
place. The clamped value goes through rrp_hdrEncode with the pass's input (the OpVectorShuffle named inputColor),
which in HDR returns the input: no gamma, no dither, no clamp.
"""

from __future__ import annotations

from . import library, merge
from .glass import _results, _types
from .spirv import OP_LOAD, OP_VARIABLE, Module, op

_EXT_INST, _CONSTANT_COMPOSITE, _VECTOR_SHUFFLE, _COMPOSITE_CONSTRUCT, _COPY_OBJECT, _IMAGE_WRITE = (
    12,
    44,
    79,
    80,
    83,
    99,
)
_VECTOR_TIMES_SCALAR, _VECTOR_TIMES_MATRIX = 142, 144
_GLSL_FCLAMP = 43


def _written(module: Module, image_variable: int) -> list[tuple[int, int]]:
    """(index of each OpImageWrite to the image variable, the id of the vec4 it writes)."""
    ins = module.instructions
    results = _results(module)
    out = []
    for n, i in enumerate(ins):
        if i.op != _IMAGE_WRITE:
            continue
        image = results.get(i.words[1])
        if image is not None and ins[image].op == OP_LOAD and ins[image].words[3] == image_variable:
            out.append((n, i.words[3]))
    return out


def _first_constituent(module: Module, texel: int) -> int | None:
    """The index of the instruction defining the first constituent of the OpCompositeConstruct texel."""
    ins = module.instructions
    results = _results(module)
    k = results.get(texel)
    if k is None or ins[k].op != _COMPOSITE_CONSTRUCT or len(ins[k].words) < 4:
        return None
    return results.get(ins[k].words[3])


def _one(module: Module, name: str, opcode: int) -> int | None:
    """The one id named name whose defining instruction is opcode."""
    ins = module.instructions
    results = _results(module)
    found = {i for i in module.named(name) if (k := results.get(i)) is not None and ins[k].op == opcode}
    return found.pop() if len(found) == 1 else None


def combine_sites(module: Module) -> list[tuple[int, int, int, int]]:
    """[(index of the OpLoad of texelFinal that the OutputTexture write stores, the exposed texelFinal, the ACES
    input matrix, the ACES output matrix)], or [] outside the final combine."""
    if not module.named("FinalCombineArgs_natural"):
        return []
    ins = module.instructions
    variable = _one(module, "texelFinal", OP_VARIABLE)
    exposed = _one(module, "texelFinal", _VECTOR_TIMES_SCALAR)
    output = _one(module, "OutputTexture", OP_VARIABLE)
    if variable is None or exposed is None or output is None:
        return []
    # The runtime's ACES matrices are constants; a merged rrp_hdrCombine multiplies by its loaded parameters.
    results = _results(module)
    constant = {
        i.words[4] for i in ins if i.op == _VECTOR_TIMES_MATRIX and ins[results[i.words[4]]].op == _CONSTANT_COMPOSITE
    }
    matrices = {i.words[4] for i in ins if i.op == _VECTOR_TIMES_MATRIX and i.words[3] == exposed} & constant
    others = constant - matrices
    if len(matrices) != 1 or len(others) != 1:
        return []
    sites = []
    for _, texel in _written(module, output):
        k = _first_constituent(module, texel)
        if k is not None and ins[k].op == OP_LOAD and ins[k].words[3] == variable:
            sites.append((k, exposed, next(iter(matrices)), next(iter(others))))
    return sites


def apply_combine(module: Module) -> int:
    """hdr-combine on one module; the number of stores covered."""
    if not combine_sites(module):
        return 0
    fn = merge.merge(module, library.hdr_library(), "rrp_hdrCombine")
    _, _, t_v3 = _types(module)
    sites = combine_sites(module)
    for n, exposed, aces_in, aces_out in sites:
        words = list(module.instructions[n].words)
        shown, raw = words[2], module.new_id()
        words[2] = raw
        lifted, code = merge.call(module, n, fn, [raw, exposed, aces_in, aces_out], t_v3)
        module.replace(n, words, *code, op(_COPY_OBJECT, t_v3, shown, lifted))
    module.commit()
    return len(sites)


def encode_sites(module: Module) -> list[tuple[int, int]]:
    """[(index of the GLSL FClamp whose result the InOutColorBuffer write stores, the pass's input colour)], or []
    outside the sRGB-and-dither pass."""
    if not module.named("SRGBDitherArgs_natural"):
        return []
    glsl, _, _ = _types(module)
    ins = module.instructions
    colour = _one(module, "inputColor", _VECTOR_SHUFFLE)
    buffer = _one(module, "InOutColorBuffer", OP_VARIABLE)
    if glsl is None or colour is None or buffer is None:
        return []
    sites = []
    for _, texel in _written(module, buffer):
        k = _first_constituent(module, texel)
        if k is not None and ins[k].op == _EXT_INST and ins[k].words[3:5] == (glsl, _GLSL_FCLAMP):
            sites.append((k, colour))
    return sites


def apply_encode(module: Module) -> int:
    """hdr-encode on one module; the number of writes covered."""
    if not encode_sites(module):
        return 0
    fn = merge.merge(module, library.hdr_library(), "rrp_hdrEncode")
    _, _, t_v3 = _types(module)
    sites = encode_sites(module)
    for n, colour in sites:
        words = list(module.instructions[n].words)
        shown, raw = words[2], module.new_id()
        words[2] = raw
        passed, code = merge.call(module, n, fn, [raw, colour], t_v3)
        module.replace(n, words, *code, op(_COPY_OBJECT, t_v3, shown, passed))
    module.commit()
    return len(sites)
