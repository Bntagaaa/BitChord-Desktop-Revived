param(
    [string[]]$SearchRoots = @(),
    [string]$CacheRoot = '',
    [int]$MaximumDirectories = 160
)
$ErrorActionPreference = 'Stop'

# A legacy jpackage setup registers %TEMP%\<random>.tmp\main.msi as its
# source. This location can become stale. Only repair an installed BitChord
# ProductCode with an identical, still-present main.msi; never guess a folder.
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class BitChordMsiSourceNative {
    [DllImport("msi.dll", CharSet=CharSet.Unicode, EntryPoint="MsiQueryProductStateW")]
    public static extern int InstalledState(string product);
    [DllImport("msi.dll", CharSet=CharSet.Unicode, EntryPoint="MsiSourceListAddSourceExW")]
    public static extern uint AddSource(string product, string userSid, uint context, uint flags, string source, uint index);
    [DllImport("msi.dll", CharSet=CharSet.Unicode, EntryPoint="MsiSourceListSetInfoW")]
    public static extern uint SetInfo(string product, string userSid, uint context, uint flags, string property, string value);
    [DllImport("msi.dll", CharSet=CharSet.Unicode, EntryPoint="MsiSourceListForceResolutionExW")]
    public static extern uint ForceResolution(string product, string userSid, uint context, uint flags);
}
'@

function Read-Property($database, [string]$name) {
    $view = $database.OpenView("SELECT Value FROM Property WHERE Property='$name'")
    try {
        $null = $view.Execute()
        $record = $view.Fetch()
        if ($null -eq $record) { return '' }
        return ([string]$record.StringData(1)).Trim()
    } finally { $null = $view.Close() }
}

