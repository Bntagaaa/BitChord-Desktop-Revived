#!/usr/bin/env python3
"""Real Linux process/helper tests; optional native UI tests, not a full BitChord build."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import tempfile
import time


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def ticks(pid):
    return Path(f'/proc/{pid}/stat').read_text().rsplit(') ', 1)[1].split()[19]


def wait_for(predicate, seconds=5):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(.02)
    raise AssertionError('Timed out waiting for fixture state')


def process_cases(helper, ui):
    cases = ['success', 'no-ack', 'wrong-token', 'wrong-version', 'crash',
             'bad-hash', 'aborted', 'symlink-backup', 'changed-backup', 'unsafe-backup']
    for mode in cases:
        with tempfile.TemporaryDirectory(prefix='BitChord Linux update ') as temp:
            root = Path(temp)
            op = root / 'operation-test'; op.mkdir(mode=0o700)
            token = 'a' * 32
            current = root / 'BitChord test.AppImage'
            staged = root / '.bitchord-update-fixture.AppImage'
            backup = root / (current.name + '.previous-' + token)
            unrelated = root / 'other.previous-unrelated'
            unrelated.write_text('do not delete')
            current.write_text('#!/bin/sh\nprintf old > "$0.recovered"\n')
            current.chmod(0o700); old = current.read_bytes()
            (op / 'request.token').write_text(token)
            shutil.copy2(ui, op / 'linux-updater-ui.sh')
            staged.write_text('''#!/usr/bin/python3
import json, os, time
from pathlib import Path
root = Path(os.environ['FIXTURE_ROOT'])
mode = os.environ['FIXTURE_MODE']
count = root / 'launch.count'
count.write_text(str(int(count.read_text()) + 1) if count.exists() else '1')
if mode == 'crash':
    raise SystemExit(7)
backup = Path(os.environ['FIXTURE_BACKUP'])
assert backup.exists(), 'Backup removed before GUI acknowledgment'
(root / 'backup-before-ready').write_text('present')
if mode == 'changed-backup':
    backup.write_text('modified; must not remove')
if mode != 'no-ack':
    pid = os.getpid()
    start = Path('/proc/self/stat').read_text().rsplit(') ', 1)[1].split()[19]
    token = 'bad' if mode == 'wrong-token' else os.environ['BITCHORD_UPDATE_TOKEN']
    version = '0.0.0' if mode == 'wrong-version' else os.environ['BITCHORD_UPDATE_EXPECTED_VERSION']
    ready = Path(os.environ['BITCHORD_UPDATE_READY_FILE'])
    temporary = ready.with_suffix('.tmp')
    temporary.write_text(f'token={token}\\npid={pid}\\nstart_ticks={start}\\nversion={version}\\nstate=gui-ready\\n')
    temporary.replace(ready)
time.sleep(30)
''')
            staged.chmod(0o700)
            expected = digest(staged)
            if mode == 'bad-hash': expected = '0' * 64
            if mode == 'symlink-backup': backup.symlink_to(unrelated)
            if mode == 'unsafe-backup': backup = root / 'not-owned.AppImage'
            parent = subprocess.Popen(['sleep', '30'])
            parent_start = ticks(parent.pid)
            environment = os.environ.copy()
            for key in ('DISPLAY', 'WAYLAND_DISPLAY'):
                environment.pop(key, None)
            environment.update(FIXTURE_ROOT=str(root), FIXTURE_BACKUP=str(backup), FIXTURE_MODE=mode)
            command = ['setsid', 'sh', str(helper), str(parent.pid), str(current), str(staged),
                       str(backup), expected, str(op / 'window-ready'), token, '1.9.1', parent_start,
                       digest(current), '100', '15', '0.05']
            child = None
            try:
                with (op / 'install.log').open('w') as log:
                    child = subprocess.Popen(command, env=environment, stdout=log, stderr=subprocess.STDOUT)
                wait_for(lambda: (op / 'armed').exists() or child.poll() is not None)
                if child.poll() is None:
                    assert current.read_bytes() == old and not (root / 'launch.count').exists()
                    if mode == 'aborted':
                        (op / 'abort').write_text('abort')
                    else:
                        parent.terminate(); parent.wait(timeout=3)
                code = child.wait(timeout=12)
                detail = (op / 'install.log').read_text()
                if mode in ('success', 'changed-backup'):
                    assert code == 0, detail
                    assert (root / 'backup-before-ready').exists()
                    assert (root / 'launch.count').read_text() == '1'
                    if mode == 'success':
                        assert not backup.exists(), detail
                        assert (op / 'backup.cleanup').read_text().strip() == 'deleted-after-gui-confirmed'
                        # A completed request cannot run twice or reopen a second instance.
                        assert subprocess.run(command, env=environment, capture_output=True, timeout=3).returncode != 0
                        assert (root / 'launch.count').read_text() == '1'
                    else:
                        assert backup.read_text() == 'modified; must not remove'
                        assert (op / 'backup.cleanup').read_text().strip() == 'retained-file-changed'
                elif mode in ('no-ack', 'wrong-token', 'wrong-version'):
                    assert code == 2, detail
                    assert backup.read_bytes() == old
                    assert not (op / 'backup.cleanup').exists()
                    assert (root / 'launch.count').read_text() == '1'
                    runtime = int(re.search(r'candidate-started runtime_pid=(\d+)', detail)[1])
                    os.kill(runtime, 0)  # Healthy but unconfirmed app must not be killed.
                elif mode == 'crash':
                    assert code == 1 and current.read_bytes() == old, detail
                    assert backup.read_bytes() == old
                    wait_for(lambda: Path(str(current) + '.recovered').exists())
                else:
                    assert code == 1 and current.read_bytes() == old, detail
                    assert not (op / 'backup.cleanup').exists()
                    if mode == 'aborted': assert parent.poll() is None
                assert unrelated.read_text() == 'do not delete'
                assert not Path(str(current) + '.update-lock').exists()
                if mode in ('bad-hash', 'aborted'):
                    assert not staged.exists(), 'Private staging copy leaked'
                print(json.dumps({'case': mode, 'exit': code, 'passed': True}), flush=True)
            finally:
                if child and child.poll() is None:
                    child.terminate(); child.wait(timeout=3)
                if parent.poll() is None:
                    parent.terminate(); parent.wait(timeout=3)
                # Kill only the unique session created by this fixture's candidate.
                log = op / 'install.log'
                if log.exists():
                    for pid in re.findall(r'candidate-started runtime_pid=(\d+)', log.read_text()):
                        try: os.killpg(int(pid), signal.SIGTERM)
                        except ProcessLookupError: pass


def ui_cases(ui):
    assert os.environ.get('DISPLAY'), 'Use xvfb-run for native UI checks'
    for desktop in ('GNOME', 'KDE'):
        with tempfile.TemporaryDirectory(prefix='BitChord Linux UI ') as temp:
            op = Path(temp)
            parent = subprocess.Popen(['sleep', '30'])
            (op / 'status').write_text('waiting-for-app-exit\n')
            env = dict(os.environ, XDG_CURRENT_DESKTOP=desktop)
            proc = subprocess.Popen(['sh', str(ui), str(op), str(parent.pid), ticks(parent.pid)],
                                    env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            try:
                def visible():
                    listing = subprocess.run(['xwininfo', '-root', '-tree'], capture_output=True,
                                             text=True, check=True).stdout
                    for window in re.findall(r'(0x[0-9a-fA-F]+) \"[^\"]*BitChord Updater[^\"]*\"', listing):
                        details = subprocess.run(['xwininfo', '-id', window], capture_output=True,
                                                 text=True, check=False).stdout
                        if 'Map State: IsViewable' in details:
                            return True
                    return False
                wait_for(visible, 15)
                assert parent.poll() is None
                (op / 'status').write_text('success-gui-confirmed\n')
                (op / 'completed').write_text('success-gui-confirmed\n')
                output, _ = proc.communicate(timeout=10)
                assert proc.returncode == 0, output.decode(errors='replace')
                wait_for(lambda: not visible(), 5)
                expected = 'kdialog' if desktop == 'KDE' else 'zenity'
                assert ('progress-ui=' + expected).encode() in output, output
                assert parent.poll() is None, 'UI must not kill the worker'
                print(json.dumps({'native_ui': desktop, 'visible': True, 'closed_on_success': True}), flush=True)
            finally:
                if proc.poll() is None: proc.terminate(); proc.wait(timeout=3)
                parent.terminate(); parent.wait(timeout=3)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--updaters', type=Path, default=Path(__file__).resolve().parents[2] / 'src/main/resources/updater')
    parser.add_argument('--ui', action='store_true', help='Require actual Zenity and KDE windows under Xvfb/D-Bus')
    args = parser.parse_args()
    helper = args.updaters.resolve() / 'appimage-update.sh'
    ui = args.updaters.resolve() / 'linux-updater-ui.sh'
    subprocess.run(['sh', '-n', str(helper)], check=True)
    subprocess.run(['sh', '-n', str(ui)], check=True)
    process_cases(helper, ui)
    if args.ui: ui_cases(ui)
    print('Linux updater regression checks passed.', flush=True)
