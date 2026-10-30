param([string]$Artifacts, [string]$Version, [string]$Output = "$env:RUNNER_TEMP\bitchord-updater-checks")
$ErrorActionPreference = 'Stop'
if (-not $IsWindows -and $PSVersionTable.PSEdition -eq 'Core') { throw 'These execution checks require Windows' }
New-Item -ItemType Directory -Force $Output | Out-Null
$helper = Join-Path $PSScriptRoot '../../src/main/resources/updater/windows-update.ps1'
$helper = [IO.Path]::GetFullPath($helper)
$tokens = $null; $errors = $null
[Management.Automation.Language.Parser]::ParseFile($helper, [ref]$tokens, [ref]$errors) > $null
if ($errors.Count) { throw ($errors | Out-String) }
$csc = Join-Path $env:SystemRoot 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
if (-not (Test-Path $csc)) { throw 'The fixture needs the Windows .NET Framework C# compiler' }
$source = Join-Path $Output 'Fixture.cs'
@'
using System;
using System.Diagnostics;
using System.IO;
class Fixture {
    static int Main() {
        string exe = Process.GetCurrentProcess().MainModule.FileName;
        File.WriteAllText(Path.ChangeExtension(exe, ".started"), "started");
        string code = Path.ChangeExtension(exe, ".code");
        return File.Exists(code) ? Int32.Parse(File.ReadAllText(code)) : 0;
    }
}
'@ | Set-Content -LiteralPath $source
$fixture = Join-Path $Output 'Fixture.exe'
& $csc /nologo /target:exe "/out:$fixture" $source
if ($LASTEXITCODE) { throw 'Fixture compilation failed' }
$results = @()
foreach ($case in @('parent-exit', 'checksum-mismatch', 'parent-timeout', 'installer-failure')) {
    $operation = Join-Path $Output "operation with spaces $case"
    New-Item -ItemType Directory -Force $operation | Out-Null
    $installer = Join-Path $operation 'verified-setup.exe'
    Copy-Item -LiteralPath $fixture -Destination $installer
    if ($case -eq 'installer-failure') { Set-Content -LiteralPath ([IO.Path]::ChangeExtension($installer, '.code')) -Value 23 }
    $expected = (Get-FileHash -LiteralPath $installer -Algorithm SHA256).Hash
    if ($case -eq 'checksum-mismatch') { $expected = '0' * 64 }
    $parent = Start-Process -FilePath (Join-Path $PSHOME 'pwsh.exe') -ArgumentList '-NoLogo -NoProfile -Command "Start-Sleep -Seconds 30"' -PassThru
    $start = ([DateTimeOffset]$parent.StartTime.ToUniversalTime()).ToUnixTimeMilliseconds()
    $wait = if ($case -eq 'parent-timeout') { 1 } else { 10 }
    $command = @('-NoLogo', '-NoProfile', '-File', "`"$helper`"", '-ParentPid', $parent.Id, '-ParentStartMillis', $start,
        '-Installer', "`"$installer`"", '-Expected', $expected, '-Operation', "`"$operation`"", '-WaitSeconds', $wait)
    try {
        $process = Start-Process -FilePath (Join-Path $PSHOME 'pwsh.exe') -ArgumentList $command -PassThru `
            -RedirectStandardOutput (Join-Path $operation 'install.log') -RedirectStandardError (Join-Path $operation 'errors.log')
        $deadline = [DateTime]::UtcNow.AddSeconds(15)
        while (-not (Test-Path (Join-Path $operation 'armed')) -and -not $process.HasExited -and [DateTime]::UtcNow -lt $deadline) { Start-Sleep -Milliseconds 50; $process.Refresh() }
        if (-not (Test-Path (Join-Path $operation 'armed'))) { throw "Helper did not arm: $case" }
        if (Test-Path ([IO.Path]::ChangeExtension($installer, '.started'))) { throw 'Installer started before parent exit' }
        if ($case -ne 'parent-timeout') { Stop-Process -Id $parent.Id; $parent.WaitForExit() }
        if (-not $process.WaitForExit(20000)) { Stop-Process -Id $process.Id; throw 'Helper timed out' }
        $started = Test-Path ([IO.Path]::ChangeExtension($installer, '.started'))
        if ($case -eq 'parent-exit') {
            if ($process.ExitCode -ne 0 -or -not $started) { throw 'Successful installer handoff failed' }
        } else {
            if ($process.ExitCode -ne 1) { throw "Failure was incorrectly reported as success: $case" }
            if ($case -ne 'installer-failure' -and $started) { throw "Unverified/premature installer execution: $case" }
        }
        if (-not (Test-Path (Join-Path $operation 'completed'))) { throw 'Persistent terminal status is missing' }
        $results += [pscustomobject]@{ case = $case; helperExit = $process.ExitCode; started = $started }
    } finally { if (-not $parent.HasExited) { Stop-Process -Id $parent.Id } }
}
if ($Artifacts) {
    $msi = Join-Path $Artifacts "BitChord-$Version-windows-x64.msi"
    $exe = Join-Path $Artifacts "BitChord-$Version-windows-x64-setup.exe"
    $zip = Join-Path $Artifacts "BitChord-$Version-windows-x64-portable.zip"
    foreach ($path in @($msi, $exe, $zip)) { if (-not (Test-Path -LiteralPath $path)) { throw "Missing artifact: $path" } }
    $windowsInstaller = New-Object -ComObject WindowsInstaller.Installer
    $database = $windowsInstaller.OpenDatabase([IO.Path]::GetFullPath($msi), 0)
    function Property([string]$Name) {
        $view = $database.OpenView("SELECT Value FROM Property WHERE Property='$Name'")
        $view.Execute(); $record = $view.Fetch(); $value = $record.StringData(1); $view.Close(); return $value
    }
    $nativeVersion = Property 'ProductVersion'
    $upgrade = Property 'UpgradeCode'
    $product = Property 'ProductCode'
    if ($nativeVersion -notmatch '^\d+\.\d+\.\d+$') { throw "Invalid MSI version '$nativeVersion' for requested build '$Version'" }
    if ([int]$nativeVersion.Split('.')[2] -gt 65535) { throw 'MSI build exceeds its supported range' }
    if ($upgrade.Trim('{}') -ine '8f3c1d24-6a2b-4f5e-9c17-2b8d54e0a913' -or -not $product) { throw 'MSI upgrade/product identity changed' }
    $exeVersion = (Get-Item -LiteralPath $exe).VersionInfo.ProductVersion
    if ($exeVersion -notmatch ('^' + [Regex]::Escape($nativeVersion) + '(?:\.0)?$')) { throw 'EXE and MSI version metadata disagree' }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead([IO.Path]::GetFullPath($zip))
    try {
        $launchers = @($archive.Entries | Where-Object { $_.FullName -match '(^|/)BitChord\.exe$' })
        if ($launchers.Count -ne 1) { throw 'Portable ZIP must have exactly one launcher' }
        $prefix = $launchers[0].FullName -replace 'BitChord\.exe$', ''
        if (-not $archive.GetEntry("${prefix}.bitchord-portable")) { throw 'Portable format marker missing' }
        $cfg = $archive.GetEntry("${prefix}app/BitChord.cfg")
        if (-not $cfg) { throw 'Portable launcher config missing' }
        $reader = New-Object IO.StreamReader($cfg.Open())
        try { $text = $reader.ReadToEnd() } finally { $reader.Dispose() }
        if (-not $text.Contains("-Dbitchord.version=$Version")) { throw 'Portable app version was not injected' }
    } finally { $archive.Dispose() }
    $results += [pscustomobject]@{ case = 'actual-artifact-metadata'; nativeVersion = $nativeVersion; upgradeCode = $upgrade; productCode = $product; exeVersion = $exeVersion }
}
$results | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $Output 'results.json')
Write-Output 'Windows helper fixtures and requested artifact inspections passed. No real BitChord installation/upgrade was performed.'
