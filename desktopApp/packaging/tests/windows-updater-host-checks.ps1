$ErrorActionPreference = 'Stop'
# Run only on a disposable Windows runner. These fixtures exercise the REAL
# host and workers, with a small native EXE instead of a full BitChord/MSI.
# Separate real-MSI integration tests remain required before release.
$updaters = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../src/main/resources/updater'))
$hostScript = Join-Path $updaters 'windows-updater-host.ps1'
foreach ($file in @($hostScript, (Join-Path $updaters 'windows-update.ps1'), (Join-Path $updaters 'windows-portable-update.ps1'))) {
    $tokens = $null; $errors = $null
    [Management.Automation.Language.Parser]::ParseFile($file, [ref]$tokens, [ref]$errors) > $null
    if ($errors.Count) { throw "Parser rejected $file : $($errors | Out-String)" }
}
$root = Join-Path $env:RUNNER_TEMP ('BitChord host tests ' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $root | Out-Null
$source = Join-Path $root 'Fixture.cs'
@'
using System;
using System.IO;
using System.Diagnostics;
using System.Threading;
class Fixture {
    static int Main() {
        string exe = Process.GetCurrentProcess().MainModule.FileName;
        string directory = Path.GetDirectoryName(exe);
        if (Path.GetFileName(exe) == "verified-setup.exe") {
            string[] request = File.ReadAllLines(Path.Combine(directory, "fixture.ini"));
            int code = Int32.Parse(request[2]);
            if ((code == 0 || code == 3010) && request[3] != "unchanged")
                File.WriteAllText(Path.Combine(request[0], "app", "BitChord.cfg"),
                    "[JavaOptions]\njava-options=-Dbitchord.version=" + request[1] + "\n");
            return code;
        }
        string countFile = Path.Combine(directory, "launch.count");
        int count = File.Exists(countFile) ? Int32.Parse(File.ReadAllText(countFile)) : 0;
        File.WriteAllText(countFile, (count + 1).ToString());
        string ready = Environment.GetEnvironmentVariable("BITCHORD_UPDATE_READY_FILE");
        if (!String.IsNullOrEmpty(ready) && !File.Exists(Path.Combine(directory, "no-ack"))) {
            Process self = Process.GetCurrentProcess();
            long started = (long)(self.StartTime.ToUniversalTime() - new DateTime(1970,1,1,0,0,0,DateTimeKind.Utc)).TotalMilliseconds;
            string text = "token=" + Environment.GetEnvironmentVariable("BITCHORD_UPDATE_TOKEN") +
                "\nversion=" + Environment.GetEnvironmentVariable("BITCHORD_UPDATE_EXPECTED_VERSION") +
                "\nstate=gui-ready\npid=" + self.Id + "\nstart_millis=" + started + "\nlauncher_path=" + exe + "\n";
            File.WriteAllText(ready + ".tmp", text);
            File.Move(ready + ".tmp", ready);
        }
        Thread.Sleep(6000);
        return 0;
    }
}
'@ | Set-Content -LiteralPath $source -Encoding utf8
$csc = Join-Path $env:SystemRoot 'Microsoft.NET/Framework64/v4.0.30319/csc.exe'
$fixture = Join-Path $root 'Fixture.exe'
& $csc /nologo /target:winexe "/out:$fixture" $source
if ($LASTEXITCODE -ne 0) { throw 'Fixture compilation failed' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$powershell = Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/powershell.exe'
function Quote([string]$value) { return '"' + $value + '"' }
function Populate([string]$directory, [string]$version, [switch]$Portable) {
    New-Item -ItemType Directory -Force -Path (Join-Path $directory 'app') | Out-Null
    Copy-Item -LiteralPath $fixture -Destination (Join-Path $directory 'BitChord.exe')
    [IO.File]::WriteAllText((Join-Path $directory 'app/BitChord.cfg'), "[JavaOptions]`njava-options=-Dbitchord.version=$version`n")
    if ($Portable) { [IO.File]::WriteAllText((Join-Path $directory '.bitchord-portable'), '') }
}
function Check-Case([string]$name, [string]$mode, [int]$expectedCode, [string]$expectedStatus,
    [int]$installerExit = 0, [switch]$Unchanged, [switch]$NoAck, [switch]$BadHash, [switch]$HoldParent) {
    $case = Join-Path $root $name
    $app = Join-Path $case 'Application with spaces'
    $op = Join-Path $case 'operation-host'
    $payload = Join-Path $op 'payload'
    New-Item -ItemType Directory -Force -Path $payload | Out-Null
    Populate $app '1.8.7' -Portable:($mode -eq 'portable')
    $target = '1.9.1'
    if ($mode -eq 'portable') {
        $bundle = Join-Path $case 'archive'
        $newApp = Join-Path $bundle 'nested/build/BitChord'
        Populate $newApp $target -Portable
        if ($NoAck) { [IO.File]::WriteAllText((Join-Path $newApp 'no-ack'), '') }
        $package = Join-Path $payload 'verified-portable.zip'
        [IO.Compression.ZipFile]::CreateFromDirectory($bundle, $package)
        Copy-Item (Join-Path $updaters 'windows-portable-update.ps1') (Join-Path $payload 'worker.ps1')
    } else {
        $package = Join-Path $payload 'verified-setup.exe'
        Copy-Item $fixture $package
        @($app, $target, [string]$installerExit, $(if ($Unchanged) { 'unchanged' } else { 'update' })) | Set-Content (Join-Path $payload 'fixture.ini')
        Copy-Item (Join-Path $updaters 'windows-update.ps1') (Join-Path $payload 'worker.ps1')
        if ($NoAck) { [IO.File]::WriteAllText((Join-Path $app 'no-ack'), '') }
    }
    $parent = Start-Process $powershell -ArgumentList '-NoProfile -NonInteractive -Command "Start-Sleep -Seconds 45"' -WindowStyle Hidden -PassThru
    $launcher = Start-Process $powershell -ArgumentList '-NoProfile -NonInteractive -Command "Start-Sleep -Seconds 45"' -WindowStyle Hidden -PassThru
    $hash = (Get-FileHash $package -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($BadHash) { $hash = '0' * 64 }
    $request = Join-Path $op 'request.json'
    @{
        schema = 1; mode = $mode; application = (Join-Path $app 'BitChord.exe'); version = $target
        expected = $hash; token = [guid]::NewGuid().ToString('N')
        workerSha256 = (Get-FileHash (Join-Path $payload 'worker.ps1') -Algorithm SHA256).Hash.ToLowerInvariant()
        parentPid = $parent.Id; parentStartMillis = ([DateTimeOffset]$parent.StartTime.ToUniversalTime()).ToUnixTimeMilliseconds()
        launcherPid = $launcher.Id; launcherStartMillis = ([DateTimeOffset]$launcher.StartTime.ToUniversalTime()).ToUnixTimeMilliseconds()
    } | ConvertTo-Json | Set-Content -LiteralPath $request -Encoding utf8
    $wait = if ($HoldParent) { 1 } else { 20 }
    $args = @('-NoProfile', '-NonInteractive', '-STA', '-ExecutionPolicy', 'Bypass', '-File', $hostScript,
        '-Request', $request, '-Headless', '-WaitSeconds', [string]$wait, '-ReadySeconds', '3')
    $process = $null
    try {
        $process = Start-Process $powershell -ArgumentList (($args | ForEach-Object { Quote $_ }) -join ' ') -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $op 'host.log') -RedirectStandardError (Join-Path $op 'host-error.log')
        $deadline = [DateTime]::UtcNow.AddSeconds(15)
        while (-not (Test-Path (Join-Path $op 'armed')) -and -not $process.HasExited -and [DateTime]::UtcNow -lt $deadline) { Start-Sleep -Milliseconds 50; $process.Refresh() }
        if (-not $BadHash -and -not (Test-Path (Join-Path $op 'armed'))) { throw "Host did not arm: $name" }
        if (-not $HoldParent -and -not $BadHash) {
            Stop-Process -Id $parent.Id -ErrorAction SilentlyContinue
            $parent.WaitForExit()
            Start-Sleep -Milliseconds 300
            if (Test-Path (Join-Path $app 'launch.count')) { throw 'Host relaunched before native launcher exited' }
            if (Test-Path (Join-Path $payload 'armed')) { throw 'Worker ran before both old processes exited' }
            Stop-Process -Id $launcher.Id -ErrorAction SilentlyContinue
            $launcher.WaitForExit()
        }
        if (-not $process.WaitForExit(45000)) { throw "Host timeout: $name" }
        $status = (Get-Content -LiteralPath (Join-Path $op 'status') -Raw).Trim()
        if ($process.ExitCode -ne $expectedCode -or $status -notlike $expectedStatus) {
            $log = Get-Content (Join-Path $op 'host.log') -Raw
            $err = Get-Content (Join-Path $op 'host-error.log') -Raw
            throw "Case $name returned $($process.ExitCode) / $status; expected $expectedCode / $expectedStatus. $log $err"
        }
        $countFile = Join-Path $app 'launch.count'
        $shouldLaunch = $expectedStatus -like 'success*' -or $expectedStatus -like 'relaunch-requested*'
        if ($shouldLaunch) {
            if (-not (Test-Path $countFile) -or (Get-Content $countFile -Raw).Trim() -ne '1') { throw "Expected exactly one relaunch: $name" }
            # Re-running the same operation must never create a second instance.
            & $powershell @args | Out-Host
            if ((Get-Content $countFile -Raw).Trim() -ne '1') { throw 'Duplicate host caused a second relaunch' }
        } elseif (Test-Path $countFile) { throw "Failure/cancel/reboot case relaunched the app: $name" }
        if (Test-Path -LiteralPath $package) { throw "Large staged payload leaked: $name" }
        if (-not (Test-Path (Join-Path $op 'completed'))) { throw 'Host completion marker missing' }
        Write-Output "PASS $name : $status"
    } finally {
        foreach ($p in @($parent, $launcher, $process)) {
            if ($p -and -not $p.HasExited) { Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue }
        }
        Get-Process -Name 'BitChord' -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq (Join-Path $app 'BitChord.exe') } | Stop-Process -Force -ErrorAction SilentlyContinue
    }
}
try {
    Check-Case 'portable-success' 'portable' 0 'success-gui-confirmed'
    Check-Case 'setup-success' 'setup' 0 'success-gui-confirmed'
    Check-Case 'setup-cancel' 'setup' 1 'windows-updater-failed' -installerExit 1602
    Check-Case 'setup-reboot' 'setup' 2 'reboot-required*' -installerExit 3010
    Check-Case 'wrong-version' 'setup' 1 'windows-updater-failed' -Unchanged
    Check-Case 'checksum-failure' 'portable' 1 'windows-updater-failed' -BadHash
    Check-Case 'shutdown-timeout' 'portable' 1 'windows-updater-failed' -HoldParent
    Check-Case 'startup-unconfirmed' 'portable' 2 'relaunch-requested-unconfirmed*' -NoAck
    Write-Output 'All standalone Windows updater handoff/relaunch cases passed.'
} finally {
    # Preserve logs for artifact upload, not the copied fixture application data.
    $diagnostics = Join-Path $env:RUNNER_TEMP 'bitchord-windows-host-results'
    New-Item -ItemType Directory -Force -Path $diagnostics | Out-Null
    Get-ChildItem $root -Filter 'operation-host' -Directory -Recurse | ForEach-Object {
        $dest = Join-Path $diagnostics $_.Parent.Name
        New-Item -ItemType Directory -Force -Path $dest | Out-Null
        Get-ChildItem $_.FullName -File | Where-Object { $_.Name -match '\.log$|status$|completed$|failure.detail$' } | Copy-Item -Destination $dest
    }
    Remove-Item -LiteralPath $root -Recurse -Force -ErrorAction SilentlyContinue
}
exit 0
