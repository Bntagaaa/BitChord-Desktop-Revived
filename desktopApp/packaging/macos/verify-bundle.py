"""Validate the actual jpackage bundle using its bundled Java, without system JRE fallback."""
import os
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
assert (contents / 'Resources' / plist['CFBundleIconFile']).is_file()
launcher = contents / 'MacOS' / plist['CFBundleExecutable']
java = contents / 'runtime/Contents/Home/bin/java'
assert launcher.is_file() and os.access(launcher, os.X_OK)
assert java.is_file(), 'Missing bundled Java runtime'

def inspect(path):
    subprocess.run(['file', str(path)], check=True)
    subprocess.run(['lipo', '-verify_arch', arch, str(path)], check=True)
    result = subprocess.run(['otool', '-L', str(path)], check=True, capture_output=True, text=True).stdout
    assert '/opt/homebrew/' not in result and '/usr/local/' not in result, result

inspect(launcher)
inspect(java)
for library in (contents / 'runtime').rglob('*.dylib'):
    inspect(library)
jars = list((contents / 'app').glob('*.jar'))
assert jars, 'Missing app jars'
classifier = 'macosx-arm64' if arch == 'arm64' else 'macosx-x86_64'
ort_arch = 'aarch64' if arch == 'arm64' else 'x64'
required = {
    'analyzer': lambda s: s == 'native/libbitchord_analysis.dylib',
    'media': lambda s: s == 'native/libbitchord_macos.dylib',
    'ffmpeg': lambda s: classifier in s and 'jniavcodec' in s and s.endswith('.dylib'),
    'onnx': lambda s: f'osx-{ort_arch}/' in s and s.endswith('libonnxruntime.dylib'),
    'skiko': lambda s: 'skiko' in s and s.endswith('.dylib'),
    'model': lambda s: s.startswith('models/') and s.endswith('.onnx'),
}
found = set()
with tempfile.TemporaryDirectory() as temp:
    for jar in jars:
        with zipfile.ZipFile(jar) as archive:
            for name in archive.namelist():
                for category, matches in required.items():
                    if matches(name):
                        found.add(category)
                        if name.endswith('.dylib'):
                            destination = Path(temp) / Path(name).name
                            destination.write_bytes(archive.read(name))
                            inspect(destination)
assert found == set(required), f'Missing native resources: {set(required) - found}'
# Loads actual FFmpeg, ONNX JNI and the shared analyzer with the packaged runtime.
subprocess.run([str(java), '-cp', str(contents / 'app' / '*'),
                'com.music.bitchord.desktop.DesktopNativeSmoke'], check=True, timeout=60)
print(f'PASS: {arch} bundle metadata, runtime, native resources and JNI loading')
