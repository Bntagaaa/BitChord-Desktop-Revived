param(
    [Parameter(Mandatory=$true)][string]$Request,
    [switch]$Headless,
    [ValidateRange(1, 600)][int]$WaitSeconds = 120,
    [ValidateRange(1, 300)][int]$ReadySeconds = 60
)
# A separate Windows process owns the entire handoff, both for ZIP and setup.
# Use only this engine's built-in modules: pwsh 7 may have supplied a module
# path that causes Windows PowerShell 5.1 to import incompatible utilities.
$ErrorActionPreference = 'Stop'
$originalModulePath = $env:PSModulePath
$env:PSModulePath = [IO.Path]::Combine($PSHOME, 'Modules')
$operation = [IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($Request))
$form = $null
$label = $null
$bar = $null
$closeButton = $null
$script:terminal = $false
$mutex = $null
$ownsMutex = $false
$lease = $null
$worker = $null
$resultCode = 1
$result = 'host-failed-before-handoff'

function Log([string]$message) { Write-Output "$([DateTime]::UtcNow.ToString('o')) stage=$message" }
function State([string]$stage, [string]$message) {
    Set-Content -LiteralPath (Join-Path $operation 'status') -Value $stage -Encoding utf8
    Log $stage
    if ($label) { $label.Text = $message }
    if ($form) { [Windows.Forms.Application]::DoEvents() }
}
function Pause-UI {
    if ($form) { [Windows.Forms.Application]::DoEvents() }
    Start-Sleep -Milliseconds 100
}
function Assert-Regular([string]$path, [switch]$Directory) {
    $item = Get-Item -LiteralPath $path -Force -ErrorAction Stop
    if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 -or
        $item.PSIsContainer -ne [bool]$Directory) { throw "Unsafe updater path: $path" }
}
function Assert-NoReparseAncestors([string]$path) {
    $at = [IO.Path]::GetFullPath($path)
    while ($at) {
        if (Test-Path -LiteralPath $at) {
            $item = Get-Item -LiteralPath $at -Force
            if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Reparse point in updater path: $at" }
        }
        $at = [IO.Path]::GetDirectoryName($at.TrimEnd('\'))
    }
}
function Alive([long]$processId, [long]$startMillis) {
    if ($processId -eq 0 -and $startMillis -eq 0) { return $false }
    if ($processId -le 1 -or $startMillis -le 0) { throw 'Invalid original process identity' }
    $p = Get-Process -Id $processId -ErrorAction SilentlyContinue
    if (-not $p) { return $false }
    # An inaccessible live process is not proof of shutdown: fail closed.
    $started = ([DateTimeOffset]$p.StartTime.ToUniversalTime()).ToUnixTimeMilliseconds()
    return [Math]::Abs($started - $startMillis) -le 10
}
function Quote([string]$value) {
    if ($value -match '["\x00\r\n]') { throw 'Invalid command argument' }
    return '"' + ($value -replace '(\\+)$', '$1$1') + '"'
}
function Read-Fields([string]$file) {
    Assert-Regular $file
    if ((Get-Item -LiteralPath $file).Length -gt 4096) { throw 'Oversized updater marker' }
    $values = @{}
    foreach ($line in [IO.File]::ReadAllLines($file)) {
        $split = $line.IndexOf('=')
        if ($split -gt 0) { $values[$line.Substring(0, $split)] = $line.Substring($split + 1) }
    }
    return $values
}
function Show-Updater {
    if ($Headless) { return }
    Add-Type -AssemblyName System.Windows.Forms
    Add-Type -AssemblyName System.Drawing
    [Windows.Forms.Application]::EnableVisualStyles()
    $script:form = New-Object Windows.Forms.Form
    $form.Text = 'BitChord Updater'
    $form.ClientSize = New-Object Drawing.Size(520, 180)
    $form.StartPosition = 'CenterScreen'
    $form.FormBorderStyle = 'FixedDialog'
    $form.MaximizeBox = $false
    $script:label = New-Object Windows.Forms.Label
    $label.Location = New-Object Drawing.Point(20, 20)
    $label.Size = New-Object Drawing.Size(480, 65)
    $label.Text = 'Preparing Windows update...'
    $script:bar = New-Object Windows.Forms.ProgressBar
    $bar.Location = New-Object Drawing.Point(20, 90)
    $bar.Size = New-Object Drawing.Size(480, 14)
    $bar.Style = 'Marquee'
    $logs = New-Object Windows.Forms.Button
    $logs.Text = 'Open logs'
    $logs.Location = New-Object Drawing.Point(20, 130)
    $logs.Add_Click({ Start-Process -FilePath (Join-Path $env:SystemRoot 'explorer.exe') -ArgumentList (Quote $operation) })
    $script:closeButton = New-Object Windows.Forms.Button
    $closeButton.Text = 'Close'
    $closeButton.Location = New-Object Drawing.Point(425, 130)
    $closeButton.Enabled = $false
    $closeButton.Add_Click({ $form.Close() })
    $form.Controls.AddRange(@($label, $bar, $logs, $closeButton))
    $form.Add_FormClosing({ param($sender, $eventArgs) if (-not $script:terminal) { $eventArgs.Cancel = $true } })
    $form.Show()
    [Windows.Forms.Application]::DoEvents()
}

try {
    Assert-Regular $Request
    Assert-NoReparseAncestors $operation
    if ((Get-Item -LiteralPath $Request).Length -gt 16384) { throw 'Oversized update request' }
    $requestData = Get-Content -LiteralPath $Request -Raw -Encoding utf8 | ConvertFrom-Json
    if ($requestData.schema -ne 1 -or $requestData.mode -notin @('portable', 'setup') -or
        $requestData.expected -notmatch '^[a-fA-F0-9]{64}$' -or
        $requestData.token -notmatch '^[a-f0-9]{32}$' -or
        $requestData.version -notmatch '^[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?$') { throw 'Invalid update request' }
    $application = [IO.Path]::GetFullPath([string]$requestData.application)
    if (-not [IO.Path]::IsPathRooted([string]$requestData.application) -or
        [IO.Path]::GetFileName($application) -ine 'BitChord.exe') { throw 'Invalid application launcher' }
    $appRoot = [IO.Path]::GetDirectoryName($application)
    Assert-NoReparseAncestors $appRoot
    Assert-Regular $application
    Assert-Regular (Join-Path $appRoot 'app/BitChord.cfg')
    if ($operation.Equals($appRoot, [StringComparison]::OrdinalIgnoreCase) -or
        $operation.StartsWith($appRoot.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Updater must run outside the installation' }
    if (Test-Path -LiteralPath (Join-Path $operation 'completed')) { throw 'Operation already completed; duplicate update refused' }
    if (Test-Path -LiteralPath (Join-Path $operation 'launch.requested')) { throw 'Relaunch already requested; duplicate launch refused' }
    $payloadDir = Join-Path $operation 'payload'
    Assert-Regular $payloadDir -Directory
    $scriptFile = Join-Path $payloadDir 'worker.ps1'
    $packageName = if ($requestData.mode -eq 'portable') { 'verified-portable.zip' } else { 'verified-setup.exe' }
    $packageFile = Join-Path $payloadDir $packageName
    Assert-Regular $scriptFile
    Assert-Regular $packageFile
    if ($requestData.mode -eq 'portable') { Assert-Regular (Join-Path $appRoot '.bitchord-portable') }
    $tokens = $null; $parseErrors = $null
    [Management.Automation.Language.Parser]::ParseFile($scriptFile, [ref]$tokens, [ref]$parseErrors) > $null
    if ($parseErrors.Count) { throw "Bundled worker has a syntax error: $($parseErrors[0].Message)" }
    if ($requestData.workerSha256 -notmatch '^[a-f0-9]{64}$' -or
        (Get-FileHash -LiteralPath $scriptFile -Algorithm SHA256).Hash -ine $requestData.workerSha256) { throw 'Worker checksum mismatch' }
    $lease = [IO.File]::Open((Join-Path $operation 'host.lock'), [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    $sha = [Security.Cryptography.SHA256]::Create()
    try { $key = [BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($application.ToLowerInvariant()))).Replace('-', '') }
    finally { $sha.Dispose() }
    $mutex = [Threading.Mutex]::new($false, ('Local\BitChordUpdater-' + $key))
    try { $ownsMutex = $mutex.WaitOne(0) } catch [Threading.AbandonedMutexException] { $ownsMutex = $true }
    if (-not $ownsMutex) { throw 'Another updater is already handling this installation' }
    $hostStart = ([DateTimeOffset](Get-Process -Id $PID).StartTime.ToUniversalTime()).ToUnixTimeMilliseconds()
    [IO.File]::WriteAllText((Join-Path $operation 'helper.identity'), "pid=$PID`nstart_millis=$hostStart`n", [Text.Encoding]::ASCII)
    [IO.File]::WriteAllText((Join-Path $operation 'request.token'), [string]$requestData.token, [Text.Encoding]::ASCII)
    Show-Updater
    State 'verifying-package' 'Verifying the downloaded update...'
    if ((Get-FileHash -LiteralPath $packageFile -Algorithm SHA256).Hash -ine $requestData.expected) { throw 'Package checksum mismatch; app was not closed' }
    [void](Alive ([long]$requestData.parentPid) ([long]$requestData.parentStartMillis))
    [void](Alive ([long]$requestData.launcherPid) ([long]$requestData.launcherStartMillis))
    [IO.File]::WriteAllText((Join-Path $operation 'armed'), 'armed', [Text.Encoding]::ASCII)
    State 'waiting-for-app-exit' 'Waiting for BitChord to save and close...'
    $deadline = [DateTime]::UtcNow.AddSeconds($WaitSeconds)
    while ((Alive ([long]$requestData.parentPid) ([long]$requestData.parentStartMillis)) -or
        (Alive ([long]$requestData.launcherPid) ([long]$requestData.launcherStartMillis))) {
        if (Test-Path -LiteralPath (Join-Path $operation 'abort')) { throw 'Update aborted before shutdown' }
        if ([DateTime]::UtcNow -ge $deadline) { throw 'BitChord did not exit; installation left unchanged' }
        Pause-UI
    }
    if (Test-Path -LiteralPath (Join-Path $operation 'abort')) { throw 'Update aborted before worker launch' }
    State 'installing-update' 'Installing update. Complete the setup wizard if it appears.'
    $arguments = @('-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', $scriptFile,
        '-ParentPid', [string]$requestData.parentPid, '-ParentStartMillis', [string]$requestData.parentStartMillis,
        '-Expected', [string]$requestData.expected, '-Operation', $payloadDir)
    if ($requestData.mode -eq 'portable') {
        $arguments += @('-Package', $packageFile, '-PortableRoot', $appRoot, '-SkipLaunch')
    } else { $arguments += @('-Installer', $packageFile) }
    $powershell = Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/powershell.exe'
    $worker = Start-Process -FilePath $powershell -ArgumentList (($arguments | ForEach-Object { Quote $_ }) -join ' ') -WorkingDirectory $operation -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $operation 'worker.log') -RedirectStandardError (Join-Path $operation 'worker-error.log')
    while (-not $worker.HasExited) { Pause-UI; $worker.Refresh() }
    $worker.WaitForExit()
    $workerStatusPath = Join-Path $payloadDir 'status'
    $workerStatus = if (Test-Path -LiteralPath $workerStatusPath) { (Get-Content -LiteralPath $workerStatusPath -Raw).Trim() } else { 'missing-worker-status' }
    Log "worker-exited code=$($worker.ExitCode) result=$workerStatus"
    foreach ($name in @('status', 'completed', 'failure.detail', 'backup.path')) {
        $from = Join-Path $payloadDir $name
        if (Test-Path -LiteralPath $from -PathType Leaf) { Copy-Item -LiteralPath $from -Destination (Join-Path $operation ('worker-' + $name)) }
    }
    if ($worker.ExitCode -ne 0) { throw "Installation failed or was cancelled: $workerStatus" }
    if ($workerStatus -like '*reboot-required*') {
        $result = 'reboot-required; no automatic app launch'
        $resultCode = 2
        State $result 'Windows needs a reboot to finish the update. BitChord was not relaunched.'
    } else {
        $expectedStatus = if ($requestData.mode -eq 'portable') { 'success-portable-replaced;*' } else { 'installer-reported-success;*' }
        if ($workerStatus -notlike $expectedStatus) { throw "Worker did not confirm replacement: $workerStatus" }
        State 'verifying-installed-version' 'Checking the installed version before restarting...'
        Assert-NoReparseAncestors $application
        Assert-Regular $application
        $cfg = Join-Path $appRoot 'app/BitChord.cfg'
        Assert-Regular $cfg
        if ((Get-Item -LiteralPath $cfg).Length -gt 1048576) { throw 'Invalid launcher config size' }
        $versions = @([IO.File]::ReadAllLines($cfg) | Where-Object { $_ -match '^java-options=-Dbitchord\.version=' } | ForEach-Object { $_.Substring('java-options=-Dbitchord.version='.Length).Trim() })
        if ($versions.Count -ne 1 -or $versions[0] -cne [string]$requestData.version) { throw 'Target version is not installed at the original path; refusing to relaunch an old or unrelated app' }
        $launchGuard = [IO.File]::Open((Join-Path $operation 'launch.requested'), [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::Read)
        $launchGuard.Dispose()
        $start = New-Object Diagnostics.ProcessStartInfo
        $start.FileName = $application
        $start.WorkingDirectory = $appRoot
        $start.UseShellExecute = $false
        if ($originalModulePath) { $start.EnvironmentVariables['PSModulePath'] = $originalModulePath }
        else { $start.EnvironmentVariables.Remove('PSModulePath') }
        $start.EnvironmentVariables['BITCHORD_UPDATE_READY_FILE'] = Join-Path $operation 'window-ready'
        $start.EnvironmentVariables['BITCHORD_UPDATE_TOKEN'] = [string]$requestData.token
        $start.EnvironmentVariables['BITCHORD_UPDATE_EXPECTED_VERSION'] = [string]$requestData.version
        $start.EnvironmentVariables['BITCHORD_UPDATE_EXPECTED_LAUNCHER'] = $application
        $launchedAt = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
        $candidate = [Diagnostics.Process]::Start($start)
        State 'waiting-for-updated-window' 'Opening the updated BitChord...'
        $ready = Join-Path $operation 'window-ready'
        $deadline = [DateTime]::UtcNow.AddSeconds($ReadySeconds)
        $confirmed = $false
        while ([DateTime]::UtcNow -lt $deadline) {
            if (Test-Path -LiteralPath $ready -PathType Leaf) {
                $fields = Read-Fields $ready
                if ($fields['token'] -ceq [string]$requestData.token -and $fields['version'] -ceq [string]$requestData.version -and
                    $fields['state'] -eq 'gui-ready' -and $fields['launcher_path'] -ieq $application -and
                    $fields['pid'] -match '^\d+$' -and $fields['start_millis'] -match '^\d+$' -and
                    [long]$fields['start_millis'] -ge ($launchedAt - 1000) -and
                    (Alive ([long]$fields['pid']) ([long]$fields['start_millis']))) {
                    $ack = Get-Process -Id ([int]$fields['pid'])
                    if ($ack.Path -ieq $application -or $ack.Path -ieq (Join-Path $appRoot 'runtime/bin/java.exe') -or $ack.Path -ieq (Join-Path $appRoot 'runtime/bin/javaw.exe')) {
                        $confirmed = $true
                        $result = 'success-gui-confirmed'
                        break
                    }
                }
            }
            # Older target versions lack the token handshake. Observe only a NEW
            # visible window from the exact version-checked launcher in that case.
            foreach ($visible in @(Get-Process -Name 'BitChord' -ErrorAction SilentlyContinue)) {
                try {
                    if ($visible.Path -ieq $application -and $visible.MainWindowHandle -ne [IntPtr]::Zero -and
                        ([DateTimeOffset]$visible.StartTime.ToUniversalTime()).ToUnixTimeMilliseconds() -ge ($launchedAt - 1000)) {
                        $confirmed = $true
                        $result = 'success-window-observed'
                        break
                    }
                } catch { }
            }
            if ($confirmed) { break }
            Pause-UI
        }
        if ($confirmed) {
            $resultCode = 0
            State $result 'BitChord is updated and running.'
        } else {
            $resultCode = 2
            $result = 'relaunch-requested-unconfirmed; no second launch or rollback'
            State $result 'The update is installed and launch was requested, but startup was not confirmed. Check logs; no duplicate app was opened.'
        }
    }
} catch {
    $result = 'windows-updater-failed'
    $resultCode = 1
    $detail = $_.Exception.Message
    Log "host-failed reason=$detail"
    if ($lease) {
        Set-Content -LiteralPath (Join-Path $operation 'failure.detail') -Value $detail -Encoding utf8
        State $result ("Update stopped: " + $detail)
    }
} finally {
    # Never discard payloads while a worker or installer might still be using them.
    if ($lease -and (-not $worker -or $worker.HasExited)) {
        $payloadDir = Join-Path $operation 'payload'
        foreach ($name in @('verified-portable.zip', 'verified-setup.exe', 'worker.ps1', 'msi-source.ps1',
            'helper.identity', 'armed', 'status', 'completed', 'failure.detail', 'backup.path')) {
            $path = Join-Path $payloadDir $name
            if (Test-Path -LiteralPath $path -PathType Leaf) {
                try { Assert-Regular $path; Remove-Item -LiteralPath $path -Force } catch { Log 'payload-cleanup-deferred' }
            }
        }
        if (Test-Path -LiteralPath $payloadDir -PathType Container) {
            try { [IO.Directory]::Delete($payloadDir, $false) } catch { }
        }
        Set-Content -LiteralPath (Join-Path $operation 'completed') -Value $result -Encoding utf8
    }
    $script:terminal = $true
    if ($form) {
        $bar.Style = 'Continuous'
        $closeButton.Enabled = $true
        if ($resultCode -eq 0) { $form.Close() }
        while (-not $form.IsDisposed) { Pause-UI }
        $form.Dispose()
    }
    if ($ownsMutex) { $mutex.ReleaseMutex() }
    if ($mutex) { $mutex.Dispose() }
    if ($lease) { $lease.Dispose() }
}
exit $resultCode
