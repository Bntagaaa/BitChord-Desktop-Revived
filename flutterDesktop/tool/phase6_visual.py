"""CI-only, real-client exploratory capture seed. Never a full parity verdict.

Runs sequential clients in disposable profiles on one Xvfb/Openbox display.
Every input and capture is recorded; unvisited source-inventoried states stay
NOT VERIFIED. Live anonymous content is not a deterministic pixel golden.
"""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time
from types import SimpleNamespace

from PIL import Image

from visual_parity import capture, compare, digest, write_json

ROOT = Path(__file__).resolve().parents[1]
REFERENCE = '348b613a0c3db1a54de56483ea6f8748d6465211'
NAVIGATION = [('listen-now', 77), ('new', 109), ('search', 141),
              ('library', 173), ('history', 224), ('downloads', 256),
              ('local-music', 288), ('spotify', 320), ('settings', 748)]


def coverage_inventory(inventory):
    """Source discovery is a lower bound, never evidence a route was visited."""
    section_block = inventory['settings_source'].split('}', 1)[0]
    sections = re.findall(r'^    ([A-Z_]+),?$', section_block, re.M)
    rows = []
    for group, values in (
        ('route', [route['id'] for route in inventory['routes']]),
        ('overlay', inventory['overlays']), ('settings-page', inventory['settings_pages']),
        ('settings-section', sections),
        ('interaction', ['playing-dock', 'pause-resume', 'seek', 'next-previous',
                         'shuffle-repeat', 'volume', 'queue', 'fullscreen-player',
                         'synced-lyrics', 'lyrics-offset-source-translation-share',
                         'video-canvas-pip', 'signed-in-account-switch-persistence',
                         'collection-artist-shelf', 'context-menus', 'keyboard-focus-hover',
                         'theme-dark', 'theme-pitch-black', 'scroll-restoration',
                         'loading-error-recovery', 'physical-audio', 'windows-paired-captures'])):
        for value in values:
            rows.append({'id': f'{group}/{value}', 'reference': 'NOT VERIFIED',
                         'flutter': 'NOT VERIFIED', 'parity': 'NOT VERIFIED',
                         'reason': 'Not visited by this bounded automation; exploration remains required.'})
    return rows


def run(*args, **kwargs):
    return subprocess.run(list(map(str, args)), check=True, timeout=kwargs.pop('timeout', 15), **kwargs)


def find_one(root, predicate):
    found = [path for path in root.rglob('*') if path.is_file() and predicate(path)]
    if len(found) != 1:
        raise RuntimeError(f'Expected one release launcher under {root}; found {len(found)}')
    return found[0].resolve()


def stop(process):
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=8)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)


