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

    $failureRoot = Join-Path $testRoot 'FailureCase'
    $failureOp = Join-Path $testRoot 'failure-operation'
    New-Item -ItemType Directory -Force -Path $failureRoot, $failureOp | Out-Null
    Populate-Root $failureRoot 'original'
    $failureZip = Join-Path $failureOp 'verified-portable.zip'
    Copy-Item -LiteralPath $zipPath -Destination $failureZip
    $code = Run-Helper $failureZip $failureRoot $failureOp -SimulateFailure
    if ($code -ne 1 -or
        (Get-Content -LiteralPath (Join-Path $failureRoot 'BitChord.exe') -Raw).Trim() -ne 'original') {
        throw 'Simulated failure did not roll back to the original portable folder'
    }
    Write-Output 'PASS: failed folder swap restored original folder'

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
    Write-Output 'PASS: ZIP traversal rejected before changing the portable folder'
} finally {
    Remove-Item -LiteralPath $testRoot -Recurse -Force -ErrorAction SilentlyContinue
}

# Negative test deliberately returned a nonzero helper exit code. Do not let
# PowerShell's inherited LASTEXITCODE make a successful CI script look failed.
exit 0
