# Fail Windows packaging when the Automix JNI DLL would only load on a build runner
# with MinGW's runtime DLLs available on PATH.
[CmdletBinding()]
param(
    [string]$DllPath = "desktopApp/build/native/windows/bitchord_analysis.dll",
    [string]$AppRoot = "desktopApp/build/compose/binaries/main/app/BitChord",
    [switch]$NativeOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if (-not (Test-Path -LiteralPath $DllPath -PathType Leaf)) {
    throw "Automix analyser DLL was not built: $DllPath"
}
$dll = (Resolve-Path -LiteralPath $DllPath).Path

# A successful LoadLibrary on CI is not enough: GitHub's Windows runner may have
# MinGW DLLs on PATH that a normal end-user Windows installation does not.
$objdump = Get-Command objdump.exe -ErrorAction SilentlyContinue | Select-Object -First 1
if ($null -eq $objdump) {
    throw "objdump.exe is required to audit Automix DLL dependencies (install MinGW binutils)"
}
$dump = @(& $objdump.Source -p $dll)
if ($LASTEXITCODE -ne 0) {
    throw "objdump failed to inspect $dll (exit code $LASTEXITCODE)"
}
$imports = @()
foreach ($line in $dump) {
    if ($line -match '^\s*DLL Name:\s*(\S+)') {
        $imports += $Matches[1]
    }
}
if ($imports.Count -eq 0) {
    throw "Could not read the DLL import table for $dll"
}
Write-Host "Automix native imports: $($imports -join ', ')"
$mingwImports = @($imports | Where-Object {
    $_ -match '(?i)^lib(?:stdc\+\+-6|gcc_s_[^.]+|winpthread-1|ssp-0|atomic-1|gomp-1)\.dll$'
})
if ($mingwImports.Count -gt 0) {
    throw "Automix DLL still requires unbundled MinGW runtime libraries: $($mingwImports -join ', ')"
}

# Verify Windows can load the same DLL that DesktopAnalysisRuntime System.load() uses.
$handle = [IntPtr]::Zero
try {
    $handle = [System.Runtime.InteropServices.NativeLibrary]::Load($dll)
    Write-Host "Automix JNI DLL loaded successfully on Windows"
} catch {
    throw "Automix JNI DLL could not be loaded: $($_.Exception.Message)"
} finally {
    if ($handle -ne [IntPtr]::Zero) {
        [System.Runtime.InteropServices.NativeLibrary]::Free($handle)
    }
}

function Invoke-AutomixJniProbe([string]$NativePath) {
    $probe = Join-Path ([System.IO.Path]::GetTempPath()) ("bitchord-automix-" + [Guid]::NewGuid())
    New-Item -ItemType Directory -Path $probe | Out-Null
    try {
        $source = Join-Path $PSScriptRoot "native/TrackFeatures.java"
        & javac -d $probe $source
        if ($LASTEXITCODE -ne 0) { throw "JNI probe compilation failed" }
        & java -cp $probe com.music.bitchord.playback.smart.TrackFeatures $NativePath
        if ($LASTEXITCODE -ne 0) { throw "JNI analyser regression failed" }
    } finally {
        Remove-Item -LiteralPath $probe -Recurse -Force
    }
}

Invoke-AutomixJniProbe $dll
if ($NativeOnly) { return }

# A native DLL built in the workspace does not help if the packaged app jar omits it.
$jarDirectory = Join-Path $AppRoot "app"
if (-not (Test-Path -LiteralPath $jarDirectory -PathType Container)) {
    throw "Cannot inspect packaged application jars: $jarDirectory"
}
$jars = @(Get-ChildItem -LiteralPath $jarDirectory -Filter "desktopApp*.jar" -File)
if ($jars.Count -ne 1) {
    throw "Expected exactly one desktopApp jar in $jarDirectory, got $($jars.Count)"
}
$resources = @(& jar tf $jars[0].FullName)
if ($LASTEXITCODE -ne 0) {
    throw "Cannot inspect packaged desktopApp jar (exit code $LASTEXITCODE)"
}
if ($resources -notcontains "native/bitchord_analysis.dll") {
    throw "Packaged desktopApp jar is missing native/bitchord_analysis.dll"
}
$models = @($resources | Where-Object { $_ -match '^models/.+\.onnx$' })
if ($models.Count -lt 2) {
    throw "Packaged desktopApp jar is missing Automix ONNX models (found $($models.Count))"
}
Write-Host "Automix Windows package verified: static runtime imports, loadable JNI DLL, and $($models.Count) models"

# Load the packaged bytes too, rather than assuming the build-output DLL was included.
$extracted = Join-Path ([System.IO.Path]::GetTempPath()) ("bitchord-packaged-" + [Guid]::NewGuid())
New-Item -ItemType Directory -Path $extracted | Out-Null
try {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::OpenRead($jars[0].FullName)
    try {
        $entry = $archive.GetEntry("native/bitchord_analysis.dll")
        $packagedDll = Join-Path $extracted "bitchord_analysis.dll"
        [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $packagedDll)
    } finally { $archive.Dispose() }
    if ((Get-FileHash $packagedDll).Hash -ne (Get-FileHash $dll).Hash) {
        throw "Packaged Automix DLL differs from the dependency-audited build output"
    }
    Invoke-AutomixJniProbe $packagedDll
} finally {
    Remove-Item -LiteralPath $extracted -Recurse -Force
}
