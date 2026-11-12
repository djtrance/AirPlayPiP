import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("patches", Path(__file__).resolve().parents[1] / "apply_native_patches.py")
patcher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(patcher)


class PatchTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / "source"
        self.source.mkdir()
        patcher.run(self.source, "init", "--quiet")
        (self.source / "video.c").write_text("original\n")
        (self.source / "other.c").write_text("unrelated\n")
        patcher.run(self.source, "add", ".")
        patcher.run(self.source, "-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-qm", "Initial")
        self.patches = []
        for index, (before, after) in enumerate((("original", "first patch"), ("first patch", "second patch"))):
            path = self.root / f"{index}.patch"
            path.write_text(f"diff --git a/video.c b/video.c\n--- a/video.c\n+++ b/video.c\n@@ -1 +1 @@\n-{before}\n+{after}\n")
            self.patches.append(path)

    def test_stack_is_idempotent_when_patches_edit_same_line(self):
        patcher.apply_stack(self.source, self.patches)
        patcher.apply_stack(self.source, self.patches)
        self.assertEqual((self.source / "video.c").read_text(), "second patch\n")

    def test_preserves_unrelated_changes(self):
        (self.source / "other.c").write_text("developer edit\n")
        patcher.apply_stack(self.source, self.patches)
        self.assertEqual((self.source / "other.c").read_text(), "developer edit\n")

    def test_conflict_does_not_overwrite_user_work(self):
        (self.source / "video.c").write_text("developer edit\n")
        with self.assertRaises(subprocess.CalledProcessError):
            patcher.apply_stack(self.source, self.patches)
        self.assertEqual((self.source / "video.c").read_text(), "developer edit\n")


if __name__ == "__main__":
    unittest.main()
