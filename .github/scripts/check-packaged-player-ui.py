"""Reject stale/duplicate player classes in the actual jpackage runtime classpath."""
from pathlib import Path
import sys
from zipfile import ZipFile

root = Path(__file__).resolve().parents[2]
classes = root / "sharedUi/build/classes/kotlin/jvm/main"
app = Path(sys.argv[1])
libraries = app / "lib/app" if (app / "lib/app").is_dir() else app / "app"
families = ("LandscapePlayerColumnsKt", "LandscapeLyricsFrameKt", "LandscapeLyricEdgesKt", "LandscapePlayerKt", "NowPlayingScreenKt")
expected = {
    path.relative_to(classes).as_posix(): path.read_bytes()
    for path in classes.rglob("*.class")
    if path.stem.split("$", 1)[0] in families
}
if not expected or not libraries.is_dir():
    sys.exit("::error::Missing compiled player classes or packaged application libraries")

packaged = {name: [] for name in expected}
for jar in libraries.glob("*.jar"):
    with ZipFile(jar) as archive:
        for name in expected.keys() & set(archive.namelist()):
            packaged[name].append((jar.name, archive.read(name)))

errors = []
for name, content in expected.items():
    copies = packaged[name]
    if len(copies) != 1:
        errors.append(f"{name}: expected exactly one packaged copy, found {len(copies)}")
    elif copies[0][1] != content:
        errors.append(f"{name}: stale bytecode in {copies[0][0]}")
for error in errors:
    print(f"::error::{error}")
if errors:
    sys.exit(1)
print(f"Packaged player matches all {len(expected)} freshly compiled classes; no duplicate definitions.")
