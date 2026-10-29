#!/usr/bin/env python3
"""Real packaged BitChord GUI/helper test on a private X11/Xvfb display; no accounts or install privileges."""
import argparse
import ctypes as C
import ctypes.util
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import time
import zipfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('appimage', type=Path)
parser.add_argument('--old', type=Path, help='optional older fixed package; defaults to replacing the same package')
parser.add_argument('--output', type=Path, required=True, help='new isolated artifact directory')
parser.add_argument('--java', default=str(Path(os.environ['JAVA_HOME']) / 'bin/java') if 'JAVA_HOME' in os.environ else 'java')
parser.add_argument('--cases', nargs='+', choices=['success', 'hangup', 'missing', 'crash', 'backup-failure', 'legacy'], default=['success', 'hangup', 'missing', 'crash', 'backup-failure', 'legacy'])
args = parser.parse_args()
image = args.appimage.resolve(); old_image = (args.old or image).resolve()
output = args.output.resolve(); output.mkdir(parents=True, exist_ok=False)
extract = output / 'metadata'; extract.mkdir()
for pattern in ['usr/lib/app/desktopApp*.jar', 'usr/lib/app/BitChord.cfg']:
    subprocess.run([str(image), '--appimage-extract', pattern], cwd=extract, stdout=subprocess.DEVNULL, check=True)
jar, = (extract / 'squashfs-root/usr/lib/app').glob('desktopApp*.jar')
with zipfile.ZipFile(jar) as archive:
    helper_bytes = archive.read('updater/appimage-update.sh')
config = (extract / 'squashfs-root/usr/lib/app/BitChord.cfg').read_text()
version, = [line.split('=', 2)[-1] for line in config.splitlines() if line.startswith('java-options=-Dbitchord.version=')]

class Attributes(C.Structure):
    _fields_ = [('x', C.c_int), ('y', C.c_int), ('width', C.c_int), ('height', C.c_int), ('border_width', C.c_int), ('depth', C.c_int),
                ('visual', C.c_void_p), ('root', C.c_ulong), ('class_', C.c_int), ('bit_gravity', C.c_int), ('win_gravity', C.c_int),
                ('backing_store', C.c_int), ('backing_planes', C.c_ulong), ('backing_pixel', C.c_ulong), ('save_under', C.c_int),
                ('colormap', C.c_ulong), ('map_installed', C.c_int), ('map_state', C.c_int), ('all_event_masks', C.c_long),
                ('your_event_mask', C.c_long), ('do_not_propagate_mask', C.c_long), ('override_redirect', C.c_int), ('screen', C.c_void_p)]
x = C.CDLL(ctypes.util.find_library('X11'))
x.XOpenDisplay.argtypes = [C.c_char_p]; x.XOpenDisplay.restype = C.c_void_p
x.XDefaultRootWindow.argtypes = [C.c_void_p]; x.XDefaultRootWindow.restype = C.c_ulong
x.XQueryTree.argtypes = [C.c_void_p, C.c_ulong, C.POINTER(C.c_ulong), C.POINTER(C.c_ulong), C.POINTER(C.POINTER(C.c_ulong)), C.POINTER(C.c_uint)]
x.XFetchName.argtypes = [C.c_void_p, C.c_ulong, C.POINTER(C.c_char_p)]
x.XInternAtom.argtypes = [C.c_void_p, C.c_char_p, C.c_int]; x.XInternAtom.restype = C.c_ulong
x.XGetWindowProperty.argtypes = [C.c_void_p, C.c_ulong, C.c_ulong, C.c_long, C.c_long, C.c_int, C.c_ulong, C.POINTER(C.c_ulong), C.POINTER(C.c_int), C.POINTER(C.c_ulong), C.POINTER(C.c_ulong), C.POINTER(C.POINTER(C.c_ubyte))]
x.XGetWindowAttributes.argtypes = [C.c_void_p, C.c_ulong, C.POINTER(Attributes)]
x.XFree.argtypes = [C.c_void_p]
display = x.XOpenDisplay(None)
assert display, 'A private X11 display (e.g. xvfb-run) is required'

def owned(pid, case):
    try:
        return f'-Duser.home={case}/home'.encode() in Path(f'/proc/{pid}/environ').read_bytes()
    except OSError:
        return False

