#!/usr/bin/env python3
"""Reuse an already-built release DEB when packaging BitChord for Arch.

This is for GitHub Actions: source packaging and release preparation use one
Debian artifact, without rerunning Gradle or fetching a second copy of BitChord.
It does not publish to the AUR; the checked-in AUR recipe remains source-based.
"""
from __future__ import annotations

import argparse
import hashlib
from pathlib import Path
import re
import shutil

from update import SHA_LINE, VERSION_LINE, PKGBUILD, change_one


def prepare(version: str, deb_file: Path) -> tuple[Path, str]:
    if not re.fullmatch(r"[0-9]+\.[0-9]+(\.[0-9]+)?", version):
        raise ValueError("Version must be MAJOR.MINOR or MAJOR.MINOR.PATCH")
    expected_filename = f"BitChord-{version}-linux-amd64.deb"
    if deb_file.name != expected_filename or not deb_file.is_file():
        raise ValueError(f"Expected built Debian artifact named {expected_filename}")

    digest = hashlib.sha256()
    with deb_file.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    checksum = digest.hexdigest()

    content = PKGBUILD.read_text(encoding="utf-8")
    content = change_one(content, VERSION_LINE, f"pkgver={version}")
    content = change_one(content, r"(?m)^pkgrel=\d+$", "pkgrel=1")
    content = change_one(content, SHA_LINE, f"sha256sums=('{checksum}')")

    # Preserve AUR's remote source URL format. makepkg uses this local copy
    # under its declared source filename, verifies it, and never downloads twice.
    target = PKGBUILD.parent / f"bitchord-desktop-revived-bin-{version}.deb"
    if deb_file.resolve() != target.resolve():
        shutil.copyfile(deb_file, target)
    PKGBUILD.write_text(content, encoding="utf-8")
    return target, checksum


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--version", required=True)
    parser.add_argument("--deb", type=Path, required=True)
    args = parser.parse_args()
    target, checksum = prepare(args.version, args.deb)
    print(f"Using existing DEB at {target} (SHA-256: {checksum})")


if __name__ == "__main__":
    main()
