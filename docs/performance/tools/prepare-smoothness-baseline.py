#!/usr/bin/env python3
"""Create an instrumentation-only, detached Phase 1 comparison checkout."""
import argparse
from pathlib import Path
import shutil
import subprocess

BASE = "4cf9755104c65ea5615f28ed6ec7d5fe53f5f4a4"
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("destination", type=Path, help="new, nonexistent worktree directory")
args = parser.parse_args()
source = Path(__file__).resolve().parents[3]
destination = args.destination.resolve()
subprocess.run(["git", "worktree", "add", "--detach", str(destination), BASE], cwd=source, check=True)
package = Path("desktopApp/src/main/kotlin/com/music/bitchord/desktop")
for name in ("DesktopSmoothnessTrace.kt", "DesktopSmoothnessModifier.kt"):
    shutil.copy2(source / package / name, destination / package / name)
test = Path("desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopShellSceneBench.kt")
shutil.copy2(source / test, destination / test)

# Only the opt-in wrapper/marks are patched into the frozen Phase 1 application.
main = destination / package / "Main.kt"
text = main.read_text()
for before, after in (
    ("fun main() {", "fun main() {\n    DesktopSmoothnessTrace.install()"),
    ("DesktopRenderVisibility.visible.value = visible && !state.isMinimized",
     "DesktopRenderVisibility.visible.value = visible && !state.isMinimized\n        DesktopSmoothnessTrace.visibility(visible && !state.isMinimized)"),
    ("        val composeWindow = window", "        DesktopSmoothnessFrameProbe()\n        val composeWindow = window"),
    ("                BitChordDesktopApp(welcomeLaunch)",
     "                if (DesktopSmoothnessTrace.enabled) {\n"
     "                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.smoothnessTrace()) {\n"
     "                        BitChordDesktopApp(welcomeLaunch)\n"
     "                    }\n"
     "                } else BitChordDesktopApp(welcomeLaunch)"),
):
    assert text.count(before) == 1, before
    text = text.replace(before, after)
main.write_text(text)
app = destination / package / "DesktopApp.kt"
text = app.read_text()
for signature in (
    "fun openCollection(browseId: String, fallback: BrowseItem? = null) {",
    "fun openArtist(browseId: String, name: String) {",
    "fun selectDestination(next: DesktopDestination) {",
):
    assert text.count(signature) == 1, signature
    text = text.replace(signature, signature + "\n        DesktopSmoothnessTrace.navigation()")
app.write_text(text)
build = destination / "desktopApp/build.gradle.kts"
task = (source / "desktopApp/build.gradle.kts").read_text().split('tasks.register<JavaExec>("shellSceneBench")', 1)[1]
build.write_text(build.read_text() + '\n// Instrumentation-only comparison task.\ntasks.register<JavaExec>("shellSceneBench")' + task)
print(f"Detached baseline ready: {destination}; base reference unchanged.")
