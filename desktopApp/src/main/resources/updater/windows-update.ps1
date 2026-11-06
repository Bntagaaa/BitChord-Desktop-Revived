param(
    [long]$ParentPid,
    [string]$Installer,
    [string]$Expected,
    [string]$Operation,
    [long]$ParentStartMillis,
    [int]$WaitSeconds = 120
)
$ErrorActionPreference = 'Stop'
function Log([string]$Message) { Write-Output "$([DateTime]::UtcNow.ToString('o')) stage=$Message" }
function Finish([string]$Message, [int]$Code) {
    Log $Message
    # The installer has exited (or never started): release our own read
    # handle before deleting the private staged EXE. Never remove the
    # permanent PackageCache/main.msi Windows Installer may still require.
    if ($script:stream) {
        $script:stream.Dispose()
        $script:stream = $null
    }
    if ($Installer -and $Operation) {
        $staged = Join-Path $Operation 'verified-setup.exe'
        if ([IO.Path]::GetFullPath($Installer) -ieq [IO.Path]::GetFullPath($staged) -and
            (Test-Path -LiteralPath $staged -PathType Leaf)) {
            try {
                $entry = Get-Item -LiteralPath $staged -ErrorAction Stop
                if (-not ($entry.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
                    Remove-Item -LiteralPath $staged -Force -ErrorAction Stop
                    Log 'staged-setup-cleaned'
                }
            } catch { Log 'staged-setup-cleanup-deferred' }
        }
    }
    Set-Content -LiteralPath (Join-Path $Operation 'status') -Value $Message -Encoding utf8
    Set-Content -LiteralPath (Join-Path $Operation 'completed') -Value $Message -Encoding utf8
    exit $Code
}
$stream = $null
try {
    if ($ParentPid -le 1 -or $Expected -notmatch '^[a-fA-F0-9]{64}$') { throw 'Invalid update identity' }
    $item = Get-Item -LiteralPath $Installer
    if ($item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Installer must be a regular file' }
    if ($item.Directory.FullName -ne [IO.Path]::GetFullPath($Operation)) { throw 'Installer must be private to this operation' }
    $helperStart = ([DateTimeOffset](Get-Process -Id $PID).StartTime.ToUniversalTime()).ToUnixTimeMilliseconds()
    Set-Content -LiteralPath (Join-Path $Operation 'helper.identity') -Value "pid=$PID`nstart_millis=$helperStart" -Encoding ascii
    Log "helper-started helper_pid=$PID parent_pid=$ParentPid installer=$Installer"
    $parent = Get-Process -Id $ParentPid -ErrorAction SilentlyContinue
    if ($parent) {
        $actualStart = ([DateTimeOffset]$parent.StartTime.ToUniversalTime()).ToUnixTimeMilliseconds()
        if ([Math]::Abs($actualStart - $ParentStartMillis) -gt 10) { $parent = $null }
    }
    Set-Content -LiteralPath (Join-Path $Operation 'armed') -Value 'armed' -Encoding ascii
    $deadline = [DateTime]::UtcNow.AddSeconds($WaitSeconds)
    while ($parent -and -not $parent.HasExited) {
        if (Test-Path -LiteralPath (Join-Path $Operation 'abort')) { Finish 'aborted-before-shutdown' 1 }
        if ([DateTime]::UtcNow -ge $deadline) { Finish 'old-process-shutdown-timeout; no installer started' 1 }
        Start-Sleep -Milliseconds 100
        $parent.Refresh()
    }
    if (Test-Path -LiteralPath (Join-Path $Operation 'abort')) { Finish 'aborted-before-installer-launch' 1 }
    # Hold a read-only sharing handle through execution: writers/replacement cannot race verification.
    $stream = [IO.File]::Open($Installer, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
    if ((Get-FileHash -InputStream $stream -Algorithm SHA256).Hash.ToLowerInvariant() -ne $Expected.ToLowerInvariant()) {
        Finish 'checksum-mismatch; installer not executed' 1
    }
    # Recheck free space immediately before opening the wizard (download may have
    # completed long before this handoff). Leave room for extracted MSI and rollback.
    $disk = [IO.DriveInfo]::new([IO.Path]::GetPathRoot($Installer))
    $required = 2GB + 3 * $item.Length
    if ($disk.AvailableFreeSpace -lt $required) {
        Finish "insufficient-disk-space required_bytes=$required available_bytes=$($disk.AvailableFreeSpace); installer not executed" 1
    }
    # A previous jpackage setup may have registered %TEMP%/<old-random-folder>/main.msi.
    # Find a source with the installed ProductCode, not an arbitrary current MSI.
    $sourceRepair = Join-Path $Operation 'msi-source.ps1'
    if (Test-Path -LiteralPath $sourceRepair -PathType Leaf) {
        try {
            & $sourceRepair | ForEach-Object { Log ([string]$_) }
        } catch {
            Log ("msi-source-repair-skipped error={0}" -f $_.Exception.GetType().Name)
        }
    }
    Log 'checksum-verified; opening interactive Windows setup'
    # Ordinary interactive wizard/UAC. No silent elevation or unproven automatic GUI relaunch.
    $setup = Start-Process -FilePath $Installer -WorkingDirectory $Operation -PassThru -Wait
    Log "installer-exited pid=$($setup.Id) exit_code=$($setup.ExitCode)"
    if ($setup.ExitCode -eq 0) { Finish 'installer-reported-success; open BitChord manually' 0 }
    if ($setup.ExitCode -in @(3010, 1641)) { Finish 'installer-reported-reboot-required; manual restart' 0 }
    Finish "installer-failed-or-cancelled exit_code=$($setup.ExitCode); launch previous installation manually" 1
} catch {
    Finish "helper-failed error=$($_.Exception.GetType().Name); installation completion unconfirmed" 1
} finally {
    if ($stream) { $stream.Dispose() }
}
