"""Exercise the production portable updater with disposable Windows EXE fixtures.

No installed application is accessed. A tiny C# test process replaces the GUI,
including the same visible-startup acknowledgment contract.
"""
from __future__ import annotations

import ctypes
import base64
from ctypes import wintypes
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
import uuid


HELPER = Path(__file__).resolve().parents[2] / "main/resources/reworked/update-portable.ps1"
VERSION = "1.9.2-reworked.1"
SOURCE = r"""
using System;
using System.IO;
using System.Threading;
public static class Fixture {
    public static int Main(string[] args) {
        string root = AppDomain.CurrentDomain.BaseDirectory;
        if (args.Length > 0 && args[0] == "parent") { Thread.Sleep(120000); return 0; }
        string ack = Environment.GetEnvironmentVariable("BITCHORD_REWORKED_UPDATE_ACK");
        if (ack == null) { File.WriteAllText(Path.Combine(root, "restored.txt"), "running"); Thread.Sleep(500); return 0; }
        if (File.Exists(Path.Combine(root, "crash.flag"))) return 23;
        File.WriteAllText(ack,
            Environment.GetEnvironmentVariable("BITCHORD_REWORKED_UPDATE_TOKEN") + "\n" +
            Environment.GetEnvironmentVariable("BITCHORD_REWORKED_UPDATE_VERSION") + "\n");
        Thread.Sleep(500); return 0;
    }
}
"""


def digest(file: Path) -> str:
    return hashlib.sha256(file.read_bytes()).hexdigest()


def created_milliseconds(process: subprocess.Popen) -> int:
    created, exited, kernel, user = (wintypes.FILETIME() for _ in range(4))
    function = ctypes.windll.kernel32.GetProcessTimes
    function.argtypes = [wintypes.HANDLE] + [ctypes.POINTER(wintypes.FILETIME)] * 4
    function.restype = wintypes.BOOL
    if not function(int(process._handle), *(ctypes.byref(value) for value in (created, exited, kernel, user))):
        raise ctypes.WinError()
    ticks = (created.dwHighDateTime << 32) | created.dwLowDateTime
    return ticks // 10000 - 11644473600000


def wait_for(predicate, seconds: float, description: str) -> None:
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(0.1)
    raise AssertionError(description)


def app_tree(directory: Path, executable: Path, version: str, crash: bool = False) -> None:
    (directory / "app").mkdir(parents=True)
    (directory / "runtime/bin/server").mkdir(parents=True)
    shutil.copyfile(executable, directory / "BitChordReworked.exe")
    (directory / "runtime/bin/server/jvm.dll").write_bytes(b"fixture runtime; the test EXE hosts no JVM")
    (directory / "app/BitChordReworked.cfg").write_text(
        "java-options=-Dbitchord.product=reworked\njava-options=-Dbitchord.version=" + version + "\n"
    )
    (directory / "reworked-portable.marker").write_text("BitChord Desktop Reworked " + version + "\n")
    if crash:
        (directory / "crash.flag").write_text("fail before startup acknowledgment")


def scenario(workspace: Path, executable: Path, crash: bool) -> None:
    workspace.mkdir()
    target = workspace / "Portable player [test]"
    staging = workspace / ".BitChordReworked-next-fixture"
    candidate = staging / "BitChordReworked"
    operation = workspace / "operation-fixture"
    operation.mkdir()
    token = str(uuid.uuid4())
    backup = workspace / (".BitChordReworked-previous-" + token)
    failed = workspace / (".BitChordReworked-failed-" + token)
    app_tree(target, executable, "1.9.1-reworked.1")
    app_tree(candidate, executable, VERSION, crash)
    payload = operation / "verified-payload.zip"
    payload.write_bytes(b"archive contents are covered by the Kotlin extraction tests")
    manifest = {str(path.relative_to(candidate)).replace("\\", "/"): digest(path) for path in candidate.rglob("*") if path.is_file()}
    (operation / "staged-files.json").write_text(json.dumps(manifest))
    environment = {key: value for key, value in os.environ.items() if not key.startswith("BITCHORD_REWORKED_UPDATE_")}
    parent = subprocess.Popen([str(target / "BitChordReworked.exe"), "parent"], env=environment)
    helper = None
    try:
        job = dict(
            target=str(target / "BitChordReworked.exe"), payload=str(payload), sha256=digest(payload),
            oldSha256=digest(target / "BitChordReworked.exe"), version=VERSION, pid=parent.pid,
            started=created_milliseconds(parent), token=token, operation=str(operation), portableRoot=str(target),
            stagedRoot=str(candidate), stagingDirectory=str(staging), backupRoot=str(backup), failedRoot=str(failed),
        )
        (operation / "operation.json").write_text(json.dumps(job))
        environment["BITCHORD_REWORKED_UPDATE_OPERATION"] = str(operation)
        with (operation / "install.log").open("w") as log:
            encoded = base64.b64encode(HELPER.read_text().encode("utf-16le")).decode("ascii")
            helper = subprocess.Popen(["powershell.exe", "-NoProfile", "-NonInteractive", "-STA", "-EncodedCommand", encoded],
                                      env=environment, cwd=operation, stdout=log, stderr=subprocess.STDOUT)
            wait_for(lambda: (operation / "accepted").exists() or helper.poll() is not None, 30, "helper failed to accept the staged update")
            assert (operation / "accepted").exists(), (operation / "install.log").read_text()
            assert not backup.exists(), "must not replace a still-running portable application"
            assert "1.9.1-reworked.1" in (target / "app/BitChordReworked.cfg").read_text()
            parent.terminate()
            parent.wait(timeout=10)
            helper.wait(timeout=35)
        state = (operation / "status").read_text(encoding="utf-8-sig").strip()
        if crash:
            assert state == "rolled-back", (state, (operation / "install.log").read_text())
            assert "1.9.1-reworked.1" in (target / "app/BitChordReworked.cfg").read_text()
            assert failed.is_dir(), "failed replacement must be retained for diagnosis"
            wait_for(lambda: (target / "restored.txt").exists(), 10, "previous application was not reopened")
        else:
            assert helper.returncode == 0, (operation / "install.log").read_text()
            assert state == "complete", state
            assert VERSION in (target / "app/BitChordReworked.cfg").read_text()
            assert "1.9.1-reworked.1" in (backup / "app/BitChordReworked.cfg").read_text()
            assert not failed.exists()
        print("PASS", "portable early-crash rollback" if crash else "portable replacement + previous backup")
    finally:
        if parent.poll() is None:
            parent.terminate()
            parent.wait(timeout=10)
        if helper is not None and helper.poll() is None:
            helper.terminate()
            helper.wait(timeout=10)


def main() -> None:
    if sys.platform != "win32":
        print("SKIP Windows portable fixture requires a Windows process/rename implementation")
        return
    with tempfile.TemporaryDirectory(prefix="reworked-portable-worker-test-") as raw:
        directory = Path(raw)
        compiler = Path(os.environ["WINDIR"]) / "Microsoft.NET/Framework64/v4.0.30319/csc.exe"
        assert compiler.is_file(), "Windows .NET Framework C# compiler is required for this fixture"
        code = directory / "Fixture.cs"
        executable = directory / "Fixture.exe"
        code.write_text(SOURCE)
        subprocess.run([str(compiler), "/nologo", "/target:exe", "/out:" + str(executable), str(code)], check=True)
        scenario(directory / "success", executable, False)
        scenario(directory / "crash", executable, True)
        # Both launched replacement fixtures exit themselves; allow Windows to release PE handles.
        time.sleep(1)


if __name__ == "__main__":
    main()
