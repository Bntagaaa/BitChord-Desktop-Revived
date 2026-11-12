"""Assemble a reproducible, offline-capable YouTube resolver for portable builds.

Only for the GitHub Actions Windows/Linux release bundles. Third-party code is
pinned and compiled in CI, never downloaded at application runtime.
"""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import sys
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]
YT_VERSION = "2026.10.26"
POT_VERSION = "2.0.2"
NODE_VERSION = "v22.23.5"
YTDLP_SHA256 = {
    "linux": "767ba9dce7052aa196f8b12652f433d76de128ba168966fa84b6394d5e6c6795",
    "windows": "f5389d3698ecde4ef06f4e6b1ce0294fe9ac1396228c8990b8d3416d6d177a7d",
}
PLUGIN_SHA256 = "a26b980bf6d8e8400d8f21445daa4d8381bf6183d20f0be309391f86b31e27ef"


def digest(path: Path) -> str:
    hasher = hashlib.sha256()
    with path.open("rb") as source:
        while part := source.read(1024 * 1024):
            hasher.update(part)
    return hasher.hexdigest()


def fetch_pinned(url: str, dest: Path, expected: str, max_bytes: int) -> None:
    request = Request(url, headers={"User-Agent": "BitChord-CI-Pinned-Artifact"})
    temp = dest.with_name(dest.name + ".partial")
    count = 0
    try:
        with urlopen(request, timeout=90) as response, temp.open("wb") as output:
            if response.geturl().split(":")[0] != "https":
                raise ValueError("Refusing non-HTTPS artifact redirect")
            while data := response.read(1024 * 1024):
                count += len(data)
                if count > max_bytes:
                    raise ValueError("Pinned artifact exceeded size cap")
                output.write(data)
        if digest(temp) != expected:
            raise ValueError("Pinned upstream SHA-256 mismatch: " + dest.name)
        temp.replace(dest)
    finally:
        temp.unlink(missing_ok=True)


def bundle_dir(target: str) -> Path:
    if target == "linux":
        return ROOT / "build/linux/x64/release/bundle"
    if target == "windows":
        return ROOT / "build/windows/x64/runner/Release"
    raise ValueError("Expected linux or windows release target")


def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit("Usage: package_youtube_runtime.py linux|windows <pinned-provider-checkout>")
    target = sys.argv[1]
    checkout = Path(sys.argv[2]).resolve()
    server = checkout / "server"
    if not (checkout / "LICENSE").is_file() or not (server / "package-lock.json").is_file():
        raise SystemExit("Pinned PO Token provider source checkout is missing")
    for required in ("build/generate_once.js", "node_modules/bgutils-js/package.json",
                     "node_modules/canvas/package.json", "package.json"):
        if not (server / required).is_file():
            raise SystemExit("Compiled PO Token provider is incomplete: " + required)
    if json.loads((server / "package.json").read_text())["version"] != POT_VERSION:
        raise SystemExit("Unexpected PO Token provider version")
    node_path = Path(shutil.which("node") or "").resolve()
    if not node_path.is_file():
        raise SystemExit("Pinned Node.js is missing")
    if subprocess.check_output([str(node_path), "--version"], text=True).strip() != NODE_VERSION:
        raise SystemExit("Node.js version does not match reviewed runtime")
    destination = bundle_dir(target)
    if not destination.is_dir():
        raise SystemExit("Build the release bundle before packaging the resolver")
    out = destination / "youtube_runtime"
    if out.exists():
        raise SystemExit("Refusing to overwrite an existing YouTube runtime")
    out.mkdir()
    binary_name = "yt-dlp.exe" if target == "windows" else "yt-dlp"
    upstream_name = "yt-dlp.exe" if target == "windows" else "yt-dlp_linux"
    fetch_pinned(
        f"https://github.com/yt-dlp/yt-dlp/releases/download/{YT_VERSION}/{upstream_name}",
        out / binary_name, YTDLP_SHA256[target], 100 * 1024 * 1024,
    )
    if target == "linux":
        (out / binary_name).chmod(0o755)
    shutil.copyfile(node_path, out / ("node.exe" if target == "windows" else "node"))
    if target == "linux":
        (out / "node").chmod(0o755)
    plugins = out / "plugins"
    plugins.mkdir()
    fetch_pinned(
        f"https://github.com/Brainicism/bgutil-ytdlp-pot-provider/releases/download/{POT_VERSION}/bgutil-ytdlp-pot-provider.zip",
        plugins / "bgutil-ytdlp-pot-provider.zip",
        PLUGIN_SHA256, 2 * 1024 * 1024,
    )
    dest_server = out / "server"
    dest_server.mkdir()
    for name in ("build", "node_modules"):
        shutil.copytree(server / name, dest_server / name, symlinks=False)
    for name in ("package.json", "package-lock.json"):
        shutil.copy2(server / name, dest_server / name)
    license_dir = out / "licenses"
    license_dir.mkdir()
    shutil.copy2(checkout / "LICENSE", license_dir / "bgutil-GPL-3.0.txt")
    shutil.copy2(checkout / "README.md", license_dir / "bgutil-source-and-build-notice.md")
    for location in [node_path.parent, node_path.parent.parent]:
        license_file = location / "LICENSE"
        if license_file.is_file():
            shutil.copy2(license_file, license_dir / "node-LICENSE")
            break
    else:
        raise SystemExit("Node.js redistribution LICENSE not found")
    manifest = {
        "yt_dlp": {"version": YT_VERSION, "sha256": digest(out / binary_name)},
        "node": {"version": NODE_VERSION, "sha256": digest(out / ("node.exe" if target == "windows" else "node"))},
        "po_token_provider": {
            "version": POT_VERSION,
            "source_commit": "26475e9d3665b972c4aeb83b4dbaafcf64b88438",
            "plugin_zip_sha256": digest(plugins / "bgutil-ytdlp-pot-provider.zip"),
            "license": "GPL-3.0-only",
        },
        "distribution": "bundled-at-build, no user setup or runtime downloads",
        "physical_youtube_playback": "not_verified",
    }
    (out / "runtime.json").write_text(json.dumps(manifest, indent=2) + "\n")
    # Validate the actual compiled generator, not only that its files exist.
    if subprocess.run(
        [str(out / ("node.exe" if target == "windows" else "node")),
         str(dest_server / "build" / "generate_once.js"), "--version"],
        cwd=dest_server, timeout=30, capture_output=True,
    ).returncode != 0:
        raise SystemExit("Bundled generator failed its startup check")
    if subprocess.run([str(out / binary_name), "--version"],
                      timeout=30, capture_output=True).returncode != 0:
        raise SystemExit("Bundled yt-dlp failed its startup check")
    print(f"Bundled verified YouTube resolver for {target}: Node {NODE_VERSION}, yt-dlp {YT_VERSION}, PO provider {POT_VERSION}")


if __name__ == "__main__":
    main()
