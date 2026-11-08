# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0
"""Copies a function of a compiled GLSL library, and everything it reaches, into a runtime module, keeping
every id the module already has: the layer and the deltas need the runtime's ids where they were.

The library's ids are renumbered above the module's bound. Its types and constants are shared with the
module's where an identical one exists (SPIR-V allows no second copy of a non-aggregate type); structs,
arrays, variables, undefs and anything the library decorates never are, so a library decoration never lands
on the runtime's own id. Every module-scope variable copied joins every entry point's interface, as SPIR-V
1.4 and later require. Which words are ids comes from the grammar table (grammar.py).
"""

from __future__ import annotations

from . import grammar
from .spirv import OP_FUNCTION, OP_FUNCTION_END, OP_NAME, OP_VARIABLE, Module, op

_CAPABILITY, _EXTENSION, _EXT_INST_IMPORT, _MEMORY_MODEL, _ENTRY_POINT = 17, 10, 11, 14, 15
_EXECUTION_MODES = {16, 331}
_DEBUG = {2, 3, 4, 7, 330, 8, 317}  # source, strings, module processed, lines
_NAMES = {5, 6}
_ANNOTATIONS = {71, 72, 73, 74, 75, 332, 5632, 5633}
_FUNCTION_PARAMETER, _FUNCTION_CALL, _STORE = 55, 57, 62
_NEVER_SHARED = {28, 29, 30, OP_VARIABLE, 1}  # OpTypeArray, OpTypeRuntimeArray, OpTypeStruct, OpVariable, OpUndef
_LINKAGE = 41  # LinkageAttributes
_STORAGE_FUNCTION = 7


def _string(words: tuple[int, ...]) -> str:
    raw = b"".join(w.to_bytes(4, "little") for w in words)
    return raw.split(b"\0", 1)[0].decode("utf-8", "replace")


def _sections(module: Module) -> tuple[int, list[str]]:
    """The index of the first function and each instruction's section."""
    first = next((n for n, i in enumerate(module.instructions) if i.op == OP_FUNCTION), len(module.instructions))
    kinds = []
    for n, i in enumerate(module.instructions):
        if n >= first:
            kinds.append("code")
        elif i.op == _CAPABILITY:
            kinds.append("capability")
        elif i.op == _EXTENSION:
            kinds.append("extension")
        elif i.op == _EXT_INST_IMPORT:
            kinds.append("import")
        elif i.op == _MEMORY_MODEL:
            kinds.append("model")
        elif i.op == _ENTRY_POINT:
            kinds.append("entry")
        elif i.op in _EXECUTION_MODES:
            kinds.append("mode")
        elif i.op in _DEBUG:
            kinds.append("debug")
        elif i.op in _NAMES:
            kinds.append("name")
        elif i.op in _ANNOTATIONS:
            kinds.append("annotation")
        else:
            kinds.append("global")
    return first, kinds


def function_named(module: Module, name: str) -> int | None:
    """The id of the function the library names name (glslang names it name(signature))."""
    functions = {i.words[2] for i in module.instructions if i.op == OP_FUNCTION}
    for ins in module.instructions:
        if ins.op == OP_NAME and ins.words[1] in functions and _string(ins.words[2:]).split("(")[0] == name:
            return ins.words[1]
    return None


