# Fresh Reworked installer handoff. Installer UI and Windows authorization remain in control.
$ErrorActionPreference = 'Stop'
$operation = $env:BITCHORD_REWORKED_UPDATE_OPERATION
$lease = $null
$payloadLock = $null
$hasLease = $false
$accepted = $false

function File-Sha256([string]$path) {
    $hash = [Security.Cryptography.SHA256]::Create()
    $reader = $null
    try {
        $reader = [IO.File]::OpenRead($path)
        return [BitConverter]::ToString($hash.ComputeHash($reader)).Replace('-', '').ToLowerInvariant()
    } finally { if ($reader) { $reader.Dispose() }; $hash.Dispose() }
}

function Start-App([string]$dir) {
    $info = New-Object Diagnostics.ProcessStartInfo
    $info.FileName = [IO.Path]::Combine($dir, 'BitChordReworked.exe')
    $info.WorkingDirectory = $dir
    $info.UseShellExecute = $false
    return [Diagnostics.Process]::Start($info)
}

try {
    if (-not $operation -or -not [System.IO.Path]::IsPathRooted($operation)) { throw 'Missing update operation.' }
    $job = Get-Content -LiteralPath (Join-Path $operation 'operation.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($job.operation -cne $operation -or [System.IO.Path]::GetFileName($job.target) -cne 'BitChordReworked.exe') { throw 'Unexpected application identity.' }
    $target = Get-Item -LiteralPath $job.target
    $payload = Get-Item -LiteralPath $job.payload
    if (($target.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -or ($payload.Attributes -band [System.IO.FileAttributes]::ReparsePoint)) { throw 'Update paths cannot be links.' }
    $config = Join-Path $target.DirectoryName 'app\BitChordReworked.cfg'
    if (-not (Get-Content -LiteralPath $config -Raw).Contains('-Dbitchord.product=reworked')) { throw 'This installation does not identify itself as Reworked.' }
    if ($job.sha256 -notmatch '^[a-f0-9]{64}$' -or $job.oldSha256 -notmatch '^[a-f0-9]{64}$') { throw 'Invalid checksums.' }
    if ($job.token -notmatch '^[a-f0-9-]{36}$') { throw 'Invalid operation token.' }
    $identityHasher = [System.Security.Cryptography.SHA256]::Create()
    try { $identity = [BitConverter]::ToString($identityHasher.ComputeHash([Text.Encoding]::UTF8.GetBytes($target.FullName.ToLowerInvariant()))).Replace('-', '') }
    finally { $identityHasher.Dispose() }
    $lease = New-Object System.Threading.Mutex($false, ('Local\BitChordReworkedUpdate_' + $identity))
    try { $hasLease = $lease.WaitOne(0) } catch [System.Threading.AbandonedMutexException] { $hasLease = $true }
    if (-not $hasLease) { throw 'Another updater already owns this installation.' }
    $payloadLock = [IO.File]::Open($payload.FullName, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
    if ((File-Sha256 $payload.FullName) -cne $job.sha256) { throw 'Package changed after verification.' }
    if ((File-Sha256 $target.FullName) -cne $job.oldSha256) { throw 'Installed launcher changed before update.' }
    Set-Content -LiteralPath (Join-Path $operation 'status') -Value 'waiting-for-app'
    [IO.File]::WriteAllText((Join-Path $operation 'accepted'), 'accepted')
    $accepted = $true
    $deadline = [DateTime]::UtcNow.AddSeconds(120)
    while ($true) {
        $parentProcess = Get-Process -Id ([int]$job.pid) -ErrorAction SilentlyContinue
        if (-not $parentProcess) { break }
        $started = ([DateTimeOffset]$parentProcess.StartTime.ToUniversalTime()).ToUnixTimeMilliseconds()
        if ([Math]::Abs($started - [long]$job.started) -gt 1000) { break }
        if ([DateTime]::UtcNow -ge $deadline) { throw 'The app did not exit; installation was not started.' }
        Start-Sleep -Milliseconds 200
    }
    if ((File-Sha256 $target.FullName) -cne $job.oldSha256) { throw 'Installed launcher changed during handoff.' }
    Set-Content -LiteralPath (Join-Path $operation 'status') -Value 'installing'
    $installer = Start-Process -FilePath $payload.FullName -Wait -PassThru
    if ($installer.ExitCode -eq 3010 -or $installer.ExitCode -eq 1641) { throw 'Windows requires a restart to finish installation. Reopen Reworked after restarting.' }
    if ($installer.ExitCode -ne 0) { throw ('Setup did not finish successfully (exit ' + $installer.ExitCode + ').') }
    $payloadLock.Dispose(); $payloadLock = $null
    $installed = Get-Content -LiteralPath $config -Raw
    $versionPattern = '(?m)^java-options=-Dbitchord\.version=' + [Regex]::Escape([string]$job.version) + '\r?$'
    if ($installed -notmatch $versionPattern -or -not $installed.Contains('-Dbitchord.product=reworked')) { throw 'Setup did not update the original installation to the requested version. Open the selected installation manually.' }
    $env:BITCHORD_REWORKED_UPDATE_ACK = Join-Path $operation 'ready'
    $env:BITCHORD_REWORKED_UPDATE_TOKEN = [string]$job.token
    $env:BITCHORD_REWORKED_UPDATE_VERSION = [string]$job.version
    Set-Content -LiteralPath (Join-Path $operation 'status') -Value 'starting'
    $updated = Start-App $target.DirectoryName
    $deadline = [DateTime]::UtcNow.AddSeconds(75)
    while ([DateTime]::UtcNow -lt $deadline) {
        $ready = Join-Path $operation 'ready'
        if (Test-Path -LiteralPath $ready -PathType Leaf) {
            $lines = @(Get-Content -LiteralPath $ready)
            if ($lines.Count -ge 2 -and $lines[0] -ceq $job.token -and $lines[1] -ceq $job.version) {
                Set-Content -LiteralPath (Join-Path $operation 'status') -Value 'complete'
                exit 0
            }
        }
        if ($updated.HasExited) { throw 'The updated application exited before its window appeared. Reopen it manually; setup logs are preserved.' }
        Start-Sleep -Milliseconds 200
    }
    Set-Content -LiteralPath (Join-Path $operation 'status') -Value 'startup-unconfirmed'
    Write-Output 'The new application is still running. No second copy was launched.'
} catch {
    $message = $_.Exception.Message
    Write-Error -Message $message -ErrorAction Continue
    if ($operation -and (Test-Path -LiteralPath $operation -PathType Container)) {
        Set-Content -LiteralPath (Join-Path $operation 'status') -Value ('failed: ' + $message)
    }
    if ($accepted -and $env:BITCHORD_REWORKED_UPDATE_NO_UI -ne '1') {
        try {
            Add-Type -AssemblyName System.Windows.Forms
            [void][System.Windows.Forms.MessageBox]::Show(($message + "`n`nLogs: " + $operation), 'BitChord Reworked update')
        } catch { }
    }
    exit 1
} finally {
    if ($payloadLock) { $payloadLock.Dispose() }
    if ($lease) {
        if ($hasLease) { $lease.ReleaseMutex() }
        $lease.Dispose()
    }
}
