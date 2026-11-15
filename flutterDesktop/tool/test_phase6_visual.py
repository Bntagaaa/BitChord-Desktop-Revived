"""Coverage honesty contracts, executed on the hosted Linux runner."""
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest

from PIL import Image

from phase6_visual import coverage_inventory, report, ROOT


class Phase6CoverageTest(unittest.TestCase):
    def test_inventory_keeps_all_reference_destinations_overlays_and_settings(self):
        source = json.loads((ROOT / 'verification/phase2/reference-inventory.json').read_text())
        rows = coverage_inventory(source)
        ids = {row['id'] for row in rows}
        self.assertTrue({f'route/{route["id"]}' for route in source['routes']} <= ids)
        self.assertTrue({f'overlay/{name}' for name in source['overlays']} <= ids)
        self.assertTrue({f'settings-page/{name}' for name in source['settings_pages']} <= ids)
        self.assertEqual(len([name for name in ids if name.startswith('settings-section/')]), 18)
        self.assertIn('interaction/fullscreen-player', ids)
        self.assertTrue(all(row['parity'] == 'NOT VERIFIED' for row in rows))
        self.assertEqual(len(ids), len(rows))

    def test_identical_screenshots_do_not_accept_unvisited_states_or_interactions(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            clients = []
            state = 'route-listen-now-1220x780'
            for kind in ('reference', 'flutter'):
                path = root / f'{kind}.png'
                Image.new('RGB', (4, 4), 'white').save(path)
                clients.append(SimpleNamespace(kind=kind, captures={state: {
                    'path': path.name, 'status': 'CAPTURED; route/state requires review'}}))
            rows = [{'id': 'route/listen-now', 'parity': 'NOT VERIFIED'},
                    {'id': 'interaction/fullscreen-player', 'reference': 'NOT VERIFIED',
                     'flutter': 'NOT VERIFIED', 'parity': 'NOT VERIFIED'}]
            report(root, rows, clients)
            result = json.loads((root / 'coverage.json').read_text())
            self.assertEqual(result['phase6'], 'INCOMPLETE')
            self.assertEqual(len(result['pairs']), 1)
            self.assertEqual(result['pairs'][0]['metrics']['mean_absolute_rgb_error_0_255'], 0)
            self.assertEqual(result['pairs'][0]['parity'], 'NOT VERIFIED')
            self.assertEqual(result['states'][1]['reference'], 'NOT VERIFIED')

    def test_failure_preserves_honest_partial_report(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            report(root, [], [], 'launcher unavailable')
            result = json.loads((root / 'coverage.json').read_text())
            self.assertEqual(result['failure'], 'launcher unavailable')
            self.assertEqual(result['pairs'], [])
            self.assertIn('Automation failure', (root / 'summary.md').read_text())


if __name__ == '__main__':
    unittest.main()
