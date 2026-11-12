import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("bootstrap", Path(__file__).resolve().parents[1] / "bootstrap_native.py")
bootstrap = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bootstrap)


class BootstrapTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.origin = self.root / "origin"
        self.origin.mkdir()
        bootstrap.git(self.origin, "init", "--quiet")
        (self.origin / "source.c").write_text("original\n")
        bootstrap.git(self.origin, "add", "source.c")
        bootstrap.git(self.origin, "-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-qm", "Initial")
        self.dependency = {"name": "Example", "url": str(self.origin), "commit": bootstrap.git(self.origin, "rev-parse", "HEAD")}
        self.base = self.root / "vendor"

    def test_pins_commit_and_preserves_local_edits(self):
        bootstrap.ensure_dependency(self.base, self.dependency)
        target = self.base / "Example"
        self.assertEqual(bootstrap.git(target, "rev-parse", "HEAD"), self.dependency["commit"])
        (target / "source.c").write_text("developer edit\n")
        bootstrap.ensure_dependency(self.base, self.dependency)
        self.assertEqual((target / "source.c").read_text(), "developer edit\n")

    def test_refuses_unmanaged_files(self):
        target = self.base / "Example"
        target.mkdir(parents=True)
        (target / "important.txt").write_text("do not delete")
        with self.assertRaises(RuntimeError):
            bootstrap.ensure_dependency(self.base, self.dependency)
        self.assertTrue((target / "important.txt").exists())

    def test_refuses_different_commit(self):
        bootstrap.ensure_dependency(self.base, self.dependency)
        dependency = dict(self.dependency, commit="a" * 40)
        with self.assertRaises(RuntimeError):
            bootstrap.ensure_dependency(self.base, dependency)

    def test_rejects_path_traversal_and_unpinned_versions(self):
        for change in ({"name": "../outside"}, {"commit": "main"}):
            with self.assertRaises(ValueError):
                bootstrap.ensure_dependency(self.base, dict(self.dependency, **change))


if __name__ == "__main__":
    unittest.main()
