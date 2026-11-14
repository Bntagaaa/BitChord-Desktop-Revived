"""Capture real X11/XWayland clients and compare equal-sized PNGs.

Requires Pillow, ImageMagick import and xdotool. Never generates app content.
Only use disposable signed-out profiles; inspect PNGs before committing them.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time

from PIL import Image, ImageChops, ImageDraw, ImageStat


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def write_json(path, value):
    Path(path).write_text(json.dumps(value, indent=2) + '\n')


def capture(args):
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    window = str(int(args.window, 0))
    pid = int(subprocess.check_output(['xdotool', 'getwindowpid', window], text=True).strip())
    if args.expected_pid is not None and args.expected_pid != pid:
        raise ValueError('Window PID does not match the requested application')
    subprocess.run(['xdotool', 'windowactivate', '--sync', window], check=True, timeout=10)
    subprocess.run(['xdotool', 'windowsize', '--sync', window, str(args.width), str(args.height)],
                   check=True, timeout=10)
    # Compositors may reposition a resized window after the X11 size changes.
    time.sleep(.5)
    # Leave the pointer in a blank client corner so tooltips/hover don't pollute captures.
    # --sync can wait forever when the pointer is already at this position.
    subprocess.run(['xdotool', 'mousemove', '--window', window,
                    str(args.width - 3), str(args.height - 3)], check=True, timeout=10)
    time.sleep(args.settle)
    raw = subprocess.check_output(['xdotool', 'getwindowgeometry', '--shell', window], text=True)
    geometry = dict(line.split('=', 1) for line in raw.splitlines() if '=' in line)
    if (int(geometry['WIDTH']), int(geometry['HEIGHT'])) != (args.width, args.height):
        raise ValueError('Client resize failed: ' + raw)
    # Hex IDs work with ImageMagick 6 and 7. Capture only this app, never the desktop.
    subprocess.run(['import', '-window', hex(int(window)), '-depth', '8', str(output)],
                   check=True, timeout=15)
    with Image.open(output) as frame:
        if frame.size != (args.width, args.height):
            raise ValueError('Screenshot dimensions do not match client geometry')
        if frame.convert('RGB').getextrema() == ((0, 0), (0, 0), (0, 0)):
            raise ValueError('Black framebuffer; capture is invalid')
    write_json(output.with_suffix('.json'), {
        'captured_utc': datetime.now(timezone.utc).isoformat(),
        'method': 'real visible X11/XWayland client; ImageMagick import -window',
        'display': os.environ.get('DISPLAY'), 'state': args.state,
        'window_pid': pid,
        'source': args.source, 'theme': args.theme, 'ui_scale_percent': args.scale,
        'display_scale': args.display_scale, 'font': args.font,
        'geometry': geometry, 'sha256': digest(output),
    })


def compare(reference, flutter, root, name, masks=()):
    captures = []
    for path in (reference, flutter):
        sidecar = Path(path).with_suffix('.json')
        metadata = json.loads(sidecar.read_text()) if sidecar.exists() else None
        if metadata is not None and metadata['sha256'] != digest(path):
            raise ValueError('Capture hash changed: ' + str(path))
        captures.append(metadata)
    if all(captures):
        for field in ('state', 'theme', 'ui_scale_percent', 'display_scale'):
            if captures[0][field] != captures[1][field]:
                raise ValueError('Unmatched capture ' + field)
    left, right = (Image.open(path).convert('RGB') for path in (reference, flutter))
    if left.size != right.size:
        raise ValueError(f'Unequal clients: {left.size} versus {right.size}; never resize captures')
    valid = Image.new('L', left.size, 255)
    draw = ImageDraw.Draw(valid)
    width, height = left.size
    for rect in masks:
        x, y, w, h = rect
        if min(x, y) < 0 or min(w, h) <= 0 or x + w > width or y + h > height:
            raise ValueError('Mask is outside the captured client')
        draw.rectangle((x, y, x + w - 1, y + h - 1), fill=0)
    compared = valid.histogram()[255]
    if not compared:
        raise ValueError('Masks exclude every pixel')
    difference = ImageChops.difference(left, right)
    stats = ImageStat.Stat(difference, valid)
    max_channel = ImageChops.lighter(ImageChops.lighter(*difference.split()[:2]), difference.split()[2])
    histogram = max_channel.histogram(mask=valid)
    metrics = {
        'reference_sha256': digest(reference), 'flutter_sha256': digest(flutter),
        'size': list(left.size), 'excluded_rectangles_xywh': list(masks),
        'compared_pixels': compared, 'excluded_pixels': width * height - compared,
        'capture_metadata_checked': all(captures),
        'mean_absolute_rgb_error_0_255': sum(stats.mean) / 3,
        'pixels_any_channel_difference_gt_8_percent': 100 * sum(histogram[9:]) / compared,
        'pixels_any_channel_difference_gt_32_percent': 100 * sum(histogram[33:]) / compared,
        'acceptance': 'Supporting metrics only; requires human visual review and equivalent states',
    }
    # Excluded pixels are visibly gray in every derivative; originals stay untouched.
    excluded = ImageChops.invert(valid)
    for frame in (left, right):
        frame.paste((180, 180, 180), mask=excluded)
    overlay = Image.blend(left, right, .5)
    heat = Image.merge('RGB', (max_channel.point(lambda v: min(255, v * 4)),
                               Image.new('L', left.size), Image.new('L', left.size)))
    heat.paste((180, 180, 180), mask=excluded)
    pair = Image.new('RGB', (width * 2, height))
    pair.paste(left, (0, 0))
    pair.paste(right, (width, 0))
    for folder, frame in [('diff', heat), ('overlays', overlay), ('reports', pair)]:
        destination = Path(root) / folder
        destination.mkdir(parents=True, exist_ok=True)
        frame.save(destination / f'{name}.png')
    write_json(Path(root) / 'reports' / f'{name}.json', metrics)
    return metrics


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    shot = commands.add_parser('capture')
    for field in ('window', 'output', 'state', 'source', 'theme', 'font', 'display-scale'):
        shot.add_argument('--' + field, required=True)
    shot.add_argument('--width', type=int, default=1220)
    shot.add_argument('--height', type=int, default=780)
    shot.add_argument('--scale', type=int, default=100)
    shot.add_argument('--settle', type=float, default=1.5)
    shot.add_argument('--expected-pid', type=int)
    diff = commands.add_parser('compare')
    for field in ('reference', 'flutter', 'root', 'name'):
        diff.add_argument('--' + field, required=True)
    diff.add_argument('--mask', action='append', default=[], help='Excluded x,y,width,height; repeatable')
    args = parser.parse_args()
    if args.command == 'capture':
        capture(args)
    else:
        masks = [tuple(map(int, value.split(','))) for value in args.mask]
        print(json.dumps(compare(args.reference, args.flutter, args.root, args.name, masks), indent=2))


if __name__ == '__main__':
    main()
