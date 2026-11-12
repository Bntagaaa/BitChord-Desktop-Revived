"""Apply the reviewed native integration to officially generated hosts only."""
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
for platform in ['windows', 'linux']:
    path = ROOT / platform / 'CMakeLists.txt'
    text = path.read_text()
    marker = 'include("${CMAKE_CURRENT_SOURCE_DIR}/../native/audio/host.cmake")'
    if marker not in text:
        key = 'set(INSTALL_BUNDLE_LIB_DIR '
        at = text.index('\n', text.index(key)) + 1
        text = text[:at] + '\n' + marker + '\n' + text[at:]
    path.write_text(text)
