"""GitHub Linux runner: verify a mapped, painted, resizable native app window."""
from pathlib import Path
import os
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]


def main():
    if sys.platform != 'linux':
        raise SystemExit('This smoke check requires an X11 Linux runner.')
    executable = Path(sys.argv[-1]).resolve()
    if '--inside' not in sys.argv:
        subprocess.run(['xvfb-run', '-a', sys.executable, __file__, '--inside', str(executable)], check=True)
        return
    evidence = ROOT / 'verification'
    evidence.mkdir(exist_ok=True)
    environment = {**os.environ, 'GDK_BACKEND': 'x11', 'LIBGL_ALWAYS_SOFTWARE': '1'}
    with (evidence / 'launch.log').open('w') as log:
        manager = subprocess.Popen(['openbox'], stdout=log, stderr=log, env=environment)
        app = subprocess.Popen([str(executable)], stdout=log, stderr=log, env=environment)
        try:
            deadline = time.monotonic() + 30
            window = None
            while time.monotonic() < deadline:
                if app.poll() is not None:
                    raise RuntimeError(f'Application exited before a mapped window: {app.returncode}')
                found = subprocess.run(['xdotool', 'search', '--onlyvisible', '--pid', str(app.pid), '--name', '^BitChord$'],
                                       text=True, capture_output=True)
                if found.returncode == 0 and found.stdout.strip():
                    window = found.stdout.splitlines()[0]
                    break
                time.sleep(.2)
            if window is None:
                raise RuntimeError('No mapped BitChord window appeared.')
            for width, height in [(1220, 780), (900, 600)]:
                subprocess.run(['xdotool', 'windowsize', '--sync', window, str(width), str(height)], check=True)
                time.sleep(1)
                capture = evidence / f'native-linux-{width}x{height}.png'
                subprocess.run(['import', '-window', window, str(capture)], check=True)
                colors = subprocess.check_output(['identify', '-format', '%k', str(capture)], text=True)
                if int(colors) < 5:
                    raise RuntimeError('Mapped window did not contain a painted shell.')
            subprocess.run(['xdotool', 'windowactivate', '--sync', window, 'key', 'Alt+F4'], check=True)
            app.wait(timeout=10)
            if app.returncode != 0:
                raise RuntimeError(f'Close failed: {app.returncode}')
            (evidence / 'launch-result.txt').write_text('PASS: visible painted window, two resizes, native close, exit 0.\n')
        finally:
            for process in (app, manager):
                if process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait()


if __name__ == '__main__':
    main()
