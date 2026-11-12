"""GitHub Linux runner: verify a mapped, painted, resizable native app window."""
from pathlib import Path
import os
import json
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]


def shell_painted(capture, width, height):
    """Check responsive shell landmarks, not just a nonblank stale framebuffer."""
    sidebar = 72 if width < 980 else 220
    boundary = subprocess.check_output([
        'convert', str(capture), '-format',
        f'%[pixel:p{{{sidebar - 1},500}}]|%[pixel:p{{{sidebar},500}}]', 'info:'
    ], text=True, timeout=10).split('|')
    center = (width + sidebar) // 2
    logo_contrast = float(subprocess.check_output([
        # import records the window's screen origin as PNG page metadata;
        # reset it BEFORE cropping in client-local coordinates.
        'convert', str(capture), '+repage', '-crop', f'48x48+{center - 24}+{height - 73}',
        '-format', '%[fx:maxima-minima]', 'info:'
    ], text=True, timeout=10))
    return boundary[0] != boundary[1] and logo_contrast >= .25


def main():
    if sys.platform != 'linux':
        raise SystemExit('This smoke check requires an X11 Linux runner.')
    executable = Path(sys.argv[-1]).resolve()
    if '--inside' not in sys.argv:
        subprocess.run(['dbus-run-session', '--', 'xvfb-run', '-a', '-s', '-screen 0 1600x1000x24',
                        sys.executable, __file__, '--inside', str(executable)], check=True)
        return
    evidence = ROOT / 'verification'
    evidence.mkdir(exist_ok=True)
    profile = tempfile.TemporaryDirectory(prefix='bitchord-smoke-')
    environment = {**os.environ, 'GDK_BACKEND': 'x11', 'LIBGL_ALWAYS_SOFTWARE': '1',
                   'XDG_CONFIG_HOME': profile.name, 'XDG_DATA_HOME': profile.name,
                   'XDG_CACHE_HOME': profile.name}
    # run_checked.py owns launch.log; never truncate its diagnostics here.
    with (evidence / 'native-linux.log').open('w') as log:
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
            subprocess.run(['xdotool', 'windowmove', '--sync', window, '40', '60'], check=True, timeout=10)
            observations = []
            for width, height in [(1220, 780), (900, 600)]:
                subprocess.run(['xdotool', 'windowsize', '--sync', window, str(width), str(height)], check=True, timeout=10)
                # X11 geometry changes precede Flutter's metrics/frame update.
                time.sleep(2)
                capture = evidence / f'native-linux-{width}x{height}.png'
                deadline = time.monotonic() + 20
                while True:
                    if app.poll() is not None:
                        raise RuntimeError(f'Application exited during paint: {app.returncode}')
                    # Read the visible screen including GL child surfaces, clipped
                    # to the client window. XGetImage of the parent can be black.
                    subprocess.run(['import', '-screen', '-window', window, str(capture)], check=True, timeout=10)
                    details = subprocess.check_output(['identify', '-format', '%w %h %k', str(capture)], text=True, timeout=10)
                    actual_width, actual_height, colors = map(int, details.split())
                    if ((actual_width, actual_height) == (width, height) and colors >= 50
                            and shell_painted(capture, width, height)):
                        break
                    if time.monotonic() >= deadline:
                        raise RuntimeError(f'Client did not paint at {width}x{height}: {details}')
                    time.sleep(.25)
                geometry = subprocess.check_output(['xdotool', 'getwindowgeometry', '--shell', window], text=True, timeout=10)
                observations.append({'width': actual_width, 'height': actual_height,
                                     'colors': colors, 'geometry': geometry})
            subprocess.run(['xdotool', 'windowactivate', '--sync', window, 'key', 'Alt+F4'], check=True, timeout=10)
            app.wait(timeout=10)
            if app.returncode != 0:
                raise RuntimeError(f'Close failed: {app.returncode}')
            (evidence / 'launch-result.txt').write_text('PASS: visible painted window, two resizes, native close, exit 0.\n')
            (evidence / 'native-linux.json').write_text(json.dumps({
                'display': 'Xvfb 1600x1000x24 / Openbox / software GL',
                'captures': observations, 'exit_code': app.returncode,
            }, indent=2) + '\n')
        finally:
            for process in (app, manager):
                if process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait()
            profile.cleanup()


if __name__ == '__main__':
    main()
