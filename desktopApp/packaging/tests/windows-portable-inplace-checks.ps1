param([string]$RealArchive = '')
$ErrorActionPreference = 'Stop'
# Real filesystem integration test on Windows, using a synthetic package with
# the same BitChord/ launcher, marker and app-config layout as the release ZIP.
# No actual BitChord process is installed or launched.
$helper = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../src/main/resources/updater/windows-portable-update.ps1'))
$tokens = $null
$parseErrors = $null
[Management.Automation.Language.Parser]::ParseFile($helper, [ref]$tokens, [ref]$parseErrors) > $null
if ($parseErrors.Count) { throw "Portable helper PowerShell syntax error: $($parseErrors | Out-String)" }
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$testRoot = Join-Path $env:RUNNER_TEMP ("bitchord-inplace-" + [guid]::NewGuid().ToString('N'))
$portable = Join-Path $testRoot 'PortableBitChord'
$operation = Join-Path $testRoot 'operations'
$source = Join-Path $testRoot 'bundle'
$packageDirectory = Join-Path $source 'BitChord'
$zipPath = Join-Path $operation 'verified-portable.zip'
New-Item -ItemType Directory -Force -Path $portable, $operation, $packageDirectory | Out-Null
function Populate-Root([string]$root, [string]$version) {
    New-Item -ItemType Directory -Force -Path (Join-Path $root 'app') | Out-Null
    Set-Content -LiteralPath (Join-Path $root 'BitChord.exe') -Value $version
    Set-Content -LiteralPath (Join-Path $root '.bitchord-portable') -Value 'portable'
    Set-Content -LiteralPath (Join-Path $root 'app/BitChord.cfg') -Value "version=$version"
}
function Run-Helper([string]$archive, [string]$target, [string]$op, [switch]$SimulateFailure) {
    $child = Start-Process -FilePath (Join-Path $PSHOME 'pwsh.exe') -ArgumentList '-NoProfile -Command "Start-Sleep -Seconds 2"' -PassThru
    $start = ([DateTimeOffset]$child.StartTime.ToUniversalTime()).ToUnixTimeMilliseconds()
    $hash = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash
    $args = @('-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass',
        '-File', $helper, '-ParentPid', [string]$child.Id,
        '-ParentStartMillis', [string]$start, '-Package', $archive,
        '-Expected', $hash, '-Operation', $op, '-PortableRoot', $target,
        '-WaitSeconds', '20', '-SkipLaunch')
    if ($SimulateFailure) { $args += '-SimulateSwapFailure' }
    & (Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/powershell.exe') @args | Out-Host
    return [int]$LASTEXITCODE
}
try {
    Populate-Root $portable 'old'
    Set-Content -LiteralPath (Join-Path $portable 'personal-notes.txt') -Value 'keep-user-files'
    Populate-Root $packageDirectory 'new'
    [IO.Compression.ZipFile]::CreateFromDirectory($source, $zipPath)
    $code = Run-Helper $zipPath $portable $operation
    if ($code -ne 0) { throw "Portable in-place helper failed with exit $code" }
    if (Test-Path -LiteralPath $zipPath) { throw 'Verified ZIP remained in completed updater operation' }
    if ((Get-Content -LiteralPath (Join-Path $portable 'BitChord.exe') -Raw).Trim() -ne 'new') {
        throw 'New portable executable was not installed at original folder path'
    }
    $backupFile = Join-Path $operation 'backup.path'
    if (-not (Test-Path -LiteralPath $backupFile)) { throw 'Backup path was not recorded' }
    $backup = (Get-Content -LiteralPath $backupFile -Raw).Trim()
    if (-not (Test-Path -LiteralPath $backup) -or
        (Get-Content -LiteralPath (Join-Path $backup 'BitChord.exe') -Raw).Trim() -ne 'old' -or
        (Get-Content -LiteralPath (Join-Path $backup 'personal-notes.txt') -Raw).Trim() -ne 'keep-user-files') {
        throw 'Old application directory and user-added files were not preserved in backup'
    }
    Write-Output "PASS: same portable path replaced, rollback folder retained: $backup"

    # An open file handle with no FILE_SHARE_DELETE is representative of an
    # antivirus/Explorer/native child transiently locking portable binaries.
    $lockedRoot = Join-Path $testRoot 'LockedCase'
    $lockedOp = Join-Path $testRoot 'locked-operation'
    New-Item -ItemType Directory -Force -Path $lockedRoot, $lockedOp | Out-Null
    Populate-Root $lockedRoot 'locked-old'
    $lockedZip = Join-Path $lockedOp 'verified-portable.zip'
    [IO.Compression.ZipFile]::CreateFromDirectory($source, $lockedZip)
    $lockReady = Join-Path $testRoot 'lock-ready.txt'
    $lockJob = Start-Job -ScriptBlock {
        param($oldFile, $marker)
        $hold = [IO.File]::Open($oldFile, [IO.FileMode]::Open,
            [IO.FileAccess]::Read, [IO.FileShare]::Read)
        try {
            Set-Content -LiteralPath $marker -Value 'locked'
            Start-Sleep -Seconds 7
        } finally { $hold.Dispose() }
    } -ArgumentList (Join-Path $lockedRoot 'BitChord.exe'), $lockReady
    try {
        $started = [DateTime]::UtcNow
        while (-not (Test-Path -LiteralPath $lockReady)) {
            if ($lockJob.State -eq 'Failed') { throw 'Fixture failed to hold executable lock' }
            if (([DateTime]::UtcNow - $started).TotalSeconds -gt 10) { throw 'Lock fixture did not start' }
            Start-Sleep -Milliseconds 100
        }
        $code = Run-Helper $lockedZip $lockedRoot $lockedOp
        if ($code -ne 0 -or
            (Get-Content -LiteralPath (Join-Path $lockedRoot 'BitChord.exe') -Raw).Trim() -ne 'new') {
            throw 'Portable in-place update failed to retry a transient file lock'
        }
        if (Test-Path -LiteralPath $lockedZip) { throw 'Locked case retained staged ZIP' }
        Write-Output 'PASS: transient Win32 file lock cleared, update completed in original folder'
    } finally {
        $null = $lockJob | Wait-Job -Timeout 15
        $lockJob | Remove-Job -Force -ErrorAction SilentlyContinue
    }

    # Historical release workflow archives the whole Gradle path rather than
    # a single BitChord/ directory. The helper must discover the launcher.
    $nestedSource = Join-Path $testRoot 'nested-bundle'
    $nestedApp = Join-Path $nestedSource 'desktopApp/build/compose/binaries/main/app/BitChord'
    Populate-Root $nestedApp 'nested-new'
    $nestedRoot = Join-Path $testRoot 'NestedCase'
    $nestedOp = Join-Path $testRoot 'nested-operation'
    New-Item -ItemType Directory -Force -Path $nestedRoot, $nestedOp | Out-Null
    Populate-Root $nestedRoot 'nested-old'
    $nestedZip = Join-Path $nestedOp 'verified-portable.zip'
    [IO.Compression.ZipFile]::CreateFromDirectory($nestedSource, $nestedZip)
    $code = Run-Helper $nestedZip $nestedRoot $nestedOp
    if ($code -ne 0 -or
        (Get-Content -LiteralPath (Join-Path $nestedRoot 'BitChord.exe') -Raw).Trim() -ne 'nested-new') {
        throw 'Nested Gradle path portable ZIP was not installed in original directory'
    }
    Write-Output 'PASS: nested Gradle-path archive root detected from BitChord.exe'

    # Ambiguous executables must never select a random directory.
    $ambiguousSource = Join-Path $testRoot 'ambiguous-bundle'
    Populate-Root (Join-Path $ambiguousSource 'first') 'first-new'
    Populate-Root (Join-Path $ambiguousSource 'second') 'second-new'
    $ambiguousRoot = Join-Path $testRoot 'AmbiguousCase'
    $ambiguousOp = Join-Path $testRoot 'ambiguous-operation'
    New-Item -ItemType Directory -Force -Path $ambiguousRoot, $ambiguousOp | Out-Null
    Populate-Root $ambiguousRoot 'unchanged'
    $ambiguousZip = Join-Path $ambiguousOp 'verified-portable.zip'
    [IO.Compression.ZipFile]::CreateFromDirectory($ambiguousSource, $ambiguousZip)
    $code = Run-Helper $ambiguousZip $ambiguousRoot $ambiguousOp
    if ($code -eq 0 -or
        (Get-Content -LiteralPath (Join-Path $ambiguousRoot 'BitChord.exe') -Raw).Trim() -ne 'unchanged') {
        throw 'Ambiguous archive was installed instead of rejected'
    }
    Write-Output 'PASS: ambiguous executable roots rejected'

    $failureRoot = Join-Path $testRoot 'FailureCase'
    $failureOp = Join-Path $testRoot 'failure-operation'
    New-Item -ItemType Directory -Force -Path $failureRoot, $failureOp | Out-Null
    Populate-Root $failureRoot 'original'
    $failureZip = Join-Path $failureOp 'verified-portable.zip'
    [IO.Compression.ZipFile]::CreateFromDirectory($source, $failureZip)
    $code = Run-Helper $failureZip $failureRoot $failureOp -SimulateFailure
    if ($code -ne 1 -or
        (Get-Content -LiteralPath (Join-Path $failureRoot 'BitChord.exe') -Raw).Trim() -ne 'original') {
        throw 'Simulated failure did not roll back to the original portable folder'
    }
    if (Test-Path -LiteralPath $failureZip) { throw 'Verified ZIP remained after failed swap' }
    Write-Output 'PASS: failed folder swap restored original folder and cleaned staged ZIP'

    $unsafeRoot = Join-Path $testRoot 'UnsafeCase'
    $unsafeOp = Join-Path $testRoot 'unsafe-operation'
    New-Item -ItemType Directory -Force -Path $unsafeRoot, $unsafeOp | Out-Null
    Populate-Root $unsafeRoot 'safe-old'
    $unsafeZip = Join-Path $unsafeOp 'verified-portable.zip'
    $archive = [IO.Compression.ZipFile]::Open($unsafeZip, [IO.Compression.ZipArchiveMode]::Create)
    try {
        [void]$archive.CreateEntry('BitChord/../escape.txt')
    } finally { $archive.Dispose() }
    $code = Run-Helper $unsafeZip $unsafeRoot $unsafeOp
    if ($code -ne 1 -or
        (Get-Content -LiteralPath (Join-Path $unsafeRoot 'BitChord.exe') -Raw).Trim() -ne 'safe-old' -or
        (Test-Path -LiteralPath (Join-Path $testRoot 'escape.txt'))) {
        throw 'Unsafe ZIP path affected existing portable folder'
    }
    if (Test-Path -LiteralPath $unsafeZip) { throw 'Unsafe ZIP remained in completed operation' }
    Write-Output 'PASS: ZIP traversal rejected without touching original and staged ZIP cleaned'

    if ($RealArchive) {
        $realPackage = [IO.Path]::GetFullPath($RealArchive)
        if (-not (Test-Path -LiteralPath $realPackage -PathType Leaf)) {
            throw "Built portable ZIP does not exist: $realPackage"
        }
        $realRoot = Join-Path $testRoot 'RealPortableCase'
        $realOp = Join-Path $testRoot 'real-operation'
        New-Item -ItemType Directory -Force -Path $realRoot, $realOp | Out-Null
        Populate-Root $realRoot 'old-real'
        $realZip = Join-Path $realOp 'verified-portable.zip'
        Copy-Item -LiteralPath $realPackage -Destination $realZip
        $code = Run-Helper $realZip $realRoot $realOp
        if ($code -ne 0) {
            $diagnostic = Get-Content -LiteralPath (Join-Path $realOp 'status') -Raw -ErrorAction SilentlyContinue
            throw "Real packaged portable ZIP failed in-place swap (exit $code, status=$diagnostic)"
        }
        foreach ($required in @('BitChord.exe', '.bitchord-portable', 'app/BitChord.cfg')) {
            if (-not (Test-Path -LiteralPath (Join-Path $realRoot $required) -PathType Leaf)) {
                throw "Real packaged portable update omitted $required"
            }
        }
        if (Test-Path -LiteralPath $realZip) { throw 'Real packaged ZIP not removed from finished staging' }
        Write-Output 'PASS: actual build portable ZIP installed at same directory and verified'
    }
} finally {
    Remove-Item -LiteralPath $testRoot -Recurse -Force -ErrorAction SilentlyContinue
}

# Negative test deliberately returned a nonzero helper exit code. Do not let
# PowerShell's inherited LASTEXITCODE make a successful CI script look failed.
exit 0