class Client:
    def __init__(self, kind, executable, output, environment):
        self.kind, self.executable, self.output, self.environment = kind, executable, output, environment
        self.actions = []
        self.captures = {}
        self.width, self.height = 1220, 780

    def input(self, *args):
        self.actions.append({'utc': datetime.now(timezone.utc).isoformat(), 'xdotool': list(map(str, args))})
        run('xdotool', *args)

    def click(self, x, y):
        self.input('mousemove', '--window', self.window, x, y)
        self.input('click', '1')
        time.sleep(.8)

    def screenshot(self, name):
        destination = self.output / self.kind / f'{name}.png'
        capture(SimpleNamespace(window=self.window, output=destination, state=name,
                                source=REFERENCE if self.kind == 'reference' else os.environ.get('GITHUB_SHA', 'unknown'),
                                theme='light', scale=100, display_scale='96 DPI / 1x',
                                font='application default; fontconfig.txt records installed fonts',
                                width=self.width, height=self.height, settle=1, expected_pid=None))
        # OCR is supporting evidence only: it cannot prove focus, playback or route correctness.
        with Image.open(destination) as frame:
            frame.crop((220, 0, self.width, self.height - 80)).save(self.output / 'ocr-input.png')
        text = subprocess.check_output(['tesseract', str(self.output / 'ocr-input.png'), 'stdout', '--psm', '11'],
                                       text=True, stderr=subprocess.DEVNULL, timeout=20)
        destination.with_suffix('.txt').write_text(text)
        self.captures[name] = {'path': str(destination.relative_to(self.output)), 'sha256': digest(destination),
                               'status': 'CAPTURED; route/state requires review', 'ocr': text,
                               'actions': list(self.actions)}
        return text

    def exercise(self):
        log = (self.output / f'{self.kind}-launch.log').open('w')
        self.process = subprocess.Popen([str(self.executable)], env=self.environment, stdout=log, stderr=log)
        try:
            deadline = time.monotonic() + 60
            while time.monotonic() < deadline:
                if self.process.poll() is not None:
                    raise RuntimeError(f'{self.kind} exited before window mapping: {self.process.returncode}')
                result = subprocess.run(['xdotool', 'search', '--onlyvisible', '--name', '^BitChord$'],
                                        capture_output=True, text=True, timeout=5)
                if result.returncode == 0 and result.stdout.strip():
                    self.window = result.stdout.splitlines()[-1]
                    break
                time.sleep(.3)
            else:
                raise RuntimeError(f'{self.kind} did not map a BitChord window')
            self.input('windowmove', self.window, 40, 60)
            self.input('windowsize', '--sync', self.window, self.width, self.height)
            self.input('windowactivate', '--sync', self.window)
            time.sleep(6)
            # All routes are actual sidebar clicks; no production route/test hooks.
            for route, y in NAVIGATION:
                self.click(85, y)
                self.screenshot(f'route-{route}-1220x780')
            # Capture the complete scrollable settings body, without toggling features.
            for index in range(1, 13 if self.kind == 'reference' else 5):
                self.input('mousemove', '--window', self.window, 1000, 500)
                self.input('click', '--repeat', 5, '--delay', 60, '5')
                self.screenshot(f'settings-scroll-{index:02d}-1220x780')
            self.click(85, 77)
            self.click(85, 700)
            self.screenshot('accounts-signed-out-1220x780')
            self.input('key', 'Escape')
            self.click(1210, 400)
            self.click(85, 77)
            self.width, self.height = 900, 600
            self.input('windowsize', '--sync', self.window, self.width, self.height)
            self.screenshot('home-900x600')
            self.click(36, 34)
            self.screenshot('drawer-900x600')
            self.input('key', 'Escape')
            self.input('key', 'alt+F4')
            self.process.wait(timeout=15)
            if self.process.returncode != 0:
                raise RuntimeError(f'{self.kind} native close exited {self.process.returncode}')
        finally:
            stop(self.process)
            log.close()
            write_json(self.output / f'{self.kind}-actions.json', self.actions)
            write_json(self.output / f'{self.kind}-captures.json', self.captures)


