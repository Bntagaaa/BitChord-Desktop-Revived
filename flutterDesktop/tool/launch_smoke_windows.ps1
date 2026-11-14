param(
  [Parameter(Mandatory=$true)][string]$Executable,
  [string]$EvidenceDirectory = (Join-Path $PSScriptRoot '../verification'),
  [string]$SourceCommit = $env:GITHUB_SHA,
  [ValidatePattern('^[a-z-]+$')][string]$OutputPrefix = 'native-windows',
  [switch]$CaptureOnly
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Windows.Forms
Add-Type @'
using System;
using System.Runtime.InteropServices;
public class SmokeWindow {
  public delegate bool EnumWindowsProc(IntPtr h, IntPtr l);
  public class WindowInfo { public long handle; public int process_id; public string title; public bool visible; }
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumWindowsProc callback, IntPtr l);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out int pid);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowText(IntPtr h, System.Text.StringBuilder text, int max);
  public static WindowInfo[] OwnedWindows(int[] owners) {
    var result = new System.Collections.Generic.List<WindowInfo>();
    EnumWindows((h,l) => {
      int pid; GetWindowThreadProcessId(h,out pid);
      if (Array.IndexOf(owners,pid)>=0) {
        var title=new System.Text.StringBuilder(512); GetWindowText(h,title,512);
        result.Add(new WindowInfo {handle=h.ToInt64(),process_id=pid,title=title.ToString(),visible=IsWindowVisible(h)});
      }
      return true;
    },IntPtr.Zero);
    return result.ToArray();
  }
  [StructLayout(LayoutKind.Sequential)] public struct Rect { public int Left, Top, Right, Bottom; }
  [StructLayout(LayoutKind.Sequential)] public struct Point { public int X, Y; }
  [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
  [DllImport("user32.dll")] public static extern bool GetClientRect(IntPtr h, out Rect r);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out Rect r);
  [DllImport("user32.dll")] public static extern bool ClientToScreen(IntPtr h, ref Point p);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr h, IntPtr after, int x, int y, int w, int height, uint flags);
  [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr h, uint m, IntPtr w, IntPtr l);
  [DllImport("user32.dll")] public static extern uint GetDpiForWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern void keybd_event(byte key, byte scan, uint flags, UIntPtr extra);
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
  [DllImport("user32.dll")] public static extern void mouse_event(uint flags, uint x, uint y, uint data, UIntPtr extra);
}
'@
[void][SmokeWindow]::SetProcessDPIAware()
$evidence = [System.IO.Path]::GetFullPath($EvidenceDirectory)
[void](New-Item -ItemType Directory -Force $evidence)
$privateLogs = if ($CaptureOnly) { Join-Path $env:TEMP ([Guid]::NewGuid().ToString()) } else { $evidence }
[void](New-Item -ItemType Directory -Force $privateLogs)
$app = Start-Process -FilePath (Resolve-Path $Executable) -PassThru -RedirectStandardOutput "$privateLogs/$OutputPrefix.stdout.log" -RedirectStandardError "$privateLogs/$OutputPrefix.stderr.log"
$owners = [System.Collections.Generic.HashSet[int]]::new()
[void]$owners.Add($app.Id)
try {
  $deadline = [DateTime]::UtcNow.AddSeconds($(if ($CaptureOnly) { 120 } else { 30 }))
  $handle = [IntPtr]::Zero
  $nextProcessScan = [DateTime]::MinValue
  do {
    $app.Refresh()
    if ($CaptureOnly) {
      # A packaged JVM launcher can own a child/owner window that .NET's
      # MainWindowHandle heuristic excludes. Restrict enumeration to our tree.
      if ([DateTime]::UtcNow -ge $nextProcessScan) {
        $processes = @(Get-CimInstance Win32_Process | Select-Object ProcessId,ParentProcessId)
        do {
          $added = $false
          foreach ($process in $processes) {
            if ($owners.Contains([int]$process.ParentProcessId) -and $owners.Add([int]$process.ProcessId)) { $added = $true }
          }
        } while ($added)
        $nextProcessScan = [DateTime]::UtcNow.AddSeconds(1)
      }
      $ownerIds = [int[]]::new($owners.Count)
      $owners.CopyTo($ownerIds)
      $windows = @([SmokeWindow]::OwnedWindows($ownerIds))
      $candidate = $windows | Where-Object { $_.visible -and $_.title -eq 'BitChord' } | Select-Object -First 1
      if ($candidate) { $handle = [IntPtr]$candidate.handle; break }
    } else {
      if ($app.HasExited) { throw "Application exited before showing: $($app.ExitCode)" }
      $handle = $app.MainWindowHandle
      if ($handle -ne [IntPtr]::Zero -and [SmokeWindow]::IsWindowVisible($handle) -and $app.MainWindowTitle -eq 'BitChord') { break }
    }
    Start-Sleep -Milliseconds 200
  } while ([DateTime]::UtcNow -lt $deadline)
  if ($handle -eq [IntPtr]::Zero) {
    $diagnosticOwners = [int[]]::new($owners.Count)
    $owners.CopyTo($diagnosticOwners)
    $diagnostics = @{process_ids=$diagnosticOwners; windows=$windows; exception_types=@(); stacks=@()}
    if ($CaptureOnly) {
      # Retain only Java exception class names and stack frames. Never provider
      # log messages, request URLs or account/profile data.
      foreach ($file in Get-ChildItem $privateLogs -Filter '*.log') {
        $diagnostics.exception_types += @([regex]::Matches([string](Get-Content $file -Raw), '(?:[A-Za-z_$][\w$]*\.)+[A-Za-z_$][\w$]*(?:Exception|Error)') | ForEach-Object Value | Sort-Object -Unique)
      }
      foreach ($owner in $owners) {
        $trace = & "$env:JAVA_HOME/bin/jcmd.exe" $owner Thread.print 2>$null
        $diagnostics.stacks += @($trace | Where-Object { $_ -match '^\s+at (?:java|javax|kotlin|kotlinx|androidx|org\.jetbrains|com\.music)\.' } | Select-Object -First 100)
      }
      $diagnostics | ConvertTo-Json -Depth 5 | Set-Content "$evidence/$OutputPrefix-launch-failure.json"
    }
    throw 'No visible native BitChord window'
  }
  [void][SmokeWindow]::SetForegroundWindow($handle)
  $dpi = [SmokeWindow]::GetDpiForWindow($handle)
  if ($dpi -ne 96) { throw "Expected CI DPI 96 for matching client captures, observed $dpi" }
  $captures = @()
  Write-Output "Visible window=$handle DPI=$dpi screen=$([System.Windows.Forms.Screen]::PrimaryScreen.Bounds)"
  foreach ($size in @(@(1220,780), @(900,600))) {
    $client = New-Object SmokeWindow+Rect
    $outer = New-Object SmokeWindow+Rect
    [void][SmokeWindow]::GetClientRect($handle, [ref]$client)
    [void][SmokeWindow]::GetWindowRect($handle, [ref]$outer)
    $width = $size[0] + ($outer.Right - $outer.Left) - $client.Right
    $height = $size[1] + ($outer.Bottom - $outer.Top) - $client.Bottom
    if (-not [SmokeWindow]::SetWindowPos($handle, [IntPtr]::Zero, 20, 20, $width, $height, 0x0040)) { throw 'Resize failed' }
    Start-Sleep -Seconds 2
    $deadline = [DateTime]::UtcNow.AddSeconds(20)
    do {
      Start-Sleep -Milliseconds 250
      $app.Refresh()
      if (-not $CaptureOnly -and $app.HasExited) { throw 'Application exited during capture' }
      [void][SmokeWindow]::GetClientRect($handle, [ref]$client)
      if ($client.Right -ne $size[0] -or $client.Bottom -ne $size[1]) {
        Write-Output "Resize requested=$($size[0])x$($size[1]) observed=$($client.Right)x$($client.Bottom)"
        [void][SmokeWindow]::GetWindowRect($handle, [ref]$outer)
        $width = $size[0] + ($outer.Right - $outer.Left) - $client.Right
        $height = $size[1] + ($outer.Bottom - $outer.Top) - $client.Bottom
        [void][SmokeWindow]::SetWindowPos($handle, [IntPtr]::Zero, 20, 20, $width, $height, 0x0040)
        continue
      }
      $origin = New-Object SmokeWindow+Point
      [void][SmokeWindow]::ClientToScreen($handle, [ref]$origin)
      $bitmap = New-Object System.Drawing.Bitmap($client.Right, $client.Bottom)
      $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
      try {
        $graphics.CopyFromScreen($origin.X, $origin.Y, 0, 0, $bitmap.Size)
        $colors = [System.Collections.Generic.HashSet[int]]::new()
        for ($y=0; $y -lt $bitmap.Height; $y+=4) {
          for ($x=0; $x -lt $bitmap.Width; $x+=4) { [void]$colors.Add($bitmap.GetPixel($x,$y).ToArgb()) }
        }
        $sidebar = if ($client.Right -lt 980) { 72 } else { 220 }
        $edgePainted = $bitmap.GetPixel($sidebar-1,500).ToArgb() -ne $bitmap.GetPixel($sidebar,500).ToArgb()
        $center = [int](($client.Right + $sidebar) / 2)
        $minimum = 1.0; $maximum = 0.0
        for ($y=$client.Bottom-73; $y -lt $client.Bottom-25; $y++) {
          for ($x=$center-24; $x -lt $center+24; $x++) {
            $color = $bitmap.GetPixel($x,$y)
            $level = ($color.R + $color.G + $color.B) / 765.0
            $minimum = [Math]::Min($minimum,$level); $maximum = [Math]::Max($maximum,$level)
          }
        }
        $logoContrast = $maximum - $minimum
        $bitmap.Save("$evidence/$OutputPrefix-$($size[0])x$($size[1]).png", [System.Drawing.Imaging.ImageFormat]::Png)
      } finally { $graphics.Dispose(); $bitmap.Dispose() }
      if ($colors.Count -ge 50 -and $edgePainted -and $logoContrast -ge 0.25) { break }
    } while ([DateTime]::UtcNow -lt $deadline)
    if ($client.Right -ne $size[0] -or $client.Bottom -ne $size[1] -or $colors.Count -lt 50 -or -not $edgePainted -or $logoContrast -lt 0.25) { throw 'Client did not resize and paint shell landmarks' }
    $captures += @{width=$client.Right; height=$client.Bottom; sampled_colors=$colors.Count; logo_contrast=$logoContrast}
  }
  if ($CaptureOnly) {
    @{source_commit=$SourceCommit; dpi=$dpi; captures=$captures; display='GitHub Windows hosted desktop'; lifecycle='not tested; force-stop after reference capture'} | ConvertTo-Json -Depth 5 | Set-Content "$evidence/$OutputPrefix.json"
    Write-Output 'PASS: reference client captures only; native close/fullscreen not asserted.'
    return
  }
  function Click-Client([int]$x, [int]$y) {
    $origin = New-Object SmokeWindow+Point
    [void][SmokeWindow]::ClientToScreen($handle, [ref]$origin)
    [void][SmokeWindow]::SetCursorPos($origin.X+$x, $origin.Y+$y)
    [SmokeWindow]::mouse_event(2,0,0,0,[UIntPtr]::Zero)
    [SmokeWindow]::mouse_event(4,0,0,0,[UIntPtr]::Zero)
    Start-Sleep -Milliseconds 800
    [void][SmokeWindow]::SetCursorPos($origin.X+500, $origin.Y+400)
    Start-Sleep -Milliseconds 250
  }
  function Send-Key([byte]$key) {
    [SmokeWindow]::keybd_event($key,0,0,[UIntPtr]::Zero)
    [SmokeWindow]::keybd_event($key,0,2,[UIntPtr]::Zero)
    Start-Sleep -Milliseconds 500
  }
  function Save-State([string]$name, [string]$theme = 'any') {
    $deadline = [DateTime]::UtcNow.AddSeconds(8)
    do {
      $origin = New-Object SmokeWindow+Point
      [void][SmokeWindow]::ClientToScreen($handle, [ref]$origin)
      $bitmap = New-Object System.Drawing.Bitmap(900,600)
      $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
      try {
        $graphics.CopyFromScreen($origin.X,$origin.Y,0,0,$bitmap.Size)
        # The final 16 px are page gutter, outside both live artwork and dock.
        # The catalog now correctly extends beneath the dock, including y=500.
        $pixel = $bitmap.GetPixel(895,500)
        $rgb = @([int]$pixel.R,[int]$pixel.G,[int]$pixel.B)
        # Skia's 0.26 black scrim over the measured #F5F6FA page fallback.
        $valid = $theme -eq 'any' -or ($theme -eq 'drawer' -and $pixel.R -eq 181 -and $pixel.G -eq 182 -and $pixel.B -eq 185) -or ($theme -eq 'light' -and ($rgb | Measure-Object -Minimum).Minimum -ge 230) -or ($theme -eq 'dark' -and $pixel.R -eq 32 -and $pixel.G -eq 32 -and $pixel.B -eq 32) -or ($theme -eq 'black' -and ($rgb | Measure-Object -Maximum).Maximum -le 2)
        $bitmap.Save("$evidence/$OutputPrefix-$name-900x600.png",[System.Drawing.Imaging.ImageFormat]::Png)
      } finally { $graphics.Dispose(); $bitmap.Dispose() }
      if ($valid) { return @{state=$name; expected_theme=$theme; background_rgb=$rgb} }
      Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Real appearance selection did not render $theme; observed $rgb"
  }
  [void][SmokeWindow]::SetForegroundWindow($handle)
  Click-Client 36 516
  $states = @((Save-State 'accounts'))
  Send-Key 0x1B
  Click-Client 36 34
  $states += Save-State 'drawer' 'drawer'
  Send-Key 0x1B
  Click-Client 36 563
  $states += Save-State 'settings' 'light'
  Click-Client 120 127
  Click-Client 300 127
  $states += Save-State 'dark' 'dark'
  Click-Client 170 170
  $states += Save-State 'pitch-black' 'black'
  Click-Client 120 127
  Click-Client 230 127
  $states += Save-State 'light' 'light'
  [SmokeWindow]::keybd_event(0x12,0,0,[UIntPtr]::Zero)
  Send-Key 0x25
  [SmokeWindow]::keybd_event(0x12,0,2,[UIntPtr]::Zero)
  $origin = New-Object SmokeWindow+Point
  [void][SmokeWindow]::ClientToScreen($handle,[ref]$origin)
  [void][SmokeWindow]::SetCursorPos($origin.X+500,$origin.Y+400)
  # Actual OS keyboard input must reach the Dart window adapter and restore the
  # last client size. Keep this independent of widget-test window fakes.
  [void][SmokeWindow]::SetForegroundWindow($handle)
  [SmokeWindow]::keybd_event(0x7A,0,0,[UIntPtr]::Zero)
  [SmokeWindow]::keybd_event(0x7A,0,2,[UIntPtr]::Zero)
  $deadline = [DateTime]::UtcNow.AddSeconds(10)
  do {
    Start-Sleep -Milliseconds 200
    [void][SmokeWindow]::GetClientRect($handle, [ref]$client)
    $screen = [System.Windows.Forms.Screen]::FromHandle($handle).Bounds
    if ($client.Right -eq $screen.Width -and $client.Bottom -eq $screen.Height) { break }
  } while ([DateTime]::UtcNow -lt $deadline)
  if ($client.Right -ne $screen.Width -or $client.Bottom -ne $screen.Height) { throw 'F11 did not enter native fullscreen' }
  [SmokeWindow]::keybd_event(0x1B,0,0,[UIntPtr]::Zero)
  [SmokeWindow]::keybd_event(0x1B,0,2,[UIntPtr]::Zero)
  $deadline = [DateTime]::UtcNow.AddSeconds(10)
  do {
    Start-Sleep -Milliseconds 200
    [void][SmokeWindow]::GetClientRect($handle, [ref]$client)
    if ($client.Right -eq 900 -and $client.Bottom -eq 600) { break }
  } while ([DateTime]::UtcNow -lt $deadline)
  if ($client.Right -ne 900 -or $client.Bottom -ne 600) { throw 'Escape did not restore the prior client size' }
  # WM_CLOSE exercises the real window listener and preference flush, rather
  # than killing a compiled process and calling that lifecycle verification.
  [void][SmokeWindow]::PostMessage($handle, 0x0010, [IntPtr]::Zero, [IntPtr]::Zero)
  if (-not $app.WaitForExit(10000)) { throw 'Native close timed out' }
  if ($app.ExitCode -ne 0) { throw "Native close exit code: $($app.ExitCode)" }
  @{source_commit=$SourceCommit; dpi=$dpi; captures=$captures; appearance_captures=$states; exit_code=$app.ExitCode; fullscreen='F11 entered; Escape restored 900x600'; display='GitHub Windows hosted desktop'} | ConvertTo-Json -Depth 5 | Set-Content "$evidence/$OutputPrefix.json"
  Write-Output 'PASS: painted Windows client, two resizes, fullscreen/restore, native close, exit 0.'
} finally {
  if ($CaptureOnly) {
    foreach ($owner in $owners) { Stop-Process -Id $owner -Force -ErrorAction SilentlyContinue }
  }
  $app.Refresh()
  if (-not $app.HasExited) { Stop-Process -Id $app.Id -Force }
  if ($CaptureOnly) { Remove-Item -LiteralPath $privateLogs -Recurse -Force }
}
