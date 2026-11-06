param(
    [Parameter(Mandatory=$true)][string]$PreviousMsi,
    [Parameter(Mandatory=$true)][string]$CurrentMsi,
    [Parameter(Mandatory=$true)][string]$ExpectedVersion
)
$ErrorActionPreference = 'Stop'
# Run on a disposable Windows CI runner. The real MSI upgrade must coexist with
# the same open updater log handle that caused Error 1306 in the field.
function Get-MsiProperty([string]$File, [string]$Name) {
    $installer = New-Object -ComObject WindowsInstaller.Installer
    $database = $installer.OpenDatabase([IO.Path]::GetFullPath($File), 0)
    $view = $database.OpenView("SELECT Value FROM Property WHERE Property='$Name'")
    try {
        $null = $view.Execute()
        $record = $view.Fetch()
        if (-not $record) { throw "MSI property $Name missing" }
        return ([string]$record.StringData(1)).Trim()
    } finally { $null = $view.Close() }
}
function Invoke-Msi([string]$Arguments, [string]$Log) {
    $p = Start-Process msiexec.exe -ArgumentList "$Arguments /qn /norestart /l*v `"$Log`"" -PassThru -Wait
    if ($p.ExitCode -notin @(0,3010)) {
        throw "msiexec failed with $($p.ExitCode). See $Log"
    }
}
function Installed-Version {
    $key = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall'
    $entry = Get-ChildItem $key -ErrorAction SilentlyContinue | ForEach-Object { Get-ItemProperty $_.PSPath } |
        Where-Object { $_.DisplayName -eq 'BitChord' } | Select-Object -First 1
    if ($entry) { return $entry.DisplayVersion }
    return $null
}
$oldId = Get-MsiProperty $PreviousMsi 'ProductCode'
$newId = Get-MsiProperty $CurrentMsi 'ProductCode'
$expectedNative = Get-MsiProperty $CurrentMsi 'ProductVersion'
if ($expectedNative -ne $ExpectedVersion) { throw "Expected native $ExpectedVersion, found $expectedNative" }
if ($oldId -eq $newId) { throw 'Upgrade test needs distinct MSI ProductCodes' }
$root = Join-Path $env:LOCALAPPDATA 'BitChordUpdater\operations'
$installRoot = Join-Path $env:LOCALAPPDATA 'BitChord'
New-Item -ItemType Directory -Force $root | Out-Null
$operation = Join-Path $root 'operation-ci-upgrade'
New-Item -ItemType Directory -Force $operation | Out-Null
$log = Join-Path $operation 'install.log'
$held = $null
try {
    Invoke-Msi "/i `"$PreviousMsi`"" (Join-Path $env:RUNNER_TEMP 'bitChord-old-msi.log')
    $previousVersion = Installed-Version
    if (-not $previousVersion) { throw 'Old BitChord version was not registered under HKCU' }
    # Match the updater's lifetime: installer log stays open while msiexec runs.
    $held = [IO.File]::Open($log, [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::Write, [IO.FileShare]::Read)
    $held.WriteByte(65)
    $held.Flush()
    Invoke-Msi "/i `"$CurrentMsi`"" (Join-Path $env:RUNNER_TEMP 'bitChord-new-msi.log')
    $actual = Installed-Version
    if ($actual -ne $ExpectedVersion) {
        throw "Upgrade left version '$actual' (old $previousVersion), expected '$ExpectedVersion'"
    }
    if (-not (Test-Path (Join-Path $installRoot 'BitChord.exe'))) { throw 'Updated launcher missing' }
    Write-Output "Real MSI upgrade passed: $previousVersion -> $actual with updater log open outside install root"
} finally {
    if ($held) { $held.Dispose() }
    $cleanup = Start-Process msiexec.exe -ArgumentList "/x $newId /qn /norestart" -Wait -PassThru
    Write-Output "Cleanup Windows Installer exit code: $($cleanup.ExitCode)"
}
