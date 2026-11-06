param(
    [Parameter(Mandatory=$true)][string]$PreviousExe,
    [Parameter(Mandatory=$true)][string]$PreviousSha256,
    [Parameter(Mandatory=$true)][string]$CurrentExe,
    [Parameter(Mandatory=$true)][string]$CurrentMsi,
    [Parameter(Mandatory=$true)][string]$ExpectedVersion
)
$ErrorActionPreference = 'Stop'

function Get-MsiProperty([string]$file, [string]$name) {
    $installer = New-Object -ComObject WindowsInstaller.Installer
    $database = $installer.OpenDatabase([IO.Path]::GetFullPath($file), 0)
    $view = $database.OpenView("SELECT Value FROM Property WHERE Property='$name'")
    try {
        $null = $view.Execute()
        $record = $view.Fetch()
        if (-not $record) { throw "MSI property $name is missing" }
        return ([string]$record.StringData(1)).Trim()
    } finally { $null = $view.Close() }
}
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class BitChordInstallStateTest {
    [DllImport("msi.dll", CharSet=CharSet.Unicode, EntryPoint="MsiQueryProductStateW")]
    public static extern int Get(string product);
}
'@

$native = Get-MsiProperty $CurrentMsi 'ProductVersion'
$newCode = Get-MsiProperty $CurrentMsi 'ProductCode'
if ($native -ne $ExpectedVersion) { throw "Expected native version $ExpectedVersion; found $native" }
$operation = Join-Path $env:LOCALAPPDATA 'BitChordUpdater\operations\operation-ci-exe-upgrade'
New-Item -ItemType Directory -Force -Path $operation | Out-Null
$held = $null
$oldCode = $null
try {
    # Reproduce the original jpackage setup.exe installation, not just an MSI install.
    $old = Start-Process -FilePath $PreviousExe -ArgumentList '/qn /norestart' -Wait -PassThru
    if ($old.ExitCode -notin @(0, 3010)) { throw "Old setup EXE failed with $($old.ExitCode)" }

    # Extract the *actual* MSI payload from the verified original EXE.
    $output = @(& "$PSScriptRoot/../windows/recover-legacy-msi.ps1" -OriginalSetupExe $PreviousExe -ExpectedSetupSha256 $PreviousSha256)
    $recovery = @($output | Where-Object { $_ -like 'Recovered original BitChord MSI source:*' }) | Select-Object -Last 1
    if (-not $recovery) { throw "Legacy MSI extraction did not succeed: $($output -join '; ')" }
    if (-not ($recovery -match 'product=(\{[A-Fa-f0-9-]+\}) file=(.+)$')) { throw "Invalid recovery response: $recovery" }
    $oldCode = $Matches[1]
    $oldMsi = $Matches[2]
    if ([BitChordInstallStateTest]::Get($oldCode) -ne 5) { throw 'Old setup EXE product is not installed.' }
    if (-not (Test-Path -LiteralPath $oldMsi -PathType Leaf)) { throw 'Missing original extracted MSI.' }

    # Reproduce stale MSI source registration and two random .tmp folders:
    # one contains the matching old ProductCode, one the unrelated new MSI.
    # The NEW setup.exe must run its embedded repair script automatically.
    $oldHash = (Get-FileHash -LiteralPath $oldMsi -Algorithm SHA256).Hash.ToLowerInvariant()
    $correct = Join-Path $env:TEMP ('bitchord-correct-' + [guid]::NewGuid().ToString('N') + '.tmp')
    $wrong = Join-Path $env:TEMP ('bitchord-wrong-' + [guid]::NewGuid().ToString('N') + '.tmp')
    New-Item -ItemType Directory -Force -Path $correct, $wrong | Out-Null
    Copy-Item -LiteralPath $oldMsi -Destination (Join-Path $correct 'main.msi')
    Copy-Item -LiteralPath $CurrentMsi -Destination (Join-Path $wrong 'main.msi')
    # The legacy installer source list still refers to its previous location.
    # Remove the temporary recovery copy in this disposable CI user only.
    Remove-Item -LiteralPath $oldMsi -Force
    if (Test-Path -LiteralPath $oldMsi) { throw 'Could not reproduce missing previous MSI source' }
    Write-Output "Setup test: old MSI is in $correct, not the registered old source; wrong MSI is in $wrong."

    # Keep the log open exactly as the external BitChord updater would.
    $held = [IO.File]::Open((Join-Path $operation 'install.log'),
        [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::Write, [IO.FileShare]::Read)
    $held.WriteByte(65)
    $held.Flush()
    $next = Start-Process -FilePath $CurrentExe -ArgumentList '--quiet' -Wait -PassThru
    if ($next.ExitCode -notin @(0, 3010)) { throw "New persistent-source setup.exe failed: $($next.ExitCode)" }
    $repairLog = Join-Path $env:LOCALAPPDATA 'BitChordUpdater\operations\setup-source-repair.log'
    $logText = if (Test-Path -LiteralPath $repairLog) { Get-Content -LiteralPath $repairLog -Raw } else { '' }
    if ($logText -notmatch ('msi-source-repaired product=' + [regex]::Escape($oldCode))) {
        throw "New EXE did not automatically repair the matching old MSI source. Log: $logText"
    }
    if ((Get-FileHash -LiteralPath $oldMsi -Algorithm SHA256).Hash -ine $oldHash) {
        throw 'Original MSI source was not restored from the correct Temp folder'
    }
    if ((Get-MsiProperty $oldMsi 'ProductCode') -ine $oldCode) {
        throw 'Setup restored an MSI with the wrong ProductCode'
    }
    if ([BitChordInstallStateTest]::Get($newCode) -ne 5) { throw 'New MSI ProductCode is not installed.' }
    if ([BitChordInstallStateTest]::Get($oldCode) -eq 5) { throw 'Old MSI remains installed after upgrade.' }

    $hash = (Get-FileHash -LiteralPath $CurrentMsi -Algorithm SHA256).Hash.ToLowerInvariant()
    $cached = Join-Path $env:LOCALAPPDATA ("BitChordUpdater\PackageCache\" + $hash + "\main.msi")
    if (-not (Test-Path -LiteralPath $cached) -or
        (Get-FileHash -LiteralPath $cached -Algorithm SHA256).Hash -ine $hash) {
        throw 'New setup.exe did not retain the byte-identical release MSI.'
    }
    Write-Output "Real EXE -> matching old Temp MSI repair -> new EXE upgrade passed; native=$native"
} finally {
    if ($held) { $held.Dispose() }
    if ([BitChordInstallStateTest]::Get($newCode) -eq 5) {
        $removed = Start-Process msiexec.exe -ArgumentList "/x $newCode /qn /norestart" -Wait -PassThru
        Write-Output "New BitChord cleanup exit code: $($removed.ExitCode)"
    }
    if ($oldCode -and [BitChordInstallStateTest]::Get($oldCode) -eq 5) {
        $removed = Start-Process msiexec.exe -ArgumentList "/x $oldCode /qn /norestart" -Wait -PassThru
        Write-Output "Old BitChord cleanup exit code: $($removed.ExitCode)"
    }
}
