$ErrorActionPreference = 'Stop'
# Exercise the real native updater window (not Headless). A bad checksum
# deliberately stops before any application or installer is executed.
$hostFile = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../src/main/resources/updater/windows-updater-host.ps1'))
$workerFile = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../src/main/resources/updater/windows-portable-update.ps1'))
$root = Join-Path $env:RUNNER_TEMP ('BitChord updater UI ' + [guid]::NewGuid().ToString('N'))
$app = Join-Path $root 'app'
$op = Join-Path $root 'operation-window'
$payload = Join-Path $op 'payload'
New-Item -ItemType Directory -Force -Path (Join-Path $app 'app'), $payload | Out-Null
[IO.File]::WriteAllText((Join-Path $app 'BitChord.exe'), 'not executed')
[IO.File]::WriteAllText((Join-Path $app '.bitchord-portable'), '')
[IO.File]::WriteAllText((Join-Path $app 'app/BitChord.cfg'), 'java-options=-Dbitchord.version=1.8.7')
$package = Join-Path $payload 'verified-portable.zip'
[IO.File]::WriteAllText($package, 'invalid payload; must never install')
Copy-Item -LiteralPath $workerFile -Destination (Join-Path $payload 'worker.ps1')
$self = Get-Process -Id $PID
$request = Join-Path $op 'request.json'
@{
    schema = 1; mode = 'portable'; application = (Join-Path $app 'BitChord.exe'); version = '1.9.1'
    expected = '0' * 64; token = [guid]::NewGuid().ToString('N')
    workerSha256 = (Get-FileHash $workerFile -Algorithm SHA256).Hash.ToLowerInvariant()
    parentPid = $PID; parentStartMillis = ([DateTimeOffset]$self.StartTime.ToUniversalTime()).ToUnixTimeMilliseconds()
    launcherPid = 0; launcherStartMillis = 0
} | ConvertTo-Json | Set-Content -LiteralPath $request -Encoding utf8
$exe = Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/powershell.exe'
$process = $null
try {
    $args = @('-NoProfile', '-NonInteractive', '-STA', '-ExecutionPolicy', 'Bypass', '-File', ('"' + $hostFile + '"'), '-Request', ('"' + $request + '"'))
    $process = Start-Process -FilePath $exe -ArgumentList $args -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $op 'host.log') -RedirectStandardError (Join-Path $op 'host-error.log')
    $deadline = [DateTime]::UtcNow.AddSeconds(20)
    $visible = $false
    while ([DateTime]::UtcNow -lt $deadline -and -not $process.HasExited) {
        $process.Refresh()
        if ($process.MainWindowHandle -ne [IntPtr]::Zero -and $process.MainWindowTitle -eq 'BitChord Updater' -and
            (Test-Path -LiteralPath (Join-Path $op 'completed'))) { $visible = $true; break }
        Start-Sleep -Milliseconds 100
    }
    if (-not $visible) { throw 'The native BitChord Updater failure window was not visible' }
    if (Test-Path -LiteralPath (Join-Path $op 'armed')) { throw 'UI fixture authorized app shutdown despite bad checksum' }
    if ((Get-Content -LiteralPath (Join-Path $op 'status') -Raw).Trim() -ne 'windows-updater-failed') { throw 'Failure was not displayed/persisted' }
    if (Test-Path -LiteralPath $package) { throw 'Bad payload was retained after terminal failure' }
    if (-not $process.CloseMainWindow()) { throw 'Updater window did not accept Close after terminal failure' }
    if (-not $process.WaitForExit(10000)) { throw 'Updater GUI did not terminate after Close' }
    if ($process.ExitCode -ne 1) { throw "Unexpected GUI failure exit code: $($process.ExitCode)" }
    if ((Get-Content -LiteralPath (Join-Path $app 'BitChord.exe') -Raw) -ne 'not executed') { throw 'UI test modified the original launcher' }
    Write-Output 'PASS: separate BitChord Updater native window stays visible on failure, closes normally, and never starts the app on invalid package.'
} catch {
    foreach ($name in @('host.log', 'host-error.log', 'failure.detail')) {
        $file = Join-Path $op $name
        if (Test-Path -LiteralPath $file) { Write-Host "$name :"; Get-Content -LiteralPath $file | Out-Host }
    }
    throw
} finally {
    if ($process -and -not $process.HasExited) { Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue }
    $diagnostics = Join-Path $env:RUNNER_TEMP 'bitchord-windows-host-results/native-window'
    New-Item -ItemType Directory -Force -Path $diagnostics | Out-Null
    Get-ChildItem -LiteralPath $op -File | Where-Object { $_.Name -match '\.log$|status$|completed$|failure.detail$' } | Copy-Item -Destination $diagnostics
    Remove-Item -LiteralPath $root -Recurse -Force -ErrorAction SilentlyContinue
}
exit 0
