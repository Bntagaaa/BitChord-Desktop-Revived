param([long]$ParentPid, [string]$Installer, [string]$Expected)
$ErrorActionPreference = 'Stop'
Wait-Process -Id $ParentPid -Timeout 120 -ErrorAction SilentlyContinue
if (Get-Process -Id $ParentPid -ErrorAction SilentlyContinue) { exit 1 }
if ((Get-FileHash -LiteralPath $Installer -Algorithm SHA256).Hash.ToLowerInvariant() -ne $Expected) { exit 1 }
# Interactive wizard retains Windows' ordinary UAC and user approval behavior.
Start-Process -FilePath $Installer
