param(
    [Parameter(Mandatory=$true)][string]$OriginalSetupExe,
    [Parameter(Mandatory=$true)][ValidatePattern('^[a-fA-F0-9]{64}$')][string]$ExpectedSetupSha256
)
$ErrorActionPreference = 'Stop'
# Repair only the installed BitChord instance represented by the *exact*
# jpackage EXE's embedded MSI. Never delete Installer registry entries,
# change ProductCode, or substitute a separately-built MSI.
$exe = (Resolve-Path -LiteralPath $OriginalSetupExe).Path
$actual = (Get-FileHash -LiteralPath $exe -Algorithm SHA256).Hash
if ($actual -ine $ExpectedSetupSha256) { throw 'Original setup SHA-256 mismatch; source recovery refused.' }

Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class JpackageResource {
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, EntryPoint="LoadLibraryExW", SetLastError=true)]
    public static extern IntPtr LoadLibraryEx(string path, IntPtr reserved, uint flags);
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, EntryPoint="FindResourceW", SetLastError=true)]
    public static extern IntPtr FindResource(IntPtr module, string name, IntPtr type);
    [DllImport("kernel32.dll", SetLastError=true)]
    public static extern uint SizeofResource(IntPtr module, IntPtr resource);
    [DllImport("kernel32.dll", SetLastError=true)]
    public static extern IntPtr LoadResource(IntPtr module, IntPtr resource);
    [DllImport("kernel32.dll", SetLastError=true)]
    public static extern IntPtr LockResource(IntPtr data);
    [DllImport("kernel32.dll")]
    public static extern bool FreeLibrary(IntPtr module);
}
public static class MsiSource {
    [DllImport("msi.dll", CharSet=CharSet.Unicode, EntryPoint="MsiQueryProductStateW")]
    public static extern int State(string product);
    [DllImport("msi.dll", CharSet=CharSet.Unicode, EntryPoint="MsiSourceListAddSourceExW")]
    public static extern uint Add(string product, string sid, uint context, uint flags, string source, uint index);
    [DllImport("msi.dll", CharSet=CharSet.Unicode, EntryPoint="MsiSourceListSetInfoW")]
    public static extern uint Set(string product, string sid, uint context, uint flags, string property, string value);
}
'@

$module = [JpackageResource]::LoadLibraryEx($exe, [IntPtr]::Zero, 0x22) # DATAFILE | IMAGE_RESOURCE
if ($module -eq [IntPtr]::Zero) { throw 'Cannot read jpackage setup resources.' }
$temporary = $null
try {
    $resource = [JpackageResource]::FindResource($module, 'msi', [IntPtr]10) # RT_RCDATA
    if ($resource -eq [IntPtr]::Zero) { throw 'This EXE does not contain the legacy jpackage MSI resource.' }
    $size = [JpackageResource]::SizeofResource($module, $resource)
    if ($size -lt 100 -or $size -gt 2147483647) { throw 'Unsupported embedded MSI size.' }
    $handle = [JpackageResource]::LoadResource($module, $resource)
    $data = [JpackageResource]::LockResource($handle)
    if ($data -eq [IntPtr]::Zero) { throw 'Cannot read embedded MSI bytes.' }

    $cacheRoot = Join-Path $env:LOCALAPPDATA 'BitChordUpdater\PackageCache\Legacy'
    New-Item -ItemType Directory -Force $cacheRoot | Out-Null
    $temporary = Join-Path $cacheRoot "$([guid]::NewGuid().ToString('N')).part"
    $output = [IO.File]::Open($temporary, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    try {
        $buffer = New-Object byte[] 1048576
        $position = 0
        while ($position -lt $size) {
            $count = [Math]::Min($buffer.Length, [int]($size - $position))
            [Runtime.InteropServices.Marshal]::Copy([IntPtr]::Add($data, $position), $buffer, 0, $count)
            $output.Write($buffer, 0, $count)
            $position += $count
        }
        $output.Flush($true)
    } finally { $output.Dispose() }
} finally {
    [void][JpackageResource]::FreeLibrary($module)
}
try {
    $msi = New-Object -ComObject WindowsInstaller.Installer
    $db = $msi.OpenDatabase([IO.Path]::GetFullPath($temporary), 0)
    function Read-Property([string]$name) {
        $view = $db.OpenView("SELECT Value FROM Property WHERE Property='$name'")
        try {
            $null = $view.Execute()
            $record = $view.Fetch()
            if (-not $record) { throw "MSI missing $name" }
            return ([string]$record.StringData(1)).Trim()
        } finally { $null = $view.Close() }
    }
    $product = Read-Property 'ProductCode'
    $upgrade = Read-Property 'UpgradeCode'
    $version = Read-Property 'ProductVersion'
    if ($upgrade.Trim('{}') -ine '8f3c1d24-6a2b-4f5e-9c17-2b8d54e0a913') {
        throw 'Embedded MSI is not part of the official BitChord upgrade family.'
    }
    if ([MsiSource]::State($product) -ne 5) {
        throw "MSI ProductCode $product is not installed for this Windows account; refusing source-list edits."
    }
    $sha = (Get-FileHash -LiteralPath $temporary -Algorithm SHA256).Hash.ToLowerInvariant()
    $cache = Join-Path $env:LOCALAPPDATA "BitChordUpdater\PackageCache\$sha"
    New-Item -ItemType Directory -Force $cache | Out-Null
    $target = Join-Path $cache 'main.msi'
    if (Test-Path -LiteralPath $target) {
        if ((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash -ine $sha) {
            throw 'Existing package cache contains a different MSI.'
        }
    } else {
        Move-Item -LiteralPath $temporary -Destination $target
        $temporary = $null
    }
    # MsiSourceListAddSourceEx / SetInfo are documented Windows Installer APIs.
    # Restrict to the CURRENT user's installed product; do not mutate arbitrary products.
    $added = $false
    foreach ($context in @(1, 2, 4)) { # USERUNMANAGED, USERMANAGED, MACHINE
        $rc = [MsiSource]::Add($product, $null, [uint32]$context, 1, ($cache.TrimEnd('\') + '\'), 1)
        if ($rc -eq 0) {
            $rcName = [MsiSource]::Set($product, $null, [uint32]$context, 1, 'PackageName', 'main.msi')
            if ($rcName -ne 0) { throw "Windows Installer PackageName registration failed with code $rcName" }
            $added = $true
            break
        }
        if ($rc -notin @(1605, 5)) { throw "Windows Installer source registration failed with code $rc" }
    }
    if (-not $added) { throw 'Unable to add a matching MSI source for this account (Windows Installer context not found).' }
    Write-Output "Recovered original BitChord MSI source: version=$version product=$product file=$target"
} finally {
    if ($temporary -and (Test-Path -LiteralPath $temporary)) { Remove-Item -LiteralPath $temporary -Force }
}
