# SPDX-License-Identifier: GPL-3.0-or-later
import hashlib
import json
import re
import sys
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))
from android_version import resolve_version, source_identity


class ArchiveRebuildIndexTest(unittest.TestCase):
    def test_release_sources_and_dependency_inputs(self):
        folder = TOOLS / "archive-rebuild"
        records = json.loads((folder / "artifacts.json").read_text(encoding="utf-8"))
        self.assertTrue(records)
        for key in ("originalSha256", "rebuildSha256"):
            self.assertEqual(len({entry[key] for entry in records}), len(records))
        for entry in records:
            with self.subTest(tag=entry["releaseTag"], edition=entry["edition"]):
                tag = entry["releaseTag"]
                self.assertEqual(resolve_version(tag)[1], entry["code"])
                self.assertEqual(source_identity(entry["sourceTag"]), (entry["versionName"], entry["code"]))
                self.assertEqual(entry["sourceTag"], tag + ("-plus" if entry["edition"] == "BCM Plus" else ""))
                self.assertRegex(entry["sourceCommit"], r"^[0-9a-f]{40}$")
                self.assertIn(entry["package"], {"com.polo.bookcovermatcher", "com.bookcovermatcher"})
                self.assertEqual(entry["rebuildFile"], entry["originalFile"][:-4] + f"-code{entry['code']}.apk")
                for key in ("originalSha256", "gradleSha256", "rebuildSha256"):
                    self.assertRegex(entry[key], r"^[0-9a-f]{64}$")
                if "pythonLock" in entry:
                    lock = folder / entry["pythonLock"]
                    self.assertEqual(lock.stem, hashlib.sha256(lock.read_bytes()).hexdigest()[:16])
                    self.assertTrue(all(re.fullmatch(r"[a-z0-9-]+==[A-Za-z0-9.+-]+", line)
                                        for line in lock.read_text().splitlines()))
                for patch in entry.get("sourceCorrections", []):
                    self.assertTrue((folder / patch).is_file())


if __name__ == "__main__":
    unittest.main()
