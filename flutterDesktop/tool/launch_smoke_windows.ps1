param([Parameter(Mandatory=$true)][string]$Executable)
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
}
'@
[void][SmokeWindow]::SetProcessDPIAware()
$evidence = Join-Path $PSScriptRoot '../verification'
[void](New-Item -ItemType Directory -Force $evidence)
$app = Start-Process -FilePath (Resolve-Path $Executable) -PassThru -RedirectStandardOutput "$evidence/native-windows.stdout.log" -RedirectStandardError "$evidence/native-windows.stderr.log"
try {
  $deadline = [DateTime]::UtcNow.AddSeconds(30)
  do {
    $app.Refresh()
    if ($app.HasExited) { throw "Application exited before showing: $($app.ExitCode)" }
    $handle = $app.MainWindowHandle
    if ($handle -ne [IntPtr]::Zero -and [SmokeWindow]::IsWindowVisible($handle)) { break }
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
        $bitmap.Save("$evidence/native-windows-$($size[0])x$($size[1]).png", [System.Drawing.Imaging.ImageFormat]::Png)
      } finally { $graphics.Dispose(); $bitmap.Dispose() }
      if ($colors.Count -ge 50) { break }
    } while ([DateTime]::UtcNow -lt $deadline)
    if ($client.Right -ne $size[0] -or $client.Bottom -ne $size[1] -or $colors.Count -lt 50) { throw 'Client did not resize and paint' }
    $captures += @{width=$client.Right; height=$client.Bottom; sampled_colors=$colors.Count}
  }
  # WM_CLOSE exercises the real window listener and preference flush, rather
  # than killing a compiled process and calling that lifecycle verification.
  [void][SmokeWindow]::PostMessage($handle, 0x0010, [IntPtr]::Zero, [IntPtr]::Zero)
  if (-not $app.WaitForExit(10000)) { throw 'Native close timed out' }
  if ($app.ExitCode -ne 0) { throw "Native close exit code: $($app.ExitCode)" }
  @{dpi=$dpi; captures=$captures; exit_code=$app.ExitCode; display='GitHub Windows hosted desktop'} | ConvertTo-Json -Depth 5 | Set-Content "$evidence/native-windows.json"
  Write-Output 'PASS: visible painted Windows client, two resizes, native close, exit 0.'
} finally {
  $app.Refresh()
  if (-not $app.HasExited) { Stop-Process -Id $app.Id -Force }
}
