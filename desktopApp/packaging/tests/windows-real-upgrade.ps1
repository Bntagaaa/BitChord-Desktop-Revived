param(
    [Parameter(Mandatory=$true)][string]$PreviousExe,
    [Parameter(Mandatory=$true)][string]$PreviousSha256,
    [Parameter(Mandatory=$true)][string]$CurrentExe,
    [Parameter(Mandatory=$true)][string]$CurrentMsi,
    [Parameter(Mandatory=$true)][string]$ExpectedVersion
)
$ErrorActionPreference = 'Stop'
# CI runs on a clean disposable Windows user. Exercise the REAL former EXE,
# repair its disappeared %TEMP%\main.msi source from the verified EXE resource,
# then install the NEW persistent-source EXE with a live updater log.
function Get-MsiProperty([string]$File, [string]$Name) {
    $installer = New-Object -ComObject WindowsInstaller.Installer
    $database = $installer.OpenDatabase([IO.Path]::GetFullPath($File), 0)
    $view = $database.OpenView("SELECT Value FROM Property WHERE Property='$Name'")
    try {
        $null = $view.Execute()
        $record = $view.Fetch()
        if (-not $record) { throw "Required MSI property $Name missing" }
        return ([string]$record.StringData(1)).Trim()
    } finally { $null = $view.Close() }
}
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class BitChordInstalled {
    [DllImport("msi.dll", CharSet=CharSet.Unicode, EntryPoint="MsiQueryProductStateW")]
    public static extern int State(string product);
}
'@

$version = Get-MsiProperty $CurrentMsi 'ProductVersion'
$newCode = Get-MsiProperty $CurrentMsi 'ProductCode'
if ($version -ne $ExpectedVersion) { throw "Expected native version $ExpectedVersion, found $version" }
$opRoot = Join-Path $env:LOCALAPPDATA 'BitChordUpdater\operations'
New-Item -ItemType Directory -Force $opRoot | Out-Null
$operation = Join-Path $opRoot 'operation-ci-upgrade'
New-Item -ItemType Directory -Force $operation | Out-Null
$log = Join-Path $operation 'install.log'
$held = $null
$oldCode = $null
try {
    $oldSetup = Start-Process -FilePath $PreviousExe -ArgumentList '/qn /norestart' -PassThru -Wait
    if ($oldSetup.ExitCode -notin @(0,3010)) { throw "Original jpackage EXE failed to install: $($oldSetup.ExitCode)" }
    $recoveryOutput = @(& "$PSScriptRoot/../windows/recover-legacy-msi.ps1" -OriginalSetupExe $PreviousExe -ExpectedSetupSha256 $PreviousSha256)
    $recovery = ($recoveryOutput | Where-Object { $_ -like 'Recovered original BitChord MSI source:*' } | Select-Object -Last 1)
    if (-not $recovery -or $recovery -notmatch 'product=(\{[A-Fa-f0-9-]+\}) file=(.+)$') {
        throw "Source recovery did not verify an installed MSI: $($recoveryOutput -join '; ')"
    }
    $oldCode = $Matches[1]
    $source = $Matches[2]
    if ([BitChordInstalled]::State($oldCode) -ne 5) { throw 'Original EXE product was not installed.' }
    if (-not (Test-Path -LiteralPath $source)) { throw 'Recovered original MSI is missing.' }
    $legacyInstall = Join-Path $env:LOCALAPPDATA 'BitChord'
    if ($source.StartsWith($legacyInstall + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw 'MSI source was written into installation directory.'
    }
    # The helper log stays open while the MSI upgrades; outside BitChord root.
    $held = [IO.File]::Open($log, [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::Write, [IO.FileShare]::Read)
    $held.WriteByte(65); $held.Flush()
    $newSetup = Start-Process -FilePath $CurrentExe -ArgumentList '--quiet' -PassThru -Wait
    if ($newSetup.ExitCode -notin @(0,3010)) { throw "New setup EXE failed to upgrade: $($newSetup.ExitCode)" }
    if ([BitChordInstalled]::State($newCode) -ne 5) { throw 'New MSI ProductCode was not installed.' }
    if ([BitChordInstalled]::State($oldCode) -eq 5) { throw 'Old MSI ProductCode remains installed after upgrade.' }
    $checksum = (Get-FileHash -LiteralPath $CurrentMsi -Algorithm SHA256).Hash.ToLowerInvariant()
    $newCache = Join-Path $env:LOCALAPPDATA "BitChordUpdater\PackageCache\$checksum\main.msi"
    if (-not (Test-Path -LiteralPath $newCache)) { throw 'New setup failed to retain permanent source MSI.' }
    if ((Get-FileHash -LiteralPath $newCache -Algorithm SHA256).Hash.ToLowerInvariant() -ne $checksum) {
        throw 'New setup cached MSI differs from the shipped MSI.'
    }
    Write-Output "Legacy EXE -> recovered MSI -> stable-source EXE upgrade passed (native $version)"
} finally {
    if ($held) { $held.Dispose() }
    if ([BitChordInstalled]::State($newCode) -eq 5) {
        $cleanup = Start-Process msiexec.exe -ArgumentList "/x $newCode /qn /norestart" -PassThru -Wait
        Write-Output "New MSI cleanup exit code: $($cleanup.ExitCode)"
    }
    if ($oldCode -and [BitChordInstalled]::State($oldCode) -eq 5) {
        $cleanup = Start-Process msiexec.exe -ArgumentList "/x $oldCode /qn /norestart" -PassThru -Wait
        Write-Output "Old MSI cleanup exit code: $($cleanup.ExitCode)"
    }
}
