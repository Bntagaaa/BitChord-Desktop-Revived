#!/usr/bin/env python3
"""Enforce the centralized non-Material icon system across desktop and shared UI."""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCAN_DIRS = (
    ROOT / "desktopApp/src/main",
    ROOT / "desktopUi/src",
    ROOT / "sharedUi/src/jvmSharedMain",
    ROOT / "sharedUi/src/commonMain",
    ROOT / "shared/src",
)

MATERIAL_USAGE_RE = re.compile(
    r"\bIcons\.(?:AutoMirrored\.)?(?:Rounded|Filled|Outlined|Sharp|TwoTone|Default)\.[A-Za-z0-9_]+"
)
MATERIAL_IMPORT_RE = re.compile(r"^\s*import\s+androidx\.compose\.material\.icons\.")

# Explicit allowlist of (relative_path, symbol_or_import) for documented Tier 5 fallbacks.
# Currently empty: 0 Material Icon fallbacks are required.
APPROVED_FALLBACKS: set[tuple[str, str]] = set()


def main() -> int:
    violations: list[str] = []
    for scan_dir in SCAN_DIRS:
        if not scan_dir.is_dir():
            continue
        for path in sorted(scan_dir.rglob("*.kt")):
            rel = path.relative_to(ROOT).as_posix()
            for lineno, line in enumerate(path.read_text(encoding="utf-8", errors="ignore").splitlines(), 1):
                if MATERIAL_IMPORT_RE.match(line):
                    sym = line.strip()
                    if (rel, sym) not in APPROVED_FALLBACKS:
                        violations.append(f"{rel}:{lineno}: unapproved Material icon import: {sym}")
                for match in MATERIAL_USAGE_RE.finditer(line):
                    sym = match.group(0)
                    if (rel, sym) not in APPROVED_FALLBACKS:
                        violations.append(f"{rel}:{lineno}: unapproved Material icon usage: {sym}")

    if violations:
        for v in violations:
            print(f"::error::{v}", file=sys.stderr)
        print(f"Found {len(violations)} unapproved Material icon reference(s).", file=sys.stderr)
        return 1

    print("Icon system check passed: 0 unapproved Material icon imports or usages.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
