# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0
"""Which words of a SPIR-V instruction are ids, from the core grammar: what the library merge needs to renumber
an instruction it copies. The table (operands.json beside this file) is generated from the Vulkan SDK's
spirv.core.grammar.json by generate(); building needs only the table, and a test checks it against the SDK
when one is installed.
"""

from __future__ import annotations

import json
from functools import cache
from pathlib import Path

TABLE = Path(__file__).with_name("operands.json")
_ID_KINDS = {"IdResultType", "IdResult", "IdRef", "IdMemorySemantics", "IdScope"}
_ONE_WORD_LITERALS = {"LiteralInteger", "LiteralExtInstInteger", "LiteralSpecConstantOpInteger", "LiteralFloat"}
_OP_SWITCH, _OP_SPEC_CONSTANT_OP = 251, 52


def generate(grammar: Path) -> dict:
    """The compact table: per opcode its operands as [kind, quantifier]; per enum kind its category and the
    operand kinds each enumerant value takes."""
    g = json.loads(grammar.read_text(encoding="utf-8"))
    instructions = {
        str(i["opcode"]): [[o["kind"], o.get("quantifier", "")] for o in i.get("operands", [])]
        for i in g["instructions"]
    }
    kinds = {}
    for k in g["operand_kinds"]:
        if k["category"] in ("ValueEnum", "BitEnum"):
            params = {}
            for e in k.get("enumerants", []):
                if e.get("parameters"):
                    value = e["value"] if isinstance(e["value"], int) else int(e["value"], 0)
                    params[str(value)] = [p["kind"] for p in e["parameters"]]
            kinds[k["kind"]] = {"category": k["category"], "params": params}
        elif k["category"] == "Composite":
            kinds[k["kind"]] = {"category": "Composite", "bases": k["bases"]}
    return {"instructions": instructions, "kinds": kinds}


@cache
def _table() -> dict:
    return json.loads(TABLE.read_text(encoding="utf-8"))


def result_position(words: tuple[int, ...] | list[int]) -> int | None:
    """Index into words of the instruction's result id, or None when it has none."""
    operands = _table()["instructions"].get(str(words[0] & 0xFFFF))
    if operands is None:
        raise ValueError(f"opcode {words[0] & 0xFFFF} not in the grammar table")
    if operands[:2] == [["IdResultType", ""], ["IdResult", ""]]:
        return 2
    if operands[:1] == [["IdResult", ""]]:
        return 1
    return None


def id_positions(words: tuple[int, ...] | list[int]) -> list[int]:
    """Indices into words (the whole instruction, opcode word included) of every id operand, the result and
    its type among them. Raises ValueError for an opcode or operand the table does not cover."""
    opcode, count = words[0] & 0xFFFF, words[0] >> 16
    operands = _table()["instructions"].get(str(opcode))
    if operands is None:
        raise ValueError(f"opcode {opcode} not in the grammar table")
    kinds = _table()["kinds"]
    out: list[int] = []
    at = 1

    def take(kind: str) -> None:
        nonlocal at
        if kind in _ID_KINDS:
            out.append(at)
            at += 1
        elif kind in _ONE_WORD_LITERALS:
            at += 1
        elif kind == "LiteralString":
            while at < count:
                word = words[at]
                at += 1
                if any((word >> shift) & 0xFF == 0 for shift in (0, 8, 16, 24)):
                    break
        elif kind == "LiteralContextDependentNumber":
            # The last operand of OpConstant and OpSpecConstant: the rest of the instruction.
            at = count
        elif kind in kinds and kinds[kind]["category"] == "Composite":
            for base in kinds[kind]["bases"]:
                take(base)
        elif kind in kinds:
            spec = kinds[kind]
            value = words[at]
            at += 1
            if spec["category"] == "ValueEnum":
                for param in spec["params"].get(str(value), []):
                    take(param)
            else:
                for bit in range(32):
                    if value & (1 << bit):
                        for param in spec["params"].get(str(1 << bit), []):
                            take(param)
        else:
            raise ValueError(f"operand kind {kind} of opcode {opcode} not in the grammar table")

    if opcode == _OP_SWITCH:  # selector, default, then (32-bit literal, label) pairs
        out += [1, 2]
        out += list(range(4, count, 2))
        return out
    if opcode == _OP_SPEC_CONSTANT_OP:  # type, result, the wrapped opcode, then that opcode's operands
        wrapped = [((count - 1) << 16) | words[3], words[1], words[2], *words[4:count]]
        return [p if p < 3 else p + 1 for p in id_positions(wrapped)]
    for kind, quantifier in operands:
        if quantifier == "*":
            while at < count:
                take(kind)
        elif quantifier == "?":
            if at < count:
                take(kind)
        else:
            take(kind)
    if at != count:
        raise ValueError(f"opcode {opcode}: operands end at word {at} of {count}")
    return out
