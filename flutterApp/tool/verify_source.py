"""Verify the pinned source/assets without inspecting other experiment branches."""
import hashlib
import json
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[2]
manifest = json.loads((root / "flutterApp/tool/source_manifest.json").read_text())
subprocess.run(["git", "merge-base", "--is-ancestor", manifest["source_commit"], "HEAD"],
               cwd=root, check=True)
errors = []
for item in manifest["kotlin_inventory"]:
    if hashlib.sha256((root / item["source"]).read_bytes()).hexdigest() != item["sha256"]:
        errors.append(item["source"])
for item in manifest["assets"]:
    for field in ("source", "target"):
        if hashlib.sha256((root / item[field]).read_bytes()).hexdigest() != item["sha256"]:
            errors.append(item[field])
if errors:
    raise SystemExit("Pinned source/assets changed: " + ", ".join(errors))
print(f"Verified {len(manifest['kotlin_inventory'])} original Kotlin sources and "
      f"{len(manifest['assets'])} unchanged source assets from {manifest['source_commit']}.")
