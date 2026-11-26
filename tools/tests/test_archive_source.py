# SPDX-License-Identifier: GPL-3.0-or-later
import importlib.util
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

HELPER = Path(__file__).resolve().parents[1] / "archive-rebuild" / "prepare.py"
spec = importlib.util.spec_from_file_location("archive_prepare", HELPER)
prepare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare)


class ArchiveSourceTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.repo = self.root / "source"
        self.repo.mkdir()
        self.git("init", "--quiet")
        self.git("config", "user.name", "Test")
        self.git("config", "user.email", "test@example.invalid")
        self.git("config", "commit.gpgSign", "false")
        (self.repo / "CHANGELOG.md").write_text("Original release\n")
        self.pin = self.commit()
        self.tag()
        self.enterContext(patch.object(prepare, "ROOT", self.repo))

    def git(self, *args):
        return subprocess.check_output(["git", "-C", self.repo, *args], text=True).strip()

    def commit(self):
        self.git("add", "-A")
        self.git("commit", "--quiet", "-m", "Fixture")
        return self.git("rev-parse", "HEAD")

    def tag(self):
        self.git("update-ref", "refs/tags/v1.0.0", "HEAD")

    def export(self, directory, keep_commit=False):
        target = self.root / directory
        target.mkdir()
        prepare.export_source({"sourceTag": "v1.0.0", "sourceCommit": self.pin, "keepTaggedCommit": keep_commit}, target)
        return target

    def test_both_export_paths_use_build_source(self):
        for corrected in (False, True):
            if corrected:
                (self.repo / "CHANGELOG.md").write_text("Original and rebuilt version codes\n")
                self.commit()
                self.tag()
            for keep_commit in (False, True):
                with self.subTest(corrected=corrected, keep_commit=keep_commit):
                    target = self.export(f"export-{corrected}-{keep_commit}", keep_commit)
                    self.assertEqual((target / "CHANGELOG.md").read_text(), "Original release\n")
                    if keep_commit:
                        head = subprocess.check_output(["git", "-C", target, "rev-parse", "HEAD"], text=True)
                        self.assertEqual(head.strip(), self.pin)

    def test_code_change_and_indirect_changelog_correction_are_rejected(self):
        (self.repo / "CHANGELOG.md").write_text("Original and rebuilt version codes\n")
        code = self.repo / "app.kt"
        code.write_text("fun main() = Unit\n")
        self.commit()
        self.tag()
        with self.assertRaises(ValueError):
            self.export("code-changed")

        code.unlink()
        self.commit()
        self.tag()
        with self.assertRaises(ValueError):
            self.export("indirect-correction")


if __name__ == "__main__":
    unittest.main()