def windows(case):
    parent = C.c_ulong(); ancestor = C.c_ulong(); children = C.POINTER(C.c_ulong)(); count = C.c_uint()
    x.XQueryTree(display, x.XDefaultRootWindow(display), C.byref(ancestor), C.byref(parent), C.byref(children), C.byref(count))
    result = []
    try:
        for window in children[:count.value]:
            name = C.c_char_p()
            if not x.XFetchName(display, window, C.byref(name)): continue
            title = name.value.decode(errors='replace') if name.value else ''; x.XFree(name)
            if title != 'BitChord': continue
            attributes = Attributes()
            if not x.XGetWindowAttributes(display, window, C.byref(attributes)) or attributes.map_state != 2 or attributes.width <= 0 or attributes.height <= 0: continue
            kind = C.c_ulong(); fmt = C.c_int(); n = C.c_ulong(); remaining = C.c_ulong(); data = C.POINTER(C.c_ubyte)()
            x.XGetWindowProperty(display, window, x.XInternAtom(display, b'_NET_WM_PID', 0), 0, 1, 0, 0, C.byref(kind), C.byref(fmt), C.byref(n), C.byref(remaining), C.byref(data))
            try:
                if n.value:
                    pid = C.cast(data, C.POINTER(C.c_ulong))[0]
                    if owned(pid, case): result.append({'window': window, 'pid': pid, 'width': attributes.width, 'height': attributes.height})
            finally:
                if data: x.XFree(data)
    finally:
        if children: x.XFree(children)
    return result

def wait_for(predicate, seconds, description):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        value = predicate()
        if value: return value
        time.sleep(.1)
    raise AssertionError(description)

def digest(path):
    # Ubuntu 22.04 ships Python 3.10, before hashlib.file_digest was added.
    checksum = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            checksum.update(chunk)
    return checksum.hexdigest()

def ticks(pid):
    return Path(f'/proc/{pid}/stat').read_text().rsplit(') ', 1)[1].split()[19]

