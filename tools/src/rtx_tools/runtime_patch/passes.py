# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0
"""The runtime patch passes: the one list of what the patch set changes.

A pass finds its sites by pattern in a module and edits them in place. It declares how many modules
and sites it expects on the pinned runtime; a pass whose matches fall outside that range is left out of
the patch set (and fails a strict build), so a runtime update that changes the inlined code is noticed
at build time rather than shipped half patched.
"""

from __future__ import annotations

from dataclasses import dataclass

from . import shadow
from .spirv import (
    OP_BRANCH_CONDITIONAL,
    OP_CONSTANT,
    OP_CONSTANT_FALSE,
    OP_FORD_EQUAL,
    OP_IEQUAL,
    OP_LABEL,
    OP_LOAD,
    OP_LOGICAL_NOT,
    OP_STORE,
    Module,
)


@dataclass(frozen=True)
class Expect:
    modules: range
    sites_per_module: range


class Pass:
    id: str
    version: int
    expect: Expect

    @property
    def group(self) -> str:
        """The patch group the pass belongs to: the layer applies a group whole or not at all, and the mod
        asks for it by this name (RuntimePatchLayer.isActive). A pass is its own group unless it says."""
        return self.id

    def apply(self, module: Module) -> int:
        """Edits the module's sites in place; returns how many it changed (0 when the pattern is absent)."""
        raise NotImplementedError


class SkyFade(Pass):
    """The composite's fade to sky (rtx.viewDistance.distanceMode = 3) ran for every pixel that was not a
    primary miss. A primary ray that crossed glass or water into the sky is not a miss but stores the miss
    view Z, so the fade replaced its tinted sky with untinted sky. The guard `!primaryMiss` is pointed at
    the view-Z comparison primaryMiss is built from (`viewZ == missViewZ && !performedAnyPSR`).

    Pattern: the variable named primaryMiss; the OpFOrdEqual branched on just before a block that stores false
    into it; and
    an OpLogicalNot of a load of primaryMiss shortly after an OpIEqual against the constant 3.
    """

    id = "sky-fade"
    version = 1
    expect = Expect(modules=range(1, 2), sites_per_module=range(1, 2))

    def apply(self, module: Module) -> int:
        variables = set(module.named("primaryMiss"))
        if not variables:
            return 0
        # `viewZ == missViewZ && ...` short-circuits: the comparison is the condition of the branch whose
        # false side's block stores false into primaryMiss.
        false_ids = {ins.words[2] for ins in module.instructions if ins.op == OP_CONSTANT_FALSE}
        label_at = {ins.words[1]: index for index, ins in enumerate(module.instructions) if ins.op == OP_LABEL}
        view_z_test = None
        for ins in module.instructions:
            if ins.op != OP_BRANCH_CONDITIONAL or ins.words[3] not in label_at:
                continue
            after = module.instructions[label_at[ins.words[3]] + 1]
            if after.op == OP_STORE and after.words[1] in variables and after.words[2] in false_ids:
                condition = module.defining(ins.words[1])
                if condition is not None and condition.op == OP_FORD_EQUAL:
                    view_z_test = condition.words[2]
        if view_z_test is None:
            return 0
        loads = {ins.words[2] for ins in module.instructions if ins.op == OP_LOAD and ins.words[3] in variables}
        threes = {
            ins.words[2]
            for ins in module.instructions
            if ins.op == OP_CONSTANT and len(ins.words) == 4 and ins.words[3] == 3
        }
        changed = 0
        for index, ins in enumerate(module.instructions):
            if ins.op != OP_LOGICAL_NOT or ins.words[3] not in loads:
                continue
            window = module.instructions[max(0, index - 12) : index]
            if any(w.op == OP_IEQUAL and (w.words[3] in threes or w.words[4] in threes) for w in window):
                module.set_word(ins.at + 3, view_z_test)
                changed += 1
        return changed


class ShadowAbsorption(Pass):
    """Shadow rays through a thick translucent surface (glass, water) were dimmed by its transmittance once
    per surface crossed, whatever the distance travelled inside, so a lake floor ten blocks down was lit like
    a puddle's. Each shadow ray-query loop now absorbs by the distance between entries and exits
    (`shadow.apply`), physically, as the eye sees through it. The same loop draws the water's caustics, water
    being the surface whose measurement distance is caustics.WATER_MARKER_DISTANCE.
    """

    id = "shadow-absorption"
    version = 2
    expect = Expect(modules=range(149, 150), sites_per_module=range(1, 8))

    GLASS_SCALE = 1.0

    def apply(self, module: Module) -> int:
        return shadow.apply(module, self.GLASS_SCALE, caustics=True)


PASSES: list[Pass] = [SkyFade(), ShadowAbsorption()]
