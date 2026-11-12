"""Run real controlled HTTP/native broker integration on each desktop OS."""
import json
import os
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
dart = Path(shutil.which('dart'))
if os.name == 'nt':
    dart = dart.with_suffix('.exe')
stream = ROOT / 'build/native' / ('Release/bitchord_stream_test.exe' if os.name == 'nt' else 'bitchord_stream_test')
library = ROOT / 'build/native' / ('Release/bitchord_audio.dll' if os.name == 'nt' else 'libbitchord_audio.so')
trial = subprocess.run([str(dart), 'run', 'tool/phase4_check.dart', str(stream), str(library)],
                       cwd=ROOT, capture_output=True, text=True, timeout=90)
if trial.returncode:
    print(trial.stdout)
    print(trial.stderr)
    raise SystemExit(trial.returncode)
report = json.loads(trial.stdout.strip().splitlines()[-1])
(ROOT / 'verification/phase4-http.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report))
