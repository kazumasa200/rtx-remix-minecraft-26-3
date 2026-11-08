# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0
"""SPIR-V as words, enough to find patch sites and edit them without renumbering anything.

Passes only change operands and append instructions or ids; every original result id stays, because
the layer copies the runtime's binding literals onto a replacement by decoration target id.
"""

from __future__ import annotations

import bisect
import ctypes
import struct
from dataclasses import dataclass
from pathlib import Path

MAGIC = 0x07230203
SPIRV_1_5 = struct.pack("<II", MAGIC, 0x00010500)

# A delta (.rrpd): magic, version, original words, output words, then operations, each one word of
# (kind << 28 | count): COPY count words of the runtime's module, SKIP count of them, or WORDS count words
# that follow. The layer's rrp_apply is the one applier; the build checks every delta through it.
DELTA_MAGIC, DELTA_VERSION = 0x44505252, 1
_DELTA_COPY, _DELTA_SKIP, _DELTA_WORDS = 1 << 28, 2 << 28, 3 << 28

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

    def __init__(self, words: list[int], origin: list[int | None] | None = None):
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
        # Which instruction of the module as loaded each one is, None for one a pass added or rewrote:
        # what delta() copies from the module the runtime hands over.
        self.origin: list[int | None] = list(range(len(self.instructions))) if origin is None else origin
        if len(self.origin) != len(self.instructions):
            raise ValueError("origin does not match the instructions")
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
        """Rewrites one word in place; its instruction no longer counts as the original's."""
        self.words[index] = value
        n = bisect.bisect_right([i.at for i in self.instructions], index) - 1
        if n >= 0:
            ins = self.instructions[n]
            self.instructions[n] = Instruction(ins.at, ins.op, tuple(self.words[ins.at : ins.at + len(ins.words)]))
            self.origin[n] = None

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
        words, origin = self.words[:5], []
        for index, ins in enumerate(self.instructions):
            if index in self._replace:
                for new in self._replace[index]:
                    words.extend(new)
                    origin.append(None)
            else:
                words.extend(self.words[ins.at : ins.at + len(ins.words)])
                origin.append(self.origin[index])
            for new in self._after.get(index, []):
                words.extend(new)
                origin.append(None)
        self.__init__(words, origin)

    def delta(self, original: Module) -> list[int]:
        """This module as edits of original, the module it was loaded from: the delta the layer applies to
        the module the runtime hands over (`rrp_apply`). COPY takes the runtime's own words, so dxvk's
        binding literals survive; a pass must never write an original binding decoration anew."""
        if self.origin and any(o is not None and o >= len(original.instructions) for o in self.origin):
            raise ValueError("delta against a module this one was not loaded from")
        out = [DELTA_MAGIC, DELTA_VERSION, len(original.words), len(self.words)]
        out += [_DELTA_WORDS | 5, *self.words[:5], _DELTA_SKIP | 5]  # the header, its bound grown
        cursor = 0  # original instruction index the runtime's words are read from next
        pending_words: list[int] = []

        def flush_words():
            if pending_words:
                out.extend([_DELTA_WORDS | len(pending_words), *pending_words])
                pending_words.clear()

        n = 0
        while n < len(self.instructions):
            o = self.origin[n]
            if o is None:
                ins = self.instructions[n]
                if ins.op == OP_DECORATE and len(ins.words) >= 4 and ins.words[2] in (33, 34):
                    if ins.words[1] < original.words[3]:
                        raise ValueError(f"a pass rewrote the binding decoration of original id {ins.words[1]}")
                pending_words.extend(ins.words)
                n += 1
                continue
            flush_words()
            if o < cursor:
                raise ValueError("instructions out of their original order")
            if o > cursor:
                out.append(_DELTA_SKIP | (original.instructions[o].at - original.instructions[cursor].at))
            start, end = o, o + 1
            n += 1
            while n < len(self.instructions) and self.origin[n] == end:
                end += 1
                n += 1
            last = original.instructions[end - 1]
            words = last.at + len(last.words) - original.instructions[start].at
            out.append(_DELTA_COPY | words)
            cursor = end
        flush_words()
        return out


def apply_delta(incoming: list[int], delta: list[int]) -> list[int]:
    """The layer's rrp_apply in Python, for tests: delta applied to the module the runtime hands over."""
    if len(delta) < 4 or delta[0] != DELTA_MAGIC or delta[1] != DELTA_VERSION or delta[2] != len(incoming):
        raise ValueError("not a delta for this module")
    out: list[int] = []
    i, cursor = 4, 0
    while i < len(delta):
        kind, count = delta[i] & 0xF0000000, delta[i] & 0x0FFFFFFF
        i += 1
        if kind == _DELTA_COPY:
            if cursor + count > len(incoming):
                raise ValueError("delta copies past the module")
            out += incoming[cursor : cursor + count]
            cursor += count
        elif kind == _DELTA_SKIP:
            cursor += count
        elif kind == _DELTA_WORDS:
            if i + count > len(delta):
                raise ValueError("delta ends inside its words")
            out += delta[i : i + count]
            i += count
        else:
            raise ValueError("unknown delta operation")
    if len(out) != delta[3]:
        raise ValueError("delta output size mismatch")
    return out


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
