param(
    [Parameter(Mandatory=$true)][long]$ParentPid,
    [Parameter(Mandatory=$true)][long]$ParentStartMillis,
    [Parameter(Mandatory=$true)][string]$Package,
    [Parameter(Mandatory=$true)][string]$Expected,
    [Parameter(Mandatory=$true)][string]$Operation,
    [Parameter(Mandatory=$true)][string]$PortableRoot,
    [int]$WaitSeconds = 120,
    [switch]$SkipLaunch,
    [switch]$SimulateSwapFailure
)

$ErrorActionPreference = 'Stop'
function Log([string]$message) { Write-Output "$([DateTime]::UtcNow.ToString('o')) stage=$message" }
function Finish([string]$message, [int]$code) {
    Log $message
    Set-Content -LiteralPath (Join-Path $Operation 'status') -Value $message -Encoding utf8
    Set-Content -LiteralPath (Join-Path $Operation 'completed') -Value $message -Encoding utf8
    exit $code
}
function Is-OriginalAlive {
    $process = Get-Process -Id $ParentPid -ErrorAction SilentlyContinue
    if (-not $process) { return $false }
    try {
        $started = ([DateTimeOffset]$process.StartTime.ToUniversalTime()).ToUnixTimeMilliseconds()
        return [Math]::Abs($started - $ParentStartMillis) -le 10
    } catch { return $false }
}
function Is-Reparse([string]$path) {
    $item = Get-Item -LiteralPath $path -Force -ErrorAction Stop
    return ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0
}