function Read-BitChordMsi([string]$path, $installer) {
    try {
        $item = Get-Item -LiteralPath $path -ErrorAction Stop
        if ($item.PSIsContainer -or $item.Length -lt 100000 -or
            $item.Length -gt 4GB -or
            ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) { return $null }
        $database = $installer.OpenDatabase($item.FullName, 0)
        try {
            $product = Read-Property $database 'ProductCode'
            $upgrade = Read-Property $database 'UpgradeCode'
            $version = Read-Property $database 'ProductVersion'
        } finally {
            if ($database) { [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($database) }
        }
        if ($product -notmatch '^\{[a-fA-F0-9-]{36}\}$' -or
            $upgrade.Trim('{}') -ine '8f3c1d24-6a2b-4f5e-9c17-2b8d54e0a913' -or
            [BitChordMsiSourceNative]::InstalledState($product) -ne 5) { return $null }
        # An installed MSI instance must agree with the candidate's native version.
        $installedVersion = try { [string]$installer.ProductInfo($product, 'VersionString') } catch { '' }
        if ($installedVersion -and $installedVersion -ne $version) { return $null }
        return [pscustomobject]@{ File = $item.FullName; Product = $product; Version = $version }
    } catch { return $null }
}

function Repair-LegacyTempSource {
    if ([string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) { throw 'LOCALAPPDATA is unavailable' }
    if (-not $CacheRoot) {
        $CacheRoot = Join-Path $env:LOCALAPPDATA 'BitChordUpdater\PackageCache'
    }
    $installation = [IO.Path]::GetFullPath((Join-Path $env:LOCALAPPDATA 'BitChord')).TrimEnd('\') + '\'
    if (-not $SearchRoots -or $SearchRoots.Count -eq 0) {
        $SearchRoots = @($env:TEMP, (Join-Path $env:LOCALAPPDATA 'Temp'), $CacheRoot)
    }
    $paths = New-Object System.Collections.Generic.List[string]
    $visited = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($root in $SearchRoots) {
        if ([string]::IsNullOrWhiteSpace($root) -or -not [IO.Path]::IsPathRooted($root)) { continue }
        $rootItem = Get-Item -LiteralPath $root -ErrorAction SilentlyContinue
        if (-not $rootItem -or -not $rootItem.PSIsContainer -or
            ($rootItem.Attributes -band [IO.FileAttributes]::ReparsePoint)) { continue }
        $directories = @($rootItem) + @(Get-ChildItem -LiteralPath $rootItem.FullName -Directory -ErrorAction SilentlyContinue |
            Where-Object { -not ($_.Attributes -band [IO.FileAttributes]::ReparsePoint) } |
            Sort-Object LastWriteTime -Descending | Select-Object -First $MaximumDirectories)
        foreach ($folder in $directories) {
            $candidate = Join-Path $folder.FullName 'main.msi'
            if (-not $visited.Add($candidate)) { continue }
            $full = [IO.Path]::GetFullPath($candidate)
            if ($full.StartsWith($installation, [StringComparison]::OrdinalIgnoreCase)) { continue }
            if (Test-Path -LiteralPath $full -PathType Leaf) { $paths.Add($full) }
        }
    }
    if ($paths.Count -eq 0) { Write-Output 'msi-source-repair-skipped no-main-msi-found'; return }

    $installer = New-Object -ComObject WindowsInstaller.Installer
    $matches = @($paths | ForEach-Object { Read-BitChordMsi $_ $installer } | Where-Object { $null -ne $_ })
    if ($matches.Count -eq 0) { Write-Output 'msi-source-repair-skipped no-installed-product-match'; return }
    $codes = @($matches | ForEach-Object { $_.Product.ToUpperInvariant() } | Select-Object -Unique)
    if ($codes.Count -ne 1) { Write-Output 'msi-source-repair-skipped ambiguous-installed-products'; return }

    $selected = $matches[0]
    $sourceHash = (Get-FileHash -LiteralPath $selected.File -Algorithm SHA256).Hash.ToLowerInvariant()
    $destinationDir = Join-Path $CacheRoot $sourceHash
    $destination = Join-Path $destinationDir 'main.msi'
    $cacheFull = [IO.Path]::GetFullPath($destination)
    if ($cacheFull.StartsWith($installation, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'MSI cache must be outside the BitChord installation directory'
    }
    New-Item -ItemType Directory -Force -Path $destinationDir | Out-Null
    if (Test-Path -LiteralPath $destination) {
        $item = Get-Item -LiteralPath $destination
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -or
            (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -ine $sourceHash) {
            throw 'Unexpected existing MSI in the permanent package cache'
        }
    } else {
        $temporary = Join-Path $destinationDir ("source-" + [guid]::NewGuid().ToString('N') + '.msi')
        try {
            Copy-Item -LiteralPath $selected.File -Destination $temporary
            if ((Get-FileHash -LiteralPath $temporary -Algorithm SHA256).Hash -ine $sourceHash) {
                throw 'Legacy MSI changed during copying'
            }
            $verified = Read-BitChordMsi $temporary $installer
            if (-not $verified -or $verified.Product -ine $selected.Product) {
                throw 'Copied MSI does not match installed BitChord'
            }
            Move-Item -LiteralPath $temporary -Destination $destination
        } finally {
            Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue
        }
    }
    $registered = $false
    foreach ($context in @(2, 1, 4)) { # current user unmanaged, managed, machine
        $code = [BitChordMsiSourceNative]::AddSource($selected.Product, $null, [uint32]$context,
            1, ($destinationDir.TrimEnd('\') + '\'), 1) # MSISOURCETYPE_NETWORK | MSICODE_PRODUCT
        if ($code -ne 0) { continue }
        $name = [BitChordMsiSourceNative]::SetInfo($selected.Product, $null, [uint32]$context,
            0, 'PackageName', 'main.msi')
        if ($name -ne 0) { throw "MSI source name registration failed with code $name" }
        # An earlier jpackage Temp path may still be LastUsedSource.
        $reset = [BitChordMsiSourceNative]::ForceResolution($selected.Product, $null, [uint32]$context, 0)
        if ($reset -ne 0) { throw "MSI stale source reset failed with code $reset" }
        $registered = $true
        break
    }
    if (-not $registered) { Write-Output 'msi-source-repair-skipped registration-denied'; return }
    Write-Output "msi-source-repaired product=$($selected.Product) version=$($selected.Version) cache=$destination"
}

Repair-LegacyTempSource
