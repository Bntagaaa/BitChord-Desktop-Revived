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
import traceback
import uuid


HELPER = Path(__file__).resolve().parents[2] / "main/resources/reworked/update-portable.ps1"
INSTALLED_HELPER = HELPER.with_name("update-windows.ps1")
REPORTS = Path(__file__).resolve().parents[3] / "build/windows-portable-tests"
VERSION = "1.9.2-reworked.1"
SOURCE = r"""
using System;
using System.IO;
using System.Threading;
public static class Fixture {
    public static int Main(string[] args) {
        string root = AppDomain.CurrentDomain.BaseDirectory;
        if (args.Length > 0 && args[0] == "parent") { Thread.Sleep(120000); return 0; }
        File.WriteAllText(Path.Combine(root, "working-directory.txt"), Environment.CurrentDirectory);
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


def verify_working_directory(target: Path, report: Path) -> None:
    observed = (target / "working-directory.txt").read_text()
    evidence = {"requested_directory": str(target), "observed_directory": observed}
    try:
        evidence["same_directory"] = os.path.samefile(observed, target)
    except OSError as error:
        evidence.update(same_directory=False, identity_error=str(error))
    report.write_text(json.dumps(evidence, indent=2), encoding="utf-8")
    assert evidence["same_directory"], "The application's working directory differs: " + json.dumps(evidence)


def verify_worker_hashes(directory: Path, reports: Path) -> None:
    """Execute only each real worker's hash function; never run the installed updater."""
    binary = directory / "binary hash [test].bin"
    binary.write_bytes(bytes(range(256)) * 4096 + b"\x00\xff\x80end")
    source = r"""
$ErrorActionPreference = 'Stop'
$tokens = $null; $parseErrors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile($env:REWORKED_HASH_TEST_SCRIPT, [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count) { throw 'The production worker has syntax errors.' }
$function = $ast.Find({ param($node)
    $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'File-Sha256'
}, $true)
if (-not $function) { throw 'The production worker is missing File-Sha256.' }
. ([ScriptBlock]::Create($function.Extent.Text))
$lock = [IO.File]::Open($env:REWORKED_HASH_TEST_FILE, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
try { File-Sha256 $env:REWORKED_HASH_TEST_FILE } finally { $lock.Dispose() }
$released = [IO.File]::Open($env:REWORKED_HASH_TEST_FILE, [IO.FileMode]::Open, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
$released.Dispose()
"""
    encoded = base64.b64encode(source.encode("utf-16le")).decode("ascii")
    for worker in (HELPER, INSTALLED_HELPER):
        environment = os.environ.copy()
        environment["REWORKED_HASH_TEST_SCRIPT"] = str(worker)
        environment["REWORKED_HASH_TEST_FILE"] = str(binary)
        output = reports / ("hash-" + worker.stem + ".log")
        with output.open("w") as log:
            result = subprocess.run(["powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded],
                                    env=environment, stdout=log, stderr=subprocess.STDOUT, timeout=30)
        actual = output.read_text(encoding="utf-8-sig", errors="replace").strip()
        assert result.returncode == 0, (worker.name, actual)
        assert actual == digest(binary), (worker.name, "production SHA256 disagrees with the binary fixture", actual)
        print("PASS streaming SHA256 + shared read lock + handle release:", worker.name, flush=True)


def verify_installed_app_launch(directory: Path, executable: Path, reports: Path) -> None:
    """Run only the installed worker's application launch, preserving literal paths and ACK env."""
    target = directory / "Installed player [test]"
    target.mkdir()
    shutil.copyfile(executable, target / "BitChordReworked.exe")
    token = str(uuid.uuid4())
    source = r"""
$ErrorActionPreference = 'Stop'
$tokens = $null; $parseErrors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile($env:REWORKED_LAUNCH_TEST_SCRIPT, [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count) { throw 'The production worker has syntax errors.' }
$function = $ast.Find({ param($node)
    $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Start-App'
}, $true)
if (-not $function) { throw 'The production worker is missing Start-App.' }
. ([ScriptBlock]::Create($function.Extent.Text))
$app = Start-App $env:REWORKED_LAUNCH_TEST_DIR
try {
    if (-not $app.WaitForExit(10000)) { $app.Kill(); throw 'The disposable application did not exit.' }
    if ($app.ExitCode -ne 0) { throw 'The disposable application failed.' }
} finally { $app.Dispose() }
"""
    environment = {key: value for key, value in os.environ.items() if not key.startswith("BITCHORD_REWORKED_UPDATE_")}
    environment.update(REWORKED_LAUNCH_TEST_SCRIPT=str(INSTALLED_HELPER), REWORKED_LAUNCH_TEST_DIR=str(target),
                       BITCHORD_REWORKED_UPDATE_ACK=str(target / "ready"),
                       BITCHORD_REWORKED_UPDATE_TOKEN=token, BITCHORD_REWORKED_UPDATE_VERSION=VERSION)
    encoded = base64.b64encode(source.encode("utf-16le")).decode("ascii")
    output = reports / "installed-app-launch.log"
    with output.open("w") as log:
        result = subprocess.run(["powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded],
                                env=environment, stdout=log, stderr=subprocess.STDOUT, timeout=30)
    assert result.returncode == 0, output.read_text(encoding="utf-8-sig", errors="replace")
    assert (target / "ready").read_text().splitlines() == [token, VERSION], "startup environment must reach the application"
    verify_working_directory(target, reports / "installed-app-working-directory.json")
    print("PASS installed application literal path + working directory + inherited startup environment", flush=True)


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


def retain_diagnostics(operation: Path, destination: Path, details: dict, failure: str | None) -> None:
    """Keep only this disposable fixture's records, even when its worker failed before accepting."""
    destination.mkdir(parents=True, exist_ok=True)
    for name in ("install.log", "status", "phase", "operation.json", "staged-files.json", "accepted", "ready"):
        source = operation / name
        if source.is_file():
            shutil.copyfile(source, destination / name)
    (destination / "fixture.json").write_text(json.dumps(details, indent=2), encoding="utf-8")
    if failure:
        (destination / "failure.txt").write_text(failure, encoding="utf-8")
        print("Portable updater failure diagnostics:", destination, file=sys.stderr, flush=True)
        for name in ("phase", "status", "install.log"):
            source = destination / name
            if source.is_file():
                data = source.read_bytes()
                encoding = "utf-16" if data.startswith((b"\xff\xfe", b"\xfe\xff")) else "utf-8-sig"
                print(name + ":\n" + data.decode(encoding, errors="replace")[-24000:], file=sys.stderr, flush=True)


def scenario(workspace: Path, executable: Path, case: str, reports: Path) -> None:
    crash = case == "crash"
    rejected = case == "tampered-stage"
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
    if rejected:
        (candidate / "runtime/bin/server/jvm.dll").write_bytes(b"modified after the verified manifest")
    environment = {key: value for key, value in os.environ.items() if not key.startswith("BITCHORD_REWORKED_UPDATE_")}
    environment["BITCHORD_REWORKED_UPDATE_NO_UI"] = "1"
    parent = subprocess.Popen([str(target / "BitChordReworked.exe"), "parent"], env=environment)
    helper = None
    failure = None
    began = time.monotonic()
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
            if rejected:
                helper.wait(timeout=10)
                assert helper.returncode != 0, "a modified staged runtime must fail"
                assert not (operation / "accepted").exists(), "tampered staging must not be accepted"
                assert not backup.exists() and not failed.exists(), "a preflight rejection must leave the installed tree in place"
                assert "1.9.1-reworked.1" in (target / "app/BitChordReworked.cfg").read_text()
                assert "Staged file changed" in (operation / "status").read_text(encoding="utf-8-sig"), "rejection must report the actual checksum failure"
                print("PASS portable staged-file tampering rejected before handoff", flush=True)
                return
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
        verify_working_directory(target, reports / (case + "-working-directory.json"))
        print("PASS", "portable early-crash rollback" if crash else "portable replacement + previous backup")
    except BaseException:
        failure = traceback.format_exc()
        raise
    finally:
        if parent.poll() is None:
            parent.terminate()
            parent.wait(timeout=10)
        if helper is not None and helper.poll() is None:
            helper.terminate()
            helper.wait(timeout=10)
        retain_diagnostics(operation, reports / case, dict(
            case=case, elapsed_seconds=round(time.monotonic() - began, 3),
            parent_returncode=parent.returncode, helper_returncode=helper.returncode if helper is not None else None,
            worker_sha256=digest(HELPER), expected_version=VERSION,
        ), failure)


def main() -> None:
    if sys.platform != "win32":
        print("SKIP Windows portable fixture requires a Windows process/rename implementation")
        return
    with tempfile.TemporaryDirectory(prefix="reworked-portable-worker-test-") as raw:
        directory = Path(raw)
        reports = REPORTS / str(uuid.uuid4())
        reports.mkdir(parents=True)
        verify_worker_hashes(directory, reports)
        compiler = Path(os.environ["WINDIR"]) / "Microsoft.NET/Framework64/v4.0.30319/csc.exe"
        assert compiler.is_file(), "Windows .NET Framework C# compiler is required for this fixture"
        code = directory / "Fixture.cs"
        executable = directory / "Fixture.exe"
        code.write_text(SOURCE)
        subprocess.run([str(compiler), "/nologo", "/target:exe", "/out:" + str(executable), str(code)], check=True)
        verify_installed_app_launch(directory, executable, reports)
        scenario(directory / "success", executable, "success", reports)
        scenario(directory / "crash", executable, "crash", reports)
        scenario(directory / "tampered-stage", executable, "tampered-stage", reports)
        # Both launched replacement fixtures exit themselves; allow Windows to release PE handles.
        time.sleep(1)


if __name__ == "__main__":
    main()
