"""A live GUI launch check, separate from hardware/audio validation."""
from pathlib import Path
import subprocess
import time

launcher = next(Path('desktopApp/build/compose/binaries/main/app').glob('*.app/Contents/MacOS/BitChord'))
with open('macos-startup.log', 'w') as log:
    process = subprocess.Popen([str(launcher)], stdout=log, stderr=subprocess.STDOUT)
    try:
        time.sleep(25)
        assert process.poll() is None, 'App exited during startup; inspect macos-startup.log'
        # Native System Events enumerates visible windows, rather than treating a live JVM as GUI success.
        probe = subprocess.run(['osascript', '-e',
            'tell application "System Events" to get count of windows of process "BitChord"'],
            capture_output=True, text=True, timeout=15)
        assert probe.returncode == 0, 'GUI verification BLOCKED: ' + probe.stderr
        assert int(probe.stdout.strip()) > 0, 'No macOS application window appeared'
    finally:
        process.terminate()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
text = Path('macos-startup.log').read_text(errors='replace')
for failure in ('UnsatisfiedLinkError', 'NoClassDefFoundError', 'Exception in thread', 'SIGSEGV', 'SIGABRT'):
    assert failure not in text, text
print('PASS: live startup and macOS window; playback and hardware are NOT TESTED')
