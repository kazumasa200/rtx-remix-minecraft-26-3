# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""Working-tree line endings follow .gitattributes.

Scripted edits on Windows kept leaving files with the wrong endings: Python's
text mode turns every written "\\n" into "\\r\\n", which put 22 Java files
(``eol=lf``) in CRLF, and line-by-line rewrites left .gitignore mixed. Git
hides it in its own diff, so nothing else noticed. A file whose attribute
names an ending must have it, and no text file may mix the two.
"""

import subprocess
import unittest

from rtx_tools.benchmark import util

REPO_ROOT = util.PROJECT_DIR

FIX = (
    "rewrite it with the ending its attribute names: read and write bytes, or open() with newline='' "
    "and keep the file's own '\\r\\n' or '\\n'"
)


def _eol_report() -> list[tuple[str, str, str]]:
    """(working-tree eol, attribute, path) per tracked file, from ``git ls-files --eol``."""
    out = subprocess.run(
        ["git", "ls-files", "--eol", "-z"], cwd=REPO_ROOT, capture_output=True, check=True
    ).stdout.decode("utf-8", "replace")
    rows = []
    for entry in filter(None, out.split("\0")):
        info, _, path = entry.partition("\t")
        fields = info.split()
        rows.append((fields[1], " ".join(fields[2:]), path))
    return rows


def violations(rows: list[tuple[str, str, str]]) -> list[str]:
    bad = []
    for work, attr, path in rows:
        if work == "w/mixed":
            bad.append(f"{path}: mixed line endings")
        elif "eol=lf" in attr and work == "w/crlf":
            bad.append(f"{path}: CRLF, .gitattributes says eol=lf")
        elif "eol=crlf" in attr and work == "w/lf":
            bad.append(f"{path}: LF, .gitattributes says eol=crlf")
    return bad


class LineEndingTests(unittest.TestCase):
    def test_working_tree_follows_gitattributes(self):
        bad = violations(_eol_report())
        self.assertEqual([], bad, f"line endings disagree with .gitattributes; {FIX}")

    def test_the_check_catches_the_past_mistakes(self):
        rows = [
            ("w/crlf", "attr/text eol=lf", "Some.java"),
            ("w/mixed", "attr/", ".gitignore"),
            ("w/lf", "attr/text eol=crlf", "run.ps1"),
            ("w/crlf", "attr/", "README.md"),
            ("w/lf", "attr/", "notes.txt"),
        ]
        self.assertEqual(3, len(violations(rows)))


if __name__ == "__main__":
    unittest.main()
