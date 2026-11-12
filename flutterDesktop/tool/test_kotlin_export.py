"""Read-only fixture checks for the Linux/Windows Preferences exporter."""
import hashlib
from pathlib import Path
import tempfile
import unittest
from export_kotlin_preferences import capture, windows_value


class ExportTest(unittest.TestCase):
    def test_selected_keys_only_and_source_unchanged(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / 'prefs.xml'
            source.write_text('<map><entry key="queue.0" value="encoded-song"/>'
                              '<entry key="queue_index" value="0"/>'
                              '<entry key="source_configs_dpapi_v1" value="not-an-import-field"/></map>')
            before = hashlib.sha256(source.read_bytes()).hexdigest()
            result = capture(source)
            self.assertEqual(result['preferences'], {'queue.0': 'encoded-song', 'queue_index': '0'})
            self.assertEqual(hashlib.sha256(source.read_bytes()).hexdigest(), before)

    def test_duplicate_and_entity_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / 'prefs.xml'
            source.write_text('<map><entry key="queue" value="a"/><entry key="queue" value="b"/></map>')
            with self.assertRaises(ValueError):
                capture(source)
            source.write_text('<!DOCTYPE map [<!ENTITY fixture "unsafe">]><map/>')
            with self.assertRaises(ValueError):
                capture(source)

    def test_java_windows_value_decoding(self):
        self.assertEqual(windows_value('/A/B/C'), 'ABC')
        self.assertEqual(windows_value('/C://Music//file.wav'), 'C:\\Music\\file.wav')
        self.assertEqual(windows_value('folder\\file'), 'folder/file')
        self.assertEqual(windows_value('/uD83C/uDFB5'), '🎵')
        with self.assertRaises(ValueError):
            windows_value('/u00')


if __name__ == '__main__':
    unittest.main()
