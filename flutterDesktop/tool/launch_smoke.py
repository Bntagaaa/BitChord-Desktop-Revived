"""GitHub Linux runner: verify a mapped, painted, resizable native app window."""
from pathlib import Path
import os
import json
import re
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


def capture_state(evidence, window, name, theme='any'):
    capture = evidence / f'native-linux-{name}-900x600.png'
    deadline = time.monotonic() + 8
    while True:
        subprocess.run(['import', '-screen', '-window', window, '-depth', '8', str(capture)], check=True, timeout=10)
        # Empty right margin below the catalog viewport and above the dock.
        # y=400 now contains live artwork with catalog-dependent scrim colors.
        value = subprocess.check_output(['convert', str(capture), '-format', '%[pixel:p{880,500}]', 'info:'], text=True, timeout=10)
        match = re.search(r'\((\d+),(\d+),(\d+)', value)
        if not match:
            raise RuntimeError('Unexpected native pixel format: ' + value)
        rgb = list(map(int, match.groups()))
        if (theme == 'any' or (theme == 'drawer' and rgb == [183, 184, 186]) or (theme == 'light' and min(rgb) >= 230) or
                (theme == 'dark' and rgb == [32, 32, 32]) or (theme == 'black' and max(rgb) <= 2)):
            return {'state': name, 'expected_theme': theme, 'background_rgb': rgb}
        if time.monotonic() > deadline:
            raise RuntimeError(f'Real appearance selection did not render {theme}: {rgb}')
        time.sleep(.25)


def click_client(window, x, y):
    subprocess.run(['xdotool', 'mousemove', '--sync', '--window', window, str(x), str(y)], check=True, timeout=5)
    time.sleep(.2)
    subprocess.run(['xdotool', 'click', '1'], check=True, timeout=5)
    time.sleep(.8)
    # Keep hover tooltips out of comparison images after opening an overlay.
    subprocess.run(['xdotool', 'mousemove', '--window', window, '500', '400'], check=True, timeout=5)
    time.sleep(.25)


def main():
    if sys.platform != 'linux':
        raise SystemExit('This smoke check requires an X11 Linux runner.')
    executable = Path(sys.argv[-1]).resolve()
    if '--inside' not in sys.argv:
        subprocess.run(['dbus-run-session', '--', 'xvfb-run', '-a', '-s', '-screen 0 1600x1000x24 -dpi 96',
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
            subprocess.run(['xdotool', 'windowactivate', '--sync', window], check=True, timeout=10)
            click_client(window, 36, 516)
            states = [capture_state(evidence, window, 'accounts')]
            subprocess.run(['xdotool', 'key', 'Escape'], check=True, timeout=5)
            time.sleep(.5)
            # Account flyouts can retain a transient desktop tooltip/focus.
            # Dismiss via the real outside-click scrim before requesting the
            # drawer, rather than letting the brand click only close Accounts.
            click_client(window, 500, 300)
            click_client(window, 36, 34)
            states.append(capture_state(evidence, window, 'drawer', 'drawer'))
            subprocess.run(['xdotool', 'key', 'Escape'], check=True, timeout=5)
            time.sleep(.5)
            click_client(window, 36, 563)
            states.append(capture_state(evidence, window, 'settings', 'light'))
            click_client(window, 120, 127)
            click_client(window, 300, 127)
            states.append(capture_state(evidence, window, 'dark', 'dark'))
            click_client(window, 170, 170)
            states.append(capture_state(evidence, window, 'pitch-black', 'black'))
            click_client(window, 120, 127)
            click_client(window, 230, 127)
            states.append(capture_state(evidence, window, 'light', 'light'))
            subprocess.run(['xdotool', 'key', 'alt+Left', 'mousemove', '--window', window, '500', '400'], check=True, timeout=5)
            subprocess.run(['xdotool', 'windowactivate', '--sync', window, 'key', 'F11'], check=True, timeout=10)
            for expected_fullscreen, key in [(True, None), (False, 'Escape')]:
                if key:
                    subprocess.run(['xdotool', 'key', key], check=True, timeout=10)
                deadline = time.monotonic() + 10
                while True:
                    state = subprocess.check_output(['xprop', '-id', window, '_NET_WM_STATE'], text=True, timeout=5)
                    if ('_NET_WM_STATE_FULLSCREEN' in state) == expected_fullscreen:
                        break
                    if time.monotonic() > deadline:
                        raise RuntimeError('Native fullscreen keyboard transition did not finish: ' + state)
                    time.sleep(.2)
            deadline = time.monotonic() + 10
            while True:
                restored = subprocess.check_output(['xdotool', 'getwindowgeometry', '--shell', window], text=True, timeout=5)
                if 'WIDTH=900\n' in restored and 'HEIGHT=600\n' in restored:
                    break
                if time.monotonic() > deadline:
                    raise RuntimeError('Escape did not restore the prior client size: ' + restored)
                time.sleep(.2)
            subprocess.run(['xdotool', 'windowactivate', '--sync', window, 'key', 'Alt+F4'], check=True, timeout=10)
            app.wait(timeout=10)
            if app.returncode != 0:
                raise RuntimeError(f'Close failed: {app.returncode}')
            (evidence / 'launch-result.txt').write_text('PASS: visible painted window, two resizes, native close, exit 0.\n')
            (evidence / 'native-linux.json').write_text(json.dumps({
                'display': 'Xvfb 1600x1000x24, 96 DPI / Openbox / software GL',
                'captures': observations, 'exit_code': app.returncode,
                'appearance_captures': states,
                'fullscreen': 'F11 entered; Escape restored 900x600',
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
