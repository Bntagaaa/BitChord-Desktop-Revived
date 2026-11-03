"""Verify canonical AutoMix source and model identity without requiring network access."""
import hashlib
import json
from pathlib import Path

root = Path(__file__).resolve().parents[2]
manifest = json.loads(Path(__file__).with_name("upstream-manifest.json").read_text())
failures = []
for name, expected in manifest["files"].items():
    path = root / name
    if not path.is_file():
        failures.append(f"missing: {name}")
        continue
    content = path.read_bytes()
    if path.suffix in (".kt", ".cpp", ".h"):
        content = content.replace(b"\r\n", b"\n")
    if hashlib.sha256(content).hexdigest() != expected:
        failures.append(f"different from upstream: {name}")
if failures:
    raise SystemExit("\n".join(failures))
print(f"Verified {len(manifest['files'])} canonical AutoMix files against {manifest['commit']}")
