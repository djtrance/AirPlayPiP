import importlib.util
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("manifest_validator", ROOT / "scripts/validate_manifest.py")
validator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(validator)


class AndroidManifestTests(unittest.TestCase):
    def test_source_manifest_has_package_parser_compatible_affinities(self):
        self.assertEqual([], validator.validate_manifest(ROOT / "app/src/main/AndroidManifest.xml"))

    def test_regression_rejects_the_011_hyphenated_affinity(self):
        self.assertFalse(validator.valid_task_affinity("io.github.airplaypip.floating-controls"))
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "AndroidManifest.xml"
            path.write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application><activity android:name=".FloatingControlsActivity"
                    android:taskAffinity="${applicationId}.floating-controls" /></application>
                </manifest>''')
            errors = validator.validate_manifest(path)
            self.assertEqual(1, len(errors))
            self.assertIn("FloatingControlsActivity", errors[0])

    def test_valid_global_private_and_empty_affinities(self):
        for value in ("", ":floatingcontrols", "io.github.airplaypip.floatingcontrols",
                      "io.github.airplaypip.floating_controls", "system"):
            with self.subTest(value=value):
                self.assertTrue(validator.valid_task_affinity(value))
        for value in (":", "floatingcontrols", "io.github.9invalid", "io.github.bad name"):
            with self.subTest(value=value):
                self.assertFalse(validator.valid_task_affinity(value))

    def test_controls_keep_a_separate_task_and_no_display_theme(self):
        android = validator.ANDROID
        root = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot()
        controls = next(a for a in root.findall("./application/activity")
                        if a.get(android + "name") == ".FloatingControlsActivity")
        self.assertEqual("@android:style/Theme.NoDisplay", controls.get(android + "theme"))
        self.assertEqual("${applicationId}.floatingcontrols", controls.get(android + "taskAffinity"))


if __name__ == "__main__":
    unittest.main()
