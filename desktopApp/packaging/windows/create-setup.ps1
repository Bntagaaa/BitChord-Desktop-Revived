param(
    [Parameter(Mandatory=$true)][string]$Msi,
    [Parameter(Mandatory=$true)][string]$Output,
    [string]$Icon = "$PSScriptRoot/../icons/AppIcon.ico"
)
$ErrorActionPreference = 'Stop'
$msiFile = (Resolve-Path -LiteralPath $Msi).Path
$msiSize = (Get-Item -LiteralPath $msiFile).Length
if ($msiSize -lt 100 -or $msiSize -gt 4GB) { throw 'Invalid MSI size.' }
$csc = Join-Path $env:SystemRoot 'Microsoft.NET/Framework64/v4.0.30319/csc.exe'
if (-not (Test-Path -LiteralPath $csc)) { throw "Required .NET Framework compiler missing: $csc" }
$source = Join-Path $PSScriptRoot 'BitChordSetup.cs'
$repairHelper = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../src/main/resources/updater/windows-msi-source.ps1'))
if (-not (Test-Path -LiteralPath $repairHelper)) { throw 'Windows MSI source repair helper is missing.' }
$iconFile = (Resolve-Path -LiteralPath $Icon).Path
$destination = [IO.Path]::GetFullPath($Output)
New-Item -ItemType Directory -Force ([IO.Path]::GetDirectoryName($destination)) | Out-Null
$tempRoot = if ($env:RUNNER_TEMP) { $env:RUNNER_TEMP } else { [IO.Path]::GetTempPath() }
$work = Join-Path $tempRoot "bitchord-setup-$([guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory $work | Out-Null
try {
    $stub = Join-Path $work 'BitChordSetup.exe'
    & $csc /nologo /target:winexe "/out:$stub" "/win32icon:$iconFile" /reference:System.Windows.Forms.dll "/resource:$repairHelper,BitChord.WindowsMsiSource" $source
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $stub)) { throw 'Failed to compile BitChord setup wrapper.' }
    Copy-Item -LiteralPath $stub -Destination $destination -Force
    $inputStream = [IO.File]::OpenRead($msiFile)
    $outputStream = [IO.File]::Open($destination, [IO.FileMode]::Append, [IO.FileAccess]::Write, [IO.FileShare]::None)
    try {
        $inputStream.CopyTo($outputStream, 1048576)
        $inputStream.Position = 0
        $sha = [Security.Cryptography.SHA256]::Create()
        try { $digest = $sha.ComputeHash($inputStream) } finally { $sha.Dispose() }
        $writer = [IO.BinaryWriter]::new($outputStream, [Text.Encoding]::ASCII, $true)
        try {
            $writer.Write([long]$msiSize)
            $writer.Write([byte[]]$digest)
            $writer.Write([Text.Encoding]::ASCII.GetBytes('BCHMSI01'))
            $writer.Flush()
        } finally { $writer.Dispose() }
    } finally { $outputStream.Dispose(); $inputStream.Dispose() }
    Write-Output "BitChord setup: embedded exact MSI, $msiSize bytes, SHA256=$([Convert]::ToHexString($digest).ToLowerInvariant())"
} finally {
    Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
}