def seed(case, paths):
    source = Path(__file__).with_name('SeedUpdaterProfile.java')
    subprocess.run([args.java, f'-Duser.home={case}/home', f'-Djava.util.prefs.userRoot={case}/prefs', str(source), str(case)] + [str(p) for p in paths], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

def screenshot(path):
    try:
        from PIL import ImageGrab
        ImageGrab.grab(xdisplay=os.environ.get('DISPLAY')).save(path)
    except ImportError:
        pass  # GUI verification uses X11 mapping/PID plus the JVM acknowledgment, not this optional image.

# Probe the real normal runtime; do not equate extraction with successful FUSE execution.
probe_dir = output / 'normal-runtime'; probe_dir.mkdir()
probe_env = os.environ.copy(); probe_env.pop('APPIMAGE_EXTRACT_AND_RUN', None)
probe_env.update(HOME=str(probe_dir), JAVA_TOOL_OPTIONS=f'-Duser.home={probe_dir}/home -Djava.util.prefs.userRoot={probe_dir}/prefs')
with (probe_dir / 'launch.log').open('w') as log:
    probe = subprocess.Popen([str(image)], env=probe_env, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    try:
        found = wait_for(lambda: windows(probe_dir) or (probe.poll() is not None and 'exited'), 20, 'normal runtime probe timed out')
        normal = 'gui-mapped' if found != 'exited' else 'unavailable-in-environment'
    except AssertionError:
        normal = 'unconfirmed-timeout'
    finally:
        try: os.killpg(probe.pid, signal.SIGTERM)
        except ProcessLookupError: pass
        try: probe.wait(timeout=5)
        except subprocess.TimeoutExpired: probe.kill(); probe.wait()
results = {'target_version': version, 'normal_runtime': normal, 'extract_and_run': []}
(output / 'results.json').write_text(json.dumps(results, indent=2) + '\n')

for mode in args.cases:
    case = output / mode; case.mkdir(); installation = case / 'installation with spaces'; installation.mkdir()
    (case / 'home').mkdir()
    operation = case / ('bitchord-update-helper-fixture' if mode == 'legacy' else 'state-operation'); operation.mkdir(mode=0o700)
    ready = operation / ('window-ready-00000000-0000-4000-8000-000000000000' if mode == 'legacy' else 'window-ready')
    current = installation / 'BitChord Test.AppImage'; staged = installation / 'staged.AppImage'; backup = installation / 'previous.AppImage'
    shutil.copy2(old_image, current); shutil.copy2(image, staged)
    if mode == 'crash':
        with staged.open('r+b') as stream: stream.seek(18); stream.write(b'\xff\xff')  # Real package with an unlaunchable ELF machine header.
    seed(case, [current, staged])
    (case / 'home/user-data-sentinel').write_text('preserve settings, queue and downloads')
    sentinel = (case / 'home/user-data-sentinel').read_bytes()
    old_hash = digest(current); new_hash = digest(staged)
    token = os.urandom(16).hex(); (operation / 'request.token').write_text(token)
    helper = operation / 'bundled-helper.sh'
    helper.write_bytes(Path(__file__).with_name('fixtures').joinpath('appimage-update-7eaa.sh').read_bytes() if mode == 'legacy' else helper_bytes)
    env = os.environ.copy()
    env.update(HOME=str(case / 'home'), APPIMAGE_EXTRACT_AND_RUN='1', XDG_STATE_HOME=str(case / 'state'), XDG_CACHE_HOME=str(case / 'cache'),
               JAVA_TOOL_OPTIONS=f'-Duser.home={case}/home -Djava.util.prefs.userRoot={case}/prefs')
    processes = []
    try:
        with (case / 'old-launch.log').open('w') as log:
            original = subprocess.Popen([str(current)], env=env, stdout=log, stderr=subprocess.STDOUT, start_new_session=True); processes.append(original)
        old_window = wait_for(lambda: windows(case), 60, f'{mode}: original GUI did not map')[0]
        old_pid = old_window['pid']; old_ticks = ticks(old_pid)
        time.sleep(1)
        screenshot(case / 'before.png')
        # Inherit the actual old AppImage environment (only selected runtime variables).
        inherited = Path(f'/proc/{old_pid}/environ').read_bytes().split(b'\0')
        for item in inherited:
            key, _, value = item.partition(b'=')
            if key.decode() in ['APPIMAGE', 'APPDIR', 'OWD', 'ARGV0', 'LD_LIBRARY_PATH', 'LD_PRELOAD', 'LD_AUDIT']:
                env[key.decode()] = value.decode()
        polls = '200' if mode == 'missing' else '900'
        with (operation / 'install.log').open('w') as log:
            command = ['/usr/bin/setsid', '/bin/sh', str(helper), str(old_pid), str(current), str(staged), str(backup), new_hash, str(ready)]
            if mode != 'legacy': command += [token, version, old_ticks, old_hash, '120', polls, '0.1']
            helper_process = subprocess.Popen(command, env=env, cwd=operation, stdout=log, stderr=subprocess.STDOUT)
            processes.append(helper_process)
        if mode != 'legacy':
            wait_for(lambda: (operation / 'armed').exists() or (helper_process.poll() is not None and 'failed'), 10, 'helper did not arm')
        assert helper_process.poll() is None, (operation / 'install.log').read_text()
        assert os.getsid(helper_process.pid) != os.getsid(old_pid), 'helper still belongs to the old session'
        if mode == 'missing': (operation / 'request.token').write_text('unavailable-acknowledgment')
        if mode == 'backup-failure': installation.chmod(0o500)
        if mode == 'hangup': os.killpg(original.pid, signal.SIGHUP)
        else: os.kill(old_pid, signal.SIGTERM)
        original.wait(timeout=20)
        code = helper_process.wait(timeout=100)
        installation.chmod(0o700)
        mapped = wait_for(lambda: [w for w in windows(case) if w['pid'] != old_pid], 60, f'{mode}: replacement/recovery GUI did not map')
        assert len(mapped) == 1, f'duplicate GUI instances: {mapped}'
        new_pid = mapped[0]['pid']; time.sleep(2)
        assert len(windows(case)) == 1, 'late duplicate GUI after recovery'
        marker = ready
        if mode == 'legacy':
            assert code == 0 and f'pid={new_pid}\n' in marker.read_text(), (operation / 'install.log').read_text()
            assert digest(current) == new_hash and digest(backup) == old_hash
        elif mode in ('success', 'hangup'):
            assert code == 0, (operation / 'install.log').read_text()
            assert f'pid={new_pid}\n' in marker.read_text() and f'token={token}\n' in marker.read_text()
            assert digest(current) == new_hash and digest(backup) == old_hash and not staged.exists()
        elif mode == 'missing':
            assert code == 2 and not marker.exists(), (operation / 'install.log').read_text()
            assert digest(current) == new_hash and digest(backup) == old_hash
            assert owned(new_pid, case), 'healthy unacknowledged JVM was killed'
        else:
            assert code == 1 and digest(current) == old_hash, (operation / 'install.log').read_text()
            if mode == 'crash': assert digest(backup) == old_hash
        seed(case, ['verify'])
        assert (case / 'home/user-data-sentinel').read_bytes() == sentinel
        screenshot(case / 'after.png')
        result = {'case': mode, 'helper_exit': code, 'old_jvm_pid': old_pid, 'new_jvm_pid': new_pid, 'mapped_windows': mapped,
                  'readiness': marker.read_text() if marker.exists() else None, 'backup_retained': backup.exists(), 'user_data_preserved': True}
        results['extract_and_run'].append(result)
        (output / 'results.json').write_text(json.dumps(results, indent=2) + '\n')
        print(json.dumps({'case': mode, 'helper_exit': code, 'mapped_windows': len(mapped), 'user_data_preserved': True}), flush=True)
    finally:
        installation.chmod(0o700)
        # Only this fixture profile's processes; include AppImage runtime and JVM descendants.
        for _ in range(2):
            for process in Path('/proc').iterdir():
                if process.name.isdigit() and owned(int(process.name), case):
                    try: os.kill(int(process.name), signal.SIGTERM)
                    except ProcessLookupError: pass
            time.sleep(.2)
        for process in processes:
            try: process.wait(timeout=5)
            except subprocess.TimeoutExpired: process.kill(); process.wait()
        # Keep diagnostics/screenshots; discard only this test's large disposable package copies.
        for path in installation.iterdir():
            if path.is_file(): path.unlink()
print('Real AppImage GUI/helper checks passed; normal runtime:', normal, flush=True)
