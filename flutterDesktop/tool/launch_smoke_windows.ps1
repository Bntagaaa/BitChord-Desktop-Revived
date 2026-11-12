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
}
'@
[void][SmokeWindow]::SetProcessDPIAware()
$evidence = [System.IO.Path]::GetFullPath($EvidenceDirectory)
[void](New-Item -ItemType Directory -Force $evidence)
$privateLogs = if ($CaptureOnly) { Join-Path $env:TEMP ([Guid]::NewGuid().ToString()) } else { $evidence }
[void](New-Item -ItemType Directory -Force $privateLogs)
$app = Start-Process -FilePath (Resolve-Path $Executable) -PassThru -RedirectStandardOutput "$privateLogs/$OutputPrefix.stdout.log" -RedirectStandardError "$privateLogs/$OutputPrefix.stderr.log"
try {
  $deadline = [DateTime]::UtcNow.AddSeconds(30)
  do {
    $app.Refresh()
    if ($app.HasExited) { throw "Application exited before showing: $($app.ExitCode)" }
    $handle = $app.MainWindowHandle
    if ($handle -ne [IntPtr]::Zero -and [SmokeWindow]::IsWindowVisible($handle) -and $app.MainWindowTitle -eq 'BitChord') { break }
    Start-Sleep -Milliseconds 200
  } while ([DateTime]::UtcNow -lt $deadline)
  if ($handle -eq [IntPtr]::Zero) { throw 'No visible native BitChord window' }
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
      if ($app.HasExited) { throw 'Application exited during capture' }
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
  @{source_commit=$SourceCommit; dpi=$dpi; captures=$captures; exit_code=$app.ExitCode; fullscreen='F11 entered; Escape restored 900x600'; display='GitHub Windows hosted desktop'} | ConvertTo-Json -Depth 5 | Set-Content "$evidence/$OutputPrefix.json"
  Write-Output 'PASS: painted Windows client, two resizes, fullscreen/restore, native close, exit 0.'
} finally {
  $app.Refresh()
  if (-not $app.HasExited) { Stop-Process -Id $app.Id -Force }
  if ($CaptureOnly) { Remove-Item -LiteralPath $privateLogs -Recurse -Force }
}
