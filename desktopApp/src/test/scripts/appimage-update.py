#!/usr/bin/env python3
"""Exercise the production helper with disposable process/file fixtures, never a live installation."""
from pathlib import Path
import hashlib
import os
import subprocess
import tempfile
import time
import uuid

HELPER = Path(__file__).resolve().parents[2] / "main/resources/reworked/update-appimage.sh"


def checksum(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def fixture(directory, mode="success", old_hash=None, parent=None):
    token = str(uuid.uuid4())
    target = directory / "BitChordReworked-test.AppImage"
    candidate = directory / f".BitChordReworked-next-{token}.AppImage"
    backup = Path(str(target) + f".reworked-previous-{token}")
    operation = directory / ("operation-" + token)
    operation.mkdir()
    old = b"#!/bin/sh\nexit 0\n"
    target.write_bytes(old)
    target.chmod(0o700)
    candidate.write_text(
        '#!/bin/sh\nprintf "%s\\n%s\\n" "$BITCHORD_REWORKED_UPDATE_TOKEN" "$BITCHORD_REWORKED_UPDATE_VERSION" > "$BITCHORD_REWORKED_UPDATE_ACK"\nsleep 1\n'
        if mode == "success" else "#!/bin/sh\nexit 7\n"
    )
    candidate.chmod(0o700)
    new_hash = checksum(candidate)
    if mode == "bad-checksum":
        new_hash = "0" * 64
    if mode == "symlink":
        outside = directory / "untouched"
        outside.write_text("sentinel")
        candidate.unlink()
        candidate.symlink_to(outside)
    if parent:
        pid = parent.pid
        ticks = Path(f"/proc/{pid}/stat").read_text().rsplit(") ", 1)[1].split()[19]
    else:
        pid, ticks = 999999999, "0"
    args = ["/bin/sh", str(HELPER), str(target), str(candidate), str(backup),
            old_hash or checksum(target), new_hash, str(pid), ticks, str(operation), token, "1.9.2"]
    proc = subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    return proc, target, candidate, backup, operation, old


def run():
    with tempfile.TemporaryDirectory(prefix="reworked updater test ") as temporary:
        base = Path(temporary)
        for mode in ("success", "crash", "bad-checksum", "symlink", "old-changed"):
            directory = base / mode
            directory.mkdir()
            proc, target, candidate, backup, operation, old = fixture(
                directory, mode, "b" * 64 if mode == "old-changed" else None
            )
            stdout, stderr = proc.communicate(timeout=15)
            state = (operation / "status").read_text().strip()
            if mode == "success":
                assert proc.returncode == 0, stderr
                assert state == "complete", state
                assert backup.read_bytes() == old
                assert target.read_bytes() != old
            elif mode == "crash":
                assert proc.returncode != 0
                assert state == "rolled-back", (state, stdout, stderr)
                assert target.read_bytes() == old
            else:
                assert proc.returncode != 0
                assert target.read_bytes() == old
                assert not (operation / "accepted").exists()
                if mode == "symlink":
                    assert (directory / "untouched").read_text() == "sentinel"
            assert not Path(str(target) + ".reworked-update-lock").exists()
            print(f"PASS {mode}")

        directory = base / "waits-for-exact-parent"
        directory.mkdir()
        parent = subprocess.Popen(["sleep", "20"])
        try:
            if not Path(f"/proc/{parent.pid}/stat").exists():
                print("SKIP waits-for-exact-parent: this executor does not expose child /proc identities")
                return
            proc, target, candidate, backup, operation, old = fixture(directory, parent=parent)
            deadline = time.monotonic() + 5
            while not (operation / "accepted").exists() and time.monotonic() < deadline:
                time.sleep(0.05)
            assert (operation / "accepted").exists()
            assert target.read_bytes() == old and not backup.exists()
            parent.terminate()
            parent.wait(timeout=5)
            stdout, stderr = proc.communicate(timeout=15)
            assert proc.returncode == 0, (stdout, stderr)
            assert (operation / "status").read_text().strip() == "complete"
            print("PASS waits-for-exact-parent")
        finally:
            if parent.poll() is None:
                parent.kill()
                parent.wait()


if __name__ == "__main__":
    run()