def report(output, rows, clients, failure=None):
    pairs = []
    if len(clients) == 2:
        for name in sorted(clients[0].captures.keys() & clients[1].captures.keys()):
            a, b = (output / client.captures[name]['path'] for client in clients)
            metrics = compare(a, b, output, name)
            pairs.append({'state': name, 'metrics': metrics, 'parity': 'NOT VERIFIED',
                          'reason': 'Capture and numeric difference only; equivalent content and interaction need review.'})
    for row in rows:
        if row['id'].startswith('route/'):
            state = 'route-' + row['id'].split('/', 1)[1] + '-1220x780'
        elif row['id'] == 'overlay/accounts':
            state = 'accounts-signed-out-1220x780'
        else:
            continue
        for client in clients:
            if state in client.captures:
                row[client.kind] = client.captures[state]['status']
                row[client.kind + '_capture'] = client.captures[state]['path']
                row['reason'] = 'Inputs and screenshot recorded; inspect OCR/selection/content before marking visited or matched.'
    write_json(output / 'coverage.json', {'reference': REFERENCE, 'flutter': os.environ.get('GITHUB_SHA'),
                                         'phase6': 'INCOMPLETE', 'failure': failure, 'states': rows, 'pairs': pairs})
    lines = ['# Phase 6 hosted visual evidence', '', '**Phase 6 INCOMPLETE. No state is parity-accepted by this job.**', '',
             f'{len(pairs)} paired captures; {len(rows)} source-inventoried/required coverage entries.', '',
             'Xvfb 1600×1000, 96 DPI, Openbox, software GL; sequential signed-out clients.',
             'PNG/JSON/TXT files preserve client geometry, hashes, inputs, timestamps and OCR.',
             'Live anonymous feeds can differ. Unmasked pixel metrics are diagnostic, with no parity threshold.', '',
             'The inventory is a source-derived lower bound, not complete runtime discovery. Settings scrolls',
             'do not prove every category/subpage was exercised. A window fullscreen shortcut does not prove',
             'Fullscreen Player. Real playing/fullscreen/lyrics, account switching/session persistence,',
             'remaining overlays, dark/pitch-black, keyboard/hover and physical output remain unverified.',
             'Windows release builds/tests/native smokes run separately; paired Windows captures remain open.', '',
             '| State | Kotlin | Flutter | Parity |', '| --- | --- | --- | --- |']
    lines.extend(f"| {row['id']} | {row['reference']} | {row['flutter']} | {row['parity']} |" for row in rows)
    if failure:
        lines += ['', f'**Automation failure:** {failure}']
    (output / 'summary.md').write_text('\n'.join(lines) + '\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for field in ('flutter-root', 'reference-root', 'output'):
        parser.add_argument('--' + field, type=Path, required=True)
    parser.add_argument('--inside', action='store_true')
    args = parser.parse_args()
    if os.environ.get('GITHUB_ACTIONS') != 'true':
        raise SystemExit('Requires disposable GitHub Actions runner; never use a listener profile.')
    if not args.inside:
        run('dbus-run-session', '--', 'xvfb-run', '-a', '-s', '-screen 0 1600x1000x24 -dpi 96',
            sys.executable, __file__, *sys.argv[1:], '--inside', timeout=600)
        return
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    inventory = json.loads((ROOT / 'verification/phase2/reference-inventory.json').read_text())
    if inventory['reference'] != REFERENCE:
        raise RuntimeError('Unpinned reference inventory')
    rows, clients, failure = coverage_inventory(inventory), [], None
    write_json(output / 'source-inventory.json', inventory)
    (output / 'fontconfig.txt').write_text(subprocess.check_output(['fc-list'], text=True))
    manager = subprocess.Popen(['openbox'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        recorded = list(args.reference_root.rglob('reference-sha.txt'))
        if len(recorded) != 1 or recorded[0].read_text().strip() != REFERENCE:
            raise RuntimeError('Missing or mismatched reference build provenance')
        reference = find_one(args.reference_root, lambda path: path.name == 'BitChord' and path.parent.name == 'bin')
        flutter = find_one(args.flutter_root, lambda path: path.name == 'bitchord_desktop' and path.parent.name == 'bundle')
        # Artifact ZIPs lose executable bits, including the reference's bundled Java runtime.
        for root in (reference.parent.parent, flutter.parent):
            for path in root.rglob('*'):
                if path.is_file() and (path.parent.name == 'bin' or path.suffix in ('', '.so')):
                    path.chmod(path.stat().st_mode | 0o100)
        for kind, executable in [('reference', reference), ('flutter', flutter)]:
            with tempfile.TemporaryDirectory(prefix=f'phase6-{kind}-') as profile:
                environment = {**os.environ, 'GDK_BACKEND': 'x11', 'LIBGL_ALWAYS_SOFTWARE': '1',
                               'XDG_CONFIG_HOME': profile + '/config', 'XDG_DATA_HOME': profile + '/data',
                               'XDG_CACHE_HOME': profile + '/cache',
                               'JAVA_TOOL_OPTIONS': f'-Djava.util.prefs.userRoot={profile}/prefs -Duser.home={profile} -Duser.language=en -Duser.country=US'}
                if kind == 'reference':
                    run('java', ROOT / 'tool/SeedReferenceProfile.java', executable, env=environment, timeout=30)
                client = Client(kind, executable, output, environment)
                clients.append(client)
                client.exercise()
    except Exception as error:
        failure = f'{type(error).__name__}: {error}'
        raise
    finally:
        stop(manager)
        report(output, rows, clients, failure)


if __name__ == '__main__':
    main()
