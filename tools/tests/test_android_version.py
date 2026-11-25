# SPDX-License-Identifier: GPL-3.0-or-later
import subprocess
import sys
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))
from android_version import resolve_version


class AndroidVersionTest(unittest.TestCase):
    def test_transitions(self):
        cases = [
            ("0.0.1-dev.1", 1),
            ("1.0.0-dev.1", 999901),
            ("2.5.0", 2050000),
            ("2.5.1-dev.1", 2050001),
            ("2.5.1-dev.99", 2050099),
            ("2.5.1", 2050100),
            ("2.6.0-dev.1", 2059901),
            ("2.6.0", 2060000),
            ("3.0.0-dev.1", 2999901),
            ("3.0.0-dev.8", 2999908),
            ("3.0.0", 3000000),
            ("2099.98.98", 2099989800),
        ]
        codes = []
        for name, code in cases:
            with self.subTest(name=name):
                self.assertEqual(resolve_version(name), (name, code))
                self.assertEqual(resolve_version("v" + name), (name, code))
                codes.append(resolve_version(name)[1])
        self.assertTrue(all(a < b for a, b in zip(codes, codes[1:])))

    def test_unsupported_new_builds(self):
        for value in (
            "v2.5.1-rc.1", "v2.5.1-dev.0", "v2.5.1-dev.100", "v2.99.0",
            "v2.5.99", "v0.0.0", "v0.0.0-dev.1", "v2100.0.0", "v3.00.0",
            "03.0.0", "3.0.00", "3.0.0-dev.01", "3.0.0-dev.1-plus",
            "３.0.0", "3.0.0\n", " 3.0.0", "9999999999999.0.0", "",
        ):
            with self.subTest(value=value), self.assertRaises(ValueError):
                resolve_version(value)

    def test_plus_source_tag_and_historical_rc(self):
        script = [sys.executable, str(TOOLS / "android_version.py"), "--source-tag"]
        for tag in ("v3.0.0-dev.8", "v3.0.0-dev.8-plus"):
            result = subprocess.run([*script, tag], capture_output=True, text=True, check=True)
            self.assertEqual(result.stdout.strip(), "3.0.0-dev.8 2999908")
        for tag, code in (("v1.1.0", 1010000), ("v2.0.0-dev.1", 1999901)):
            result = subprocess.run([*script, tag], capture_output=True, text=True, check=True)
            self.assertEqual(result.stdout.strip(), f"1.1.0-dev.1 {code}")
        for tag in ("v2.5.1-rc.1", "v2.5.1-rc.1-plus", "3.0.0-dev.8-plus"):
            result = subprocess.run([*script, tag], capture_output=True)
            self.assertNotEqual(result.returncode, 0)


if __name__ == "__main__":
    unittest.main()
