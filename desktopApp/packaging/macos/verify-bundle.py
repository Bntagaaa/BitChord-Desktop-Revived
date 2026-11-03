"""Validate the actual jpackage bundle using its bundled Java, without system JRE fallback."""
import os
import re
from pathlib import Path
import plistlib
import subprocess
import tempfile
import zipfile

arch = 'arm64' if os.environ['BUILD_ARCH'] == 'arm64' else 'x86_64'
apps = list(Path('desktopApp/build/compose/binaries/main/app').glob('*.app'))
assert len(apps) == 1, apps
app = apps[0]
contents = app / 'Contents'
with (contents / 'Info.plist').open('rb') as handle:
    plist = plistlib.load(handle)
assert plist['CFBundleIdentifier'] == 'com.music.bitchord', plist
assert plist['CFBundleName'] == 'BitChord', plist
assert plist['CFBundleVersion'] and plist['CFBundleShortVersionString']
def version(value):
    return tuple((list(map(int, value.split('.'))) + [0, 0, 0])[:3])
minimum = version(plist.get('LSMinimumSystemVersion', '0'))
assert minimum >= version('14.0' if arch == 'arm64' else '13.3'), plist
assert (contents / 'Resources' / plist['CFBundleIconFile']).is_file()
launcher = contents / 'MacOS' / plist['CFBundleExecutable']
jvm = contents / 'runtime/Contents/Home/lib/server/libjvm.dylib'
assert launcher.is_file() and os.access(launcher, os.X_OK)
assert jvm.is_file(), 'Missing bundled Java runtime'

def inspect(path):
    subprocess.run(['file', str(path)], check=True)
    subprocess.run(['lipo', str(path), '-verify_arch', arch], check=True)
    result = subprocess.run(['otool', '-L', str(path)], check=True, capture_output=True, text=True).stdout
    assert '/opt/homebrew/' not in result and '/usr/local/' not in result, result
    headers = subprocess.run(['otool', '-l', str(path)], check=True, capture_output=True, text=True).stdout
    match = re.search(r'cmd LC_BUILD_VERSION.*?\bminos ([0-9.]+)', headers, re.S)
    if match is None:
        match = re.search(r'cmd LC_VERSION_MIN_MACOSX.*?\bversion ([0-9.]+)', headers, re.S)
    if match:
        assert version(match[1]) <= minimum, f'{path} requires macOS {match[1]}, beyond bundle minimum {minimum}'

inspect(launcher)
inspect(jvm)
for library in (contents / 'runtime').rglob('*.dylib'):
    inspect(library)
jars = list((contents / 'app').glob('*.jar'))
assert jars, 'Missing app jars'
classifier = 'macosx-arm64' if arch == 'arm64' else 'macosx-x86_64'
ort_arch = 'aarch64' if arch == 'arm64' else 'x64'
required = {
    'analyzer': lambda s: s == 'native/libbitchord_analysis.dylib',
    'media': lambda s: s == 'native/libbitchord_macos.dylib',
    **{f'ffmpeg-{module}': (lambda s, module=module: classifier in s and f'jni{module}' in s and s.endswith('.dylib'))
       for module in ('avcodec', 'avformat', 'avutil', 'swresample')},
    'javafx-web': lambda s: s.endswith('libjfxwebkit.dylib'),
    'javafx-media': lambda s: s.endswith('libjfxmedia.dylib'),
    'onnx': lambda s: f'osx-{ort_arch}/' in s and s.endswith('libonnxruntime.dylib'),
    'onnx-jni': lambda s: f'osx-{ort_arch}/' in s and s.endswith('libonnxruntime4j_jni.dylib'),
    'skiko': lambda s: 'skiko' in s and s.endswith('.dylib'),
    'model': lambda s: s.startswith('models/') and s.endswith('.onnx'),
}
found = set()
with tempfile.TemporaryDirectory() as temp:
    for jar in jars:
        with zipfile.ZipFile(jar) as archive:
            for name in archive.namelist():
                if ".dSYM/" in name: continue
                for category, matches in required.items():
                    if matches(name):
                        found.add(category)
                        if name.endswith('.dylib'):
                            destination = Path(temp) / Path(name).name
                            destination.write_bytes(archive.read(name))
                            inspect(destination)
assert found == set(required), f'Missing native resources: {set(required) - found}'
# Loads actual FFmpeg, ONNX JNI and the shared analyzer with the packaged runtime.
subprocess.run([str(launcher)], env={**os.environ, 'BITCHORD_NATIVE_SMOKE': '1'}, check=True, timeout=60)
print(f'PASS: {arch} bundle metadata, runtime, native resources and JNI loading')