def merge(target: Module, library: Module, export: str) -> int:
    """Merges library's function export, and what it reaches, into target and commits; returns the
    function's id in target. A function merged before is reused."""
    fn = function_named(library, export)
    if fn is None:
        raise ValueError(f"the library has no function {export}")
    existing = function_named(target, export)
    if existing is not None:
        return existing

    lib = library.instructions
    first_fn, kinds = _sections(library)
    functions: dict[int, tuple[int, int]] = {}
    n = first_fn
    while n < len(lib):
        start = n
        while lib[n].op != OP_FUNCTION_END:
            n += 1
        functions[lib[start].words[2]] = (start, n)
        n += 1
    defs = {}
    for n in range(first_fn):
        r = grammar.result_position(lib[n].words) if kinds[n] in ("global", "import") else None
        if r is not None:
            defs[lib[n].words[r]] = n

    # The functions export reaches, and the globals they and those globals name.
    reached, work = [], [fn]
    while work:
        f = work.pop()
        if f in reached:
            continue
        reached.append(f)
        start, end = functions[f]
        work += [lib[k].words[3] for k in range(start, end) if lib[k].op == _FUNCTION_CALL]
    needed: set[int] = set()
    pending = [w for f in reached for k in range(*functions[f]) for p in grammar.id_positions(lib[k].words)
               if (w := lib[k].words[p]) in defs]
    while pending:
        g = pending.pop()
        if g in needed:
            continue
        needed.add(g)
        ins = lib[defs[g]]
        r = grammar.result_position(ins.words)
        pending += [ins.words[p] for p in grammar.id_positions(ins.words) if p != r and ins.words[p] in defs]
    decorated = {lib[n].words[1] for n in range(first_fn) if kinds[n] == "annotation"}

    t = target.instructions
    t_first, t_kinds = _sections(target)
    bound = target.words[3]
    shared: dict[tuple, int] = {}
    imports: dict[str, int] = {}
    for n in range(t_first):
        ins = t[n]
        if t_kinds[n] == "import":
            imports[_string(ins.words[2:])] = ins.words[1]
        elif t_kinds[n] == "global" and ins.op not in _NEVER_SHARED:
            r = grammar.result_position(ins.words)
            if r is not None:
                key = (ins.op, *(w for p, w in enumerate(ins.words[1:], 1) if p != r))
                shared.setdefault(key, ins.words[r])

    ids: dict[int, int] = {}

    def renumber(words: tuple[int, ...]) -> list[int]:
        out = list(words)
        for p in grammar.id_positions(words):
            if words[p] not in ids:
                ids[words[p]] = target.new_id()
            out[p] = ids[words[p]]
        return out

    new_imports, new_globals, new_variables = [], [], []
    for n in range(first_fn):
        ins = lib[n]
        if kinds[n] == "import":
            if ins.words[1] in needed:
                name = _string(ins.words[2:])
                if name in imports:
                    ids[ins.words[1]] = imports[name]
                else:
                    new_imports.append(renumber(ins.words))
            continue
        if kinds[n] != "global":
            continue
        r = grammar.result_position(ins.words)
        if r is None or ins.words[r] not in needed:
            continue
        result = ins.words[r]
        if ins.op not in _NEVER_SHARED and result not in decorated:
            operands = [ids.get(w, w) if p in grammar.id_positions(ins.words) else w for p, w in enumerate(ins.words)]
            key = (ins.op, *(w for p, w in enumerate(operands[1:], 1) if p != r))
            unresolved = [p for p in grammar.id_positions(ins.words) if p != r and ins.words[p] not in ids]
            if not unresolved and key in shared:
                ids[result] = shared[key]
                continue
        words = renumber(ins.words)
        new_globals.append(words)
        if ins.op == OP_VARIABLE and ins.words[3] != _STORAGE_FUNCTION:
            new_variables.append(words[2])
        key = (words[0] & 0xFFFF, *(w for p, w in enumerate(words[1:], 1) if p != r))
        shared.setdefault(key, words[r])

    new_functions = [renumber(lib[k].words) for f in reached for k in range(functions[f][0], functions[f][1] + 1)]
    # Decorations and names only for what was copied anew: the runtime's own ids are never touched.
    new_annotations, new_names = [], []
    for n in range(first_fn):
        ins = lib[n]
        copied = ins.words[1] in ids and ids[ins.words[1]] >= bound
        if kinds[n] == "annotation" and copied and not (ins.op == 71 and ins.words[2] == _LINKAGE):
            new_annotations.append(renumber(ins.words))
        elif kinds[n] == "name" and copied:
            new_names.append(renumber(ins.words))
    have_caps = {i.words for i in t if i.op == _CAPABILITY}
    new_caps = [list(i.words) for i in lib if i.op == _CAPABILITY and i.words not in have_caps
                and i.words[1] != 5]  # Linkage is never needed: the call goes straight to the function

    def last(kind_names: tuple[str, ...]) -> int | None:
        found = [n for n in range(t_first) if t_kinds[n] in kind_names]
        return found[-1] if found else None

    target.insert_after(last(("capability",)), *new_caps)
    if new_imports:
        target.insert_after(last(("import", "extension", "capability")), *new_imports)
    if new_names:
        target.insert_after(last(("name", "debug", "mode", "entry")), *new_names)
    if new_annotations:
        target.insert_after(last(("annotation", "name", "debug", "mode", "entry")), *new_annotations)
    target.insert_after(t_first - 1, *new_globals)
    target.insert_after(len(t) - 1, *new_functions)
    for n in range(t_first):
        if t[n].op == _ENTRY_POINT and new_variables:
            words = list(t[n].words) + new_variables
            words[0] = (len(words) << 16) | _ENTRY_POINT
            target.replace(n, words)
    target.commit()
    return ids[fn]


def call(target: Module, at: int, function: int, args: list[int], result_type: int) -> tuple[int, list[list[int]]]:
    """Code that calls function with args at instruction index at of target, glslang's way: each argument
    through a Function variable, which is declared in the enclosing function's entry block (queued on
    target). Returns the call's result id and the code, for the caller to place at or after at."""
    ins = target.instructions
    start = next(n for n in range(len(ins)) if ins[n].op == OP_FUNCTION and ins[n].words[2] == function)
    params = []
    n = start + 1
    while ins[n].op == _FUNCTION_PARAMETER:
        params.append(ins[n].words[1])
        n += 1
    if len(params) != len(args):
        raise ValueError(f"function {function} takes {len(params)} arguments, not {len(args)}")
    caller = max(n for n in range(at) if ins[n].op == OP_FUNCTION)
    entry = next(n for n in range(caller, at) if ins[n].op == 248)  # OpLabel
    code, variables = [], []
    for pointer, value in zip(params, args, strict=True):
        variable = target.new_id()
        variables.append(variable)
        target.insert_after(entry, op(OP_VARIABLE, pointer, variable, _STORAGE_FUNCTION))
        code.append(op(_STORE, variable, value))
    result = target.new_id()
    code.append(op(_FUNCTION_CALL, result_type, result, function, *variables))
    return result, code
