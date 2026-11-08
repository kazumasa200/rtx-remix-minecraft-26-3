# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0
"""SPIR-V as words, enough to find patch sites and edit them without renumbering anything.

Passes only change operands and append instructions or ids; every original result id stays, because
the layer copies the runtime's binding literals onto a replacement by decoration target id.
"""

from __future__ import annotations

import ctypes
import struct
from dataclasses import dataclass
from pathlib import Path

MAGIC = 0x07230203
SPIRV_1_5 = struct.pack("<II", MAGIC, 0x00010500)

OP_NAME = 5
OP_FUNCTION = 54
OP_FUNCTION_END = 56
OP_VARIABLE = 59
OP_LOAD = 61
OP_STORE = 62
OP_DECORATE = 71
OP_IEQUAL = 170
OP_LOGICAL_NOT = 168
OP_LOGICAL_AND = 167
OP_CONSTANT = 43
OP_CONSTANT_FALSE = 42
OP_LABEL = 248
OP_BRANCH_CONDITIONAL = 250
OP_FORD_EQUAL = 180


@dataclass(frozen=True)
class Instruction:
    at: int  # word index of the opcode word
    op: int
    words: tuple[int, ...]  # the whole instruction, opcode word included

    @property
    def result(self) -> int | None:
        """The result id of an instruction that has a result type, or None."""
        return self.words[2] if len(self.words) > 2 else None


def op(opcode: int, *operands: int) -> list[int]:
    """One instruction's words."""
    return [((len(operands) + 1) << 16) | opcode, *operands]


def f32(value: float) -> int:
    """A float constant's literal word."""
    return struct.unpack("<I", struct.pack("<f", value))[0]


class Module:
    """One module's words, with lookups for pattern passes. A pass edits through set_word, or queues
    instructions with insert_after/replace and new ids with new_id, then calls commit."""

    def __init__(self, words: list[int]):
        if len(words) < 5 or words[0] != MAGIC:
            raise ValueError("not a SPIR-V module")
        self.words = list(words)
        self._after: dict[int, list[list[int]]] = {}
        self._replace: dict[int, list[list[int]]] = {}
        self.instructions: list[Instruction] = []
        i = 5
        while i < len(self.words):
            count, op = self.words[i] >> 16, self.words[i] & 0xFFFF
            if count == 0 or i + count > len(self.words):
                raise ValueError(f"malformed instruction at word {i}")
            self.instructions.append(Instruction(i, op, tuple(self.words[i : i + count])))
            i += count
        self.names: dict[int, str] = {}
        for ins in self.instructions:
            if ins.op == OP_NAME:
                self.names.setdefault(ins.words[1], _string(ins.words[2:]))

    @classmethod
    def from_bytes(cls, data: bytes) -> Module:
        return cls(list(struct.unpack(f"<{len(data) // 4}I", data[: len(data) // 4 * 4])))

    def to_bytes(self) -> bytes:
        return struct.pack(f"<{len(self.words)}I", *self.words)

    def named(self, name: str) -> list[int]:
        return [i for i, n in self.names.items() if n == name]

    def defining(self, result: int) -> Instruction | None:
        for ins in self.instructions:
            if ins.op not in (OP_NAME, OP_DECORATE, OP_STORE) and len(ins.words) > 2 and ins.words[2] == result:
                return ins
        return None

    def set_word(self, index: int, value: int) -> None:
        self.words[index] = value

    def new_id(self) -> int:
        bound = self.words[3]
        self.words[3] = bound + 1
        return bound

    def insert_after(self, index: int, *instructions: list[int]) -> None:
        """Queues instructions after the instruction at index (an index into self.instructions)."""
        self._after.setdefault(index, []).extend(instructions)

    def replace(self, index: int, *instructions: list[int]) -> None:
        """Queues instructions in place of the instruction at index."""
        if index in self._replace:
            raise ValueError(f"instruction {index} replaced twice")
        self._replace[index] = list(instructions)

    def commit(self) -> None:
        """Applies the queued edits and re-reads the module."""
        words = self.words[:5]
        for index, ins in enumerate(self.instructions):
            for new in self._replace.get(index, [self.words[ins.at : ins.at + len(ins.words)]]):
                words.extend(new)
            for new in self._after.get(index, []):
                words.extend(new)
        self.__init__(words)


def _string(words: tuple[int, ...]) -> str:
    raw = struct.pack(f"<{len(words)}I", *words)
    return raw.split(b"\0", 1)[0].decode("utf-8", "replace")


def module_size(blob: bytes) -> int:
    """Bytes of the module at blob[0]: instructions up to the last OpFunctionEnd before a non-OpFunction."""
    words = struct.unpack(f"<{len(blob) // 4}I", blob[: len(blob) // 4 * 4])
    i, end = 5, 0
    while i < len(words):
        count, op = words[i] >> 16, words[i] & 0xFFFF
        if count == 0 or i + count > len(words):
            break
        i += count
        if op == OP_FUNCTION_END:
            end = i
            if i >= len(words) or (words[i] & 0xFFFF) != OP_FUNCTION:
                break
    return end * 4


@dataclass(frozen=True)
class Embedded:
    offset: int  # file offset in d3d9.dll
    size: int  # bytes, as the runtime's .text records it
    data: bytes


def embedded_modules(dll: bytes) -> list[Embedded]:
    """Every SPIR-V 1.5 module embedded in the runtime, in file order."""
    found: list[Embedded] = []
    pos, floor = 0, 0
    while True:
        pos = dll.find(SPIRV_1_5, pos)
        if pos < 0:
            return found
        if pos >= floor and pos % 4 == 0:
            size = module_size(dll[pos : pos + 8 * 1024 * 1024])
            if size > 100:
                found.append(Embedded(pos, size, dll[pos : pos + size]))
                floor = pos + size
        pos += 4


class Keyer:
    """The layer's key rule, called through the built layer DLL so there is one implementation."""

    def __init__(self, layer_dll: Path):
        self._lib = ctypes.CDLL(str(layer_dll))
        self._lib.rrp_key.argtypes = [ctypes.c_void_p, ctypes.c_size_t, ctypes.c_void_p]
        self._lib.rrp_key.restype = ctypes.c_int32

    def key(self, data: bytes) -> str:
        out = ctypes.create_string_buffer(32)
        buf = ctypes.create_string_buffer(data, len(data))
        if self._lib.rrp_key(buf, len(data) // 4, out) != 0:
            raise ValueError("module could not be keyed")
        return out.raw.hex()
