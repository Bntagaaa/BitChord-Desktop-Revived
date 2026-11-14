"""Offline contracts for screenshot comparisons (no generated app fixtures)."""
import tempfile
import json
from pathlib import Path
import unittest

from PIL import Image

from visual_parity import compare


class ComparisonTest(unittest.TestCase):
    def test_masks_exclude_exact_pixel_bounds_without_hiding_other_errors(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            a = Image.new('RGB', (2, 2), (0, 0, 0))
            b = a.copy()
            b.putpixel((0, 0), (255, 255, 255))
            b.putpixel((1, 1), (30, 30, 30))
            a.save(root / 'a.png')
            b.save(root / 'b.png')
            metrics = compare(root / 'a.png', root / 'b.png', root, 'masked', [(0, 0, 1, 1)])
            self.assertEqual(metrics['compared_pixels'], 3)
            self.assertEqual(metrics['excluded_pixels'], 1)
            self.assertEqual(metrics['mean_absolute_rgb_error_0_255'], 10)
            self.assertAlmostEqual(metrics['pixels_any_channel_difference_gt_8_percent'], 100 / 3)
            self.assertEqual(metrics['pixels_any_channel_difference_gt_32_percent'], 0)
            self.assertEqual(Image.open(root / 'overlays/masked.png').getpixel((0, 0)), (180, 180, 180))
            self.assertEqual(Image.open(root / 'b.png').getpixel((0, 0)), (255, 255, 255))

    def test_rejects_mismatched_sizes_and_invalid_masks(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            Image.new('RGB', (2, 2)).save(root / 'a.png')
            Image.new('RGB', (3, 2)).save(root / 'b.png')
            with self.assertRaises(ValueError):
                compare(root / 'a.png', root / 'b.png', root, 'size')
            for masks in [[(0, 0, 3, 1)], [(0, 0, 2, 2)], [(-1, 0, 1, 1)]]:
                with self.assertRaises(ValueError):
                    compare(root / 'a.png', root / 'a.png', root, 'mask', masks)

    def test_rejects_changed_capture_hash(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            Image.new('RGB', (2, 2)).save(root / 'a.png')
            (root / 'a.json').write_text(json.dumps({'sha256': 'changed'}))
            with self.assertRaisesRegex(ValueError, 'hash changed'):
                compare(root / 'a.png', root / 'a.png', root, 'hash')


if __name__ == '__main__':
    unittest.main()
