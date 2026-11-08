# Fresh portable updater. The ZIP is already verified/extracted before this worker starts.
# Directory replacement occurs only after the exact original process has exited.
$ErrorActionPreference = 'Stop'
$operation = $env:BITCHORD_REWORKED_UPDATE_OPERATION
$lease = $null
$hasLease = $false
$payloadLock = $null
$swapped = $false
$updated = $null
$job = $null
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

function Write-Phase([string]$phase) {
    Write-Output ('Portable updater: ' + $phase)
    if ($operation -and [IO.Directory]::Exists($operation)) {
        [IO.File]::WriteAllText((Join-Path $operation 'phase'), $phase)
    }
}

function Require-RegularFile([string]$path) {
    $item = Get-Item -LiteralPath $path
    if ($item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw ('Expected a regular file: ' + $path) }
    return $item
}

function Verify-StagedTree {
    $entries = Get-Content -LiteralPath (Join-Path $operation 'staged-files.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    $properties = @($entries.PSObject.Properties)
    if ($properties.Count -lt 4 -or $properties.Count -gt 100000) { throw 'The staged file manifest is incomplete.' }
    $root = [IO.Path]::GetFullPath([string]$job.stagedRoot).TrimEnd('\') + '\'
    foreach ($entry in $properties) {
        if ($entry.Name -match '(^[/\\]|:|(^|[/\\])\.\.?([/\\]|$))' -or $entry.Value -notmatch '^[a-f0-9]{64}$') { throw 'Invalid staged file record.' }
        $file = [IO.Path]::GetFullPath((Join-Path $root $entry.Name))
        if (-not $file.StartsWith($root, [StringComparison]::OrdinalIgnoreCase)) { throw 'A staged file escaped the application folder.' }
        [void](Require-RegularFile $file)
        if ((File-Sha256 $file) -cne $entry.Value) { throw ('Staged file changed: ' + $entry.Name) }
    }
    $actual = @(Get-ChildItem -LiteralPath $job.stagedRoot -File -Recurse -Force)
    if ($actual.Count -ne $properties.Count) { throw 'The staged application has unexpected files.' }
    if (@(Get-ChildItem -LiteralPath $job.stagedRoot -Recurse -Force | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }).Count -ne 0) { throw 'The staged application contains a link.' }
    $config = Get-Content -LiteralPath (Join-Path $job.stagedRoot 'app\BitChordReworked.cfg') -Raw
    $pattern = '(?m)^java-options=-Dbitchord\.version=' + [Regex]::Escape([string]$job.version) + '\r?$'
    if ($config -notmatch $pattern -or $config -notmatch '(?m)^java-options=-Dbitchord\.product=reworked\r?$') { throw 'The staged application has the wrong product or version.' }
    if ((Get-Content -LiteralPath (Join-Path $job.stagedRoot 'reworked-portable.marker') -Raw).Trim() -cne ('BitChord Desktop Reworked ' + $job.version)) { throw 'The portable marker has the wrong version.' }
    [void](Require-RegularFile (Join-Path $job.stagedRoot 'runtime\bin\server\jvm.dll'))
}

function Restore-Previous {
    if (-not $swapped) { return }
    if ($updated -and -not $updated.HasExited) { throw 'The replacement is still running; its backup has been retained.' }
    $backupLauncher = Join-Path $job.backupRoot 'BitChordReworked.exe'
    if ((File-Sha256 $backupLauncher) -cne $job.oldSha256) { throw 'The previous launcher changed; automatic rollback was stopped.' }
    if (Test-Path -LiteralPath $job.failedRoot) { throw 'The failed-update recovery path already exists.' }
    if (Test-Path -LiteralPath $job.portableRoot) { [IO.Directory]::Move($job.portableRoot, $job.failedRoot) }
    [IO.Directory]::Move($job.backupRoot, $job.portableRoot)
    $script:swapped = $false
    Remove-Item Env:BITCHORD_REWORKED_UPDATE_ACK -ErrorAction SilentlyContinue
    Remove-Item Env:BITCHORD_REWORKED_UPDATE_TOKEN -ErrorAction SilentlyContinue
    Remove-Item Env:BITCHORD_REWORKED_UPDATE_VERSION -ErrorAction SilentlyContinue
    [void](Start-App $job.portableRoot)
    Set-Content -LiteralPath (Join-Path $operation 'status') -Value 'rolled-back'
}

try {
    if (-not $operation -or -not [IO.Path]::IsPathRooted($operation)) { throw 'Missing update operation.' }
    Write-Phase 'reading-operation'
    $job = Get-Content -LiteralPath (Join-Path $operation 'operation.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($job.operation -cne $operation -or $job.token -notmatch '^[a-f0-9-]{36}$') { throw 'Invalid update operation.' }
    if ($job.sha256 -notmatch '^[a-f0-9]{64}$' -or $job.oldSha256 -notmatch '^[a-f0-9]{64}$') { throw 'Invalid checksums.' }
    Write-Phase 'checking-layout'
    $target = Require-RegularFile $job.target
    $root = Get-Item -LiteralPath $job.portableRoot
    $stage = Get-Item -LiteralPath $job.stagedRoot
    $staging = Get-Item -LiteralPath $job.stagingDirectory
    if (-not $root.PSIsContainer -or ($root.Attributes -band [IO.FileAttributes]::ReparsePoint) -or -not $stage.PSIsContainer -or ($stage.Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Portable roots cannot be links.' }
    if ($target.Name -cne 'BitChordReworked.exe' -or $target.DirectoryName -ine $root.FullName) { throw 'Unexpected portable launcher identity.' }
    if ($stage.Name -cne 'BitChordReworked' -or $stage.Parent.FullName -ine $staging.FullName -or -not $staging.Name.StartsWith('.BitChordReworked-next-')) { throw 'Unexpected staging layout.' }
    if ($root.Parent.FullName -ine $staging.Parent.FullName -or ([IO.DirectoryInfo]$job.backupRoot).Parent.FullName -ine $root.Parent.FullName -or ([IO.DirectoryInfo]$job.failedRoot).Parent.FullName -ine $root.Parent.FullName) { throw 'Portable replacement must stay on the same parent volume.' }
    if ([IO.Path]::GetFileName([string]$job.backupRoot) -cne ('.BitChordReworked-previous-' + $job.token) -or [IO.Path]::GetFileName([string]$job.failedRoot) -cne ('.BitChordReworked-failed-' + $job.token)) { throw 'Unexpected recovery path.' }
    if ((Test-Path -LiteralPath $job.backupRoot) -or (Test-Path -LiteralPath $job.failedRoot)) { throw 'A recovery directory already exists.' }
    [void](Require-RegularFile (Join-Path $root.FullName 'reworked-portable.marker'))
    $config = Get-Content -LiteralPath (Join-Path $root.FullName 'app\BitChordReworked.cfg') -Raw
    if ($config -notmatch '(?m)^java-options=-Dbitchord\.product=reworked\r?$') { throw 'The old application does not identify itself as Reworked.' }
    Write-Phase 'taking-installation-lock'
    $identityHasher = [Security.Cryptography.SHA256]::Create()
    try { $identity = [BitConverter]::ToString($identityHasher.ComputeHash([Text.Encoding]::UTF8.GetBytes($target.FullName.ToLowerInvariant()))).Replace('-', '') }
    finally { $identityHasher.Dispose() }
    $lease = New-Object System.Threading.Mutex($false, ('Local\BitChordReworkedUpdate_' + $identity))
    try { $hasLease = $lease.WaitOne(0) } catch [System.Threading.AbandonedMutexException] { $hasLease = $true }
    if (-not $hasLease) { throw 'Another updater already owns this installation.' }
    Write-Phase 'checking-archive'
    $payload = Require-RegularFile $job.payload
    $payloadLock = [IO.File]::Open($payload.FullName, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
    if ((File-Sha256 $payload.FullName) -cne $job.sha256) { throw 'The portable archive changed after verification.' }
    Write-Phase 'checking-original-launcher'
    if ((File-Sha256 $target.FullName) -cne $job.oldSha256) { throw 'The running launcher changed before update.' }
    Write-Phase 'checking-staged-tree'
    Verify-StagedTree
    Write-Phase 'waiting-for-app'
    Set-Content -LiteralPath (Join-Path $operation 'status') -Value 'waiting-for-app'
    [IO.File]::WriteAllText((Join-Path $operation 'accepted'), 'accepted')
    $accepted = $true
    $deadline = [DateTime]::UtcNow.AddSeconds(120)
    while ($true) {
        $parentProcess = Get-Process -Id ([int]$job.pid) -ErrorAction SilentlyContinue
        if (-not $parentProcess) { break }
        $started = ([DateTimeOffset]$parentProcess.StartTime.ToUniversalTime()).ToUnixTimeMilliseconds()
        if ([Math]::Abs($started - [long]$job.started) -gt 1000) { break }
        if ([DateTime]::UtcNow -ge $deadline) { throw 'The app did not exit; the portable folder has not been changed.' }
        Start-Sleep -Milliseconds 200
    }
    Write-Phase 'verifying-handoff'
    if ((File-Sha256 $target.FullName) -cne $job.oldSha256) { throw 'The installed launcher changed during handoff.' }
    Verify-StagedTree
    Write-Phase 'replacing'
    Set-Content -LiteralPath (Join-Path $operation 'status') -Value 'replacing'
    [IO.Directory]::Move($job.portableRoot, $job.backupRoot)
    try { [IO.Directory]::Move($job.stagedRoot, $job.portableRoot) }
    catch { [IO.Directory]::Move($job.backupRoot, $job.portableRoot); throw }
    $swapped = $true
    $payloadLock.Dispose(); $payloadLock = $null
    $env:BITCHORD_REWORKED_UPDATE_ACK = Join-Path $operation 'ready'
    $env:BITCHORD_REWORKED_UPDATE_TOKEN = [string]$job.token
    $env:BITCHORD_REWORKED_UPDATE_VERSION = [string]$job.version
    Set-Content -LiteralPath (Join-Path $operation 'status') -Value 'starting'
    Write-Phase 'starting'
    $updated = Start-App $job.portableRoot
    $deadline = [DateTime]::UtcNow.AddSeconds(75)
    while ([DateTime]::UtcNow -lt $deadline) {
        $ready = Join-Path $operation 'ready'
        if (Test-Path -LiteralPath $ready -PathType Leaf) {
            $lines = @(Get-Content -LiteralPath $ready)
            if ($lines.Count -ge 2 -and $lines[0] -ceq $job.token -and $lines[1] -ceq $job.version) {
                Set-Content -LiteralPath (Join-Path $operation 'status') -Value 'complete'
                Write-Phase 'complete'
                Write-Output ('Previous portable version retained at ' + $job.backupRoot)
                exit 0
            }
        }
        if ($updated.HasExited) {
            Restore-Previous
            Write-Output 'The replacement exited before its window appeared. The previous version was restored and reopened.'
            exit 1
        }
        Start-Sleep -Milliseconds 200
    }
    Set-Content -LiteralPath (Join-Path $operation 'status') -Value 'startup-unconfirmed'
    Write-Output ('The replacement is still running; no second process was started. Backup: ' + $job.backupRoot)
} catch {
    $message = $_.Exception.Message
    if ($swapped -and (-not $updated -or $updated.HasExited)) {
        try { Restore-Previous } catch { $message += (' Rollback: ' + $_.Exception.Message) }
    }
    Write-Error -Message $message -ErrorAction Continue
    if ($operation -and (Test-Path -LiteralPath $operation -PathType Container)) {
        $status = Join-Path $operation 'status'
        if (-not (Test-Path -LiteralPath $status) -or (Get-Content -LiteralPath $status -Raw).Trim() -cne 'rolled-back') { Set-Content -LiteralPath $status -Value ('failed: ' + $message) }
    }
    # The running app reports preflight errors; only handoff failures need a dialog.
    if ($accepted -and $env:BITCHORD_REWORKED_UPDATE_NO_UI -ne '1') {
        try { Add-Type -AssemblyName System.Windows.Forms; [void][Windows.Forms.MessageBox]::Show(($message + "`n`nLogs: " + $operation), 'BitChord Reworked portable update') } catch { }
    }
    exit 1
} finally {
    if ($payloadLock) { $payloadLock.Dispose() }
    if ($lease) { if ($hasLease) { $lease.ReleaseMutex() }; $lease.Dispose() }
}
