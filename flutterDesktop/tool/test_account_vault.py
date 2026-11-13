"""Actual OS-keyring/DPAPI round trip using synthetic data, never user accounts."""
from pathlib import Path
import json
import os
import subprocess
import sys
import tempfile

exe = str(Path(sys.argv[1]).resolve())
with tempfile.TemporaryDirectory(prefix='bitchord-vault-test-') as temp:
    root = Path(temp)
    env = os.environ.copy()
    if os.name != 'nt':
        if not env.get('DBUS_SESSION_BUS_ADDRESS'):
            raise SystemExit('Run this test inside dbus-run-session.')
        env['HOME'] = str(root / 'home')
        env['XDG_DATA_HOME'] = str(root / 'keyring-data')
        Path(env['HOME']).mkdir()
        p = subprocess.run(['gnome-keyring-daemon', '--unlock', '--components=secrets'],
            input='synthetic-keyring-password\n', text=True, capture_output=True, env=env, timeout=20)
        if p.returncode:
            raise SystemExit('Unable to initialize the isolated test keyring')
    directory = root / 'private-vault'
    payload = '{"cookie":"synthetic-session-value-only","version":1}'
    def run(op, key='accounts-v1', value=None):
        return subprocess.run([exe, op, str(directory), key], input=value,
            capture_output=True, text=True, env=env, timeout=25)
    assert run('read').returncode == 3, 'missing record'
    assert run('write', value=payload).returncode == 0, 'secure write'
    r = run('read')
    assert r.returncode == 0 and r.stdout == payload, 'secure round trip'
    for path in root.rglob('*.dpapi'):
        assert payload.encode() not in path.read_bytes(), 'plaintext stored'
    assert run('write', '../invalid', payload).returncode == 64, 'key traversal'
    assert run('write', value='x' * (256 * 1024 + 1)).returncode == 64, 'input bound'
    assert run('read').stdout == payload, 'invalid write replaced secret'
    assert run('delete').returncode == 0, 'secure delete'
    assert run('read').returncode == 3, 'deleted secret survived'
    assert run('delete').returncode == 0, 'idempotent delete'
    print(json.dumps({'platform': sys.platform, 'secret_round_trip': 'PASS',
        'bounds_and_key_validation': 'PASS', 'erase': 'PASS', 'real_account': 'NOT_TESTED'}))
