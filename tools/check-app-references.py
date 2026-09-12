#!/usr/bin/env python3
"""Static reference check for the Android module.

The :app module cannot be compiled in every environment - it needs an Android
SDK, and some sandboxes cannot reach Google's servers to install one. That gap
has twice let a broken :app reach CI: an edit to the view model removed methods
the screens still called, :core's tests passed, and the failure only surfaced
in the Android build minutes later.

This catches that specific class of break without a compiler. It is not a type
checker and does not pretend to be; it answers two questions that account for
the breakages actually seen:

  1. Does every `viewModel.x` / `viewModel::x` the UI calls exist on the view
     model?
  2. Does every `com.anthonyrohde.truckscan.core.*` import resolve to something
     declared in :core?

Exit code 1 on any unresolved reference, so it can gate a push.
"""
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
APP = ROOT / "app/src/main/kotlin/com/anthonyrohde/truckscan"
CORE = ROOT / "core/src/main/kotlin"
VIEW_MODEL = APP / "ScanViewModel.kt"

DECLARATION = re.compile(
    r"^\s*(?:@\w+\s+)?(?:public |internal |private |protected )?"
    r"(?:override |open |abstract |suspend |inline |lateinit )*"
    r"(?:fun|val|var)\s+(?:<[^>]+>\s+)?([A-Za-z_]\w*)",
    re.M,
)
TYPE_DECLARATION = re.compile(
    r"^\s*(?:@\w+\s+)?(?:public |internal |private )?"
    r"(?:abstract |open |sealed |data |value |enum |annotation )*"
    r"(class|interface|object)\s+([A-Za-z_]\w*)",
    re.M,
)


def view_model_members() -> set[str]:
    source = VIEW_MODEL.read_text()
    members = set(DECLARATION.findall(source))
    # Backing StateFlows are exposed under the name without the underscore, and
    # the public alias is itself a declaration, so nothing extra is needed here.
    return members


def core_declarations() -> dict[str, set[str]]:
    declared: dict[str, set[str]] = {}
    for path in CORE.rglob("*.kt"):
        source = path.read_text()
        package = re.search(r"^package\s+([\w.]+)", source, re.M)
        package = package.group(1) if package else ""
        names = declared.setdefault(package, set())
        names.update(name for _, name in TYPE_DECLARATION.findall(source))
        names.update(DECLARATION.findall(source))
    return declared


def main() -> int:
    problems: list[str] = []

    members = view_model_members()
    used = re.compile(r"viewModel(?:\.|::)([A-Za-z_]\w*)")
    for path in APP.rglob("*.kt"):
        if path == VIEW_MODEL:
            continue
        for line_number, line in enumerate(path.read_text().splitlines(), 1):
            for name in used.findall(line):
                if name not in members:
                    problems.append(
                        f"{path.relative_to(ROOT)}:{line_number}: "
                        f"ScanViewModel has no '{name}'"
                    )

    declared = core_declarations()
    importing = re.compile(r"^import\s+(com\.anthonyrohde\.truckscan\.core\.[\w.]+)")
    for path in APP.rglob("*.kt"):
        for line_number, line in enumerate(path.read_text().splitlines(), 1):
            match = importing.match(line.strip())
            if not match:
                continue
            parts = match.group(1).split(".")
            symbol, package = parts[-1], ".".join(parts[:-1])
            if symbol in declared.get(package, set()):
                continue
            # Nested symbol: Package.Type.Nested
            parent_package, parent = ".".join(parts[:-2]), parts[-2]
            if symbol in declared.get(parent_package, set()):
                continue
            if parent in declared.get(parent_package, set()):
                continue
            problems.append(
                f"{path.relative_to(ROOT)}:{line_number}: "
                f"unresolved core import '{match.group(1)}'"
            )

    if problems:
        print(f"{len(problems)} unresolved reference(s) in :app\n")
        for problem in problems:
            print("  " + problem)
        return 1

    print(f"OK - {len(members)} view model members, all :app references resolve")
    return 0


if __name__ == "__main__":
    sys.exit(main())