$staging = $null
$backup = $null
$oldMoved = $false
$installed = $false
try {
    if ($ParentPid -le 1 -or $ParentStartMillis -le 0 -or $WaitSeconds -lt 1 -or
        $Expected -notmatch '^[a-fA-F0-9]{64}$') { throw 'Invalid operation identity' }
    $operationPath = [IO.Path]::GetFullPath($Operation)
    $archivePath = [IO.Path]::GetFullPath($Package)
    $root = [IO.Path]::GetFullPath($PortableRoot).TrimEnd('\')
    $parent = [IO.Path]::GetDirectoryName($root)
    if ([string]::IsNullOrWhiteSpace($parent) -or
        $root -eq [IO.Path]::GetPathRoot($root) -or
        [string]::IsNullOrWhiteSpace([IO.Path]::GetFileName($root))) {
        throw 'Unsafe portable directory'
    }
    $operationPrefix = $operationPath.TrimEnd('\') + '\'
    $rootPrefix = $root + '\'
    if ($operationPath.Equals($root, [StringComparison]::OrdinalIgnoreCase) -or
        $operationPrefix.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Updater logs must not be inside the portable installation'
    }
    if ((Is-Reparse $root) -or (Is-Reparse $parent)) { throw 'Portable folder must not use a junction or symbolic link' }
    if (-not (Test-Path -LiteralPath (Join-Path $root '.bitchord-portable') -PathType Leaf) -or
        -not (Test-Path -LiteralPath (Join-Path $root 'BitChord.exe') -PathType Leaf)) {
        throw 'Missing portable marker or BitChord executable; installation was not changed'
    }
    $file = Get-Item -LiteralPath $archivePath -ErrorAction Stop
    if ($file.PSIsContainer -or ($file.Attributes -band [IO.FileAttributes]::ReparsePoint) -or
        $file.Directory.FullName -ine $operationPath) { throw 'ZIP is not the verified private operation package' }
    if (-not (Test-Path -LiteralPath $operationPath -PathType Container)) { throw 'Operation directory missing' }

    $helperStart = ([DateTimeOffset](Get-Process -Id $PID).StartTime.ToUniversalTime()).ToUnixTimeMilliseconds()
    Set-Content -LiteralPath (Join-Path $operationPath 'helper.identity') -Value "pid=$PID" -Encoding ascii
    Add-Content -LiteralPath (Join-Path $operationPath 'helper.identity') -Value "start_millis=$helperStart" -Encoding ascii
    Set-Content -LiteralPath (Join-Path $operationPath 'armed') -Value 'armed' -Encoding ascii
    Log "portable-helper-armed pid=$PID parent_pid=$ParentPid root=$root"

    $deadline = [DateTime]::UtcNow.AddSeconds($WaitSeconds)
    while (Is-OriginalAlive) {
        if (Test-Path -LiteralPath (Join-Path $operationPath 'abort')) {
            Finish 'aborted-before-shutdown; portable folder unchanged' 1
        }
        if ([DateTime]::UtcNow -ge $deadline) {
            Finish 'old-process-shutdown-timeout; portable folder unchanged' 1
        }
        Start-Sleep -Milliseconds 200
    }
    if (Test-Path -LiteralPath (Join-Path $operationPath 'abort')) {
        Finish 'aborted-before-replacement; portable folder unchanged' 1
    }

    if ((Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash -ine $Expected) {
        Finish 'zip-checksum-mismatch; portable folder unchanged' 1
    }

    # Reject ZIP slip, duplicate names on case-insensitive Windows, symlink
    # metadata, unrelated root folders, and oversized expansion before changes.
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead($archivePath)
    $names = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    $found = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    [long]$expandedBytes = 0
    try {
        foreach ($entry in $zip.Entries) {
            $name = $entry.FullName
            if ([string]::IsNullOrWhiteSpace($name) -or
                -not $name.StartsWith('BitChord/', [StringComparison]::Ordinal) -or
                $name -match '[\\:]' -or $name -match '(^|/)\.\.?(/|$)' -or
                $name -match '[\x00-\x1f]' -or -not $names.Add($name)) {
                throw "Unsafe or duplicate ZIP entry: $name"
            }
            $unixMode = ($entry.ExternalAttributes -shr 16) -band 0xF000
            if ($unixMode -eq 0xA000) { throw "ZIP symlink refused: $name" }
            if ($entry.Length -lt 0 -or $expandedBytes + $entry.Length -gt 4GB) {
                throw 'Portable update expands beyond 4 GiB safety limit'
            }
            $expandedBytes += $entry.Length
            [void]$found.Add($name)
        }
    } finally { $zip.Dispose() }
    foreach ($required in @('BitChord/BitChord.exe', 'BitChord/.bitchord-portable', 'BitChord/app/BitChord.cfg')) {
        if (-not $found.Contains($required)) { throw "Portable ZIP lacks $required" }
    }
    $disk = [IO.DriveInfo]::new([IO.Path]::GetPathRoot($root))
    if ($disk.AvailableFreeSpace -lt ($expandedBytes + 256MB)) {
        Finish "insufficient-disk-space for portable extraction; required=$($expandedBytes + 256MB) available=$($disk.AvailableFreeSpace)" 1
    }
    if ($expandedBytes -le 0) { throw 'Portable ZIP contains no files' }

    $id = [guid]::NewGuid().ToString('N')
    $name = [IO.Path]::GetFileName($root)
    $staging = Join-Path $parent (".$name.update-staging-$id")
    $backup = Join-Path $parent ("$name.previous-$id")
    if ((Test-Path -LiteralPath $staging) -or (Test-Path -LiteralPath $backup)) {
        throw 'Update staging or backup already exists'
    }
    [void][IO.Directory]::CreateDirectory($staging)
    [IO.Compression.ZipFile]::ExtractToDirectory($archivePath, $staging)
    $candidate = Join-Path $staging 'BitChord'
    foreach ($required in @('BitChord.exe', '.bitchord-portable', 'app\BitChord.cfg')) {
        if (-not (Test-Path -LiteralPath (Join-Path $candidate $required) -PathType Leaf)) {
            throw "Extracted portable ZIP lacks $required"
        }
    }
    if (Test-Path -LiteralPath (Join-Path $operationPath 'abort')) {
        Finish 'aborted-before-folder-swap; portable folder unchanged' 1
    }
    # Only rename complete folders on the same volume, never recursively
    # overwrite a running executable or delete unknown user-added files.
    [IO.Directory]::Move($root, $backup)
    $oldMoved = $true
    if ($SimulateSwapFailure) { throw 'simulated-swap-failure' }
    [IO.Directory]::Move($candidate, $root)
    $installed = $true
    Set-Content -LiteralPath (Join-Path $operationPath 'backup.path') -Value $backup -Encoding utf8
    Log "portable-replaced in-place root=$root backup=$backup"
    if ($SkipLaunch) { Finish 'success-portable-replaced; backup retained' 0 }
    try {
        Start-Process -FilePath (Join-Path $root 'BitChord.exe') -WorkingDirectory $root
        Finish 'success-portable-replaced; relaunch requested; backup retained' 0
    } catch {
        Finish 'portable-replaced; relaunch failed; manually open BitChord.exe; backup retained' 1
    }
} catch {
    $reason = $_.Exception.Message
    if ($oldMoved -and -not $installed) {
        if (-not (Test-Path -LiteralPath $root) -and (Test-Path -LiteralPath $backup)) {
            try {
                [IO.Directory]::Move($backup, $root)
                Log 'portable-rollback-restored-original'
                Finish 'portable-replacement-failed; original folder restored' 1
            } catch {
                Finish 'portable-rollback-failed; original folder remains in backup; manual recovery required' 1
            }
        }
        Finish 'portable-replacement-failed; original backup retained; manual recovery required' 1
    }
    Log "portable-update-failed error=$reason"
    Finish 'portable-update-failed; original folder unchanged' 1
} finally {
    if ($staging -and (Test-Path -LiteralPath $staging)) {
        try { Remove-Item -LiteralPath $staging -Recurse -Force -ErrorAction Stop }
        catch { Log 'portable-staging-cleanup-deferred' }
    }
}
