#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Repeatable licensing audit for BookCoverMatcher.

    python tools/check-licenses.py           # audit the working tree (exit 1 on any failure)
    python tools/check-licenses.py --refs    # also report every local tag and branch tip

Checks:

1. `LICENSE` is the complete, unmodified GPLv3 text (byte hash + structural markers).
2. Every eligible project-owned source file carries `SPDX-License-Identifier: GPL-3.0-or-later`.
3. No project-owned file still says `GPL-3.0-only` (the previous migration's identifier).
4. Third-party files keep their own terms: the Gradle wrapper stays Apache-2.0, and the PaddleOCR
   dictionary / OpenCLIP metadata carry no project header at all.
5. `README.md`, `NOTICE` and `THIRD_PARTY_NOTICES.md` describe `GPL-3.0-or-later`, and the third-party
   inventory is intact.
6. With `--refs`, report which tags and branch tips carry `LICENSE` and the SPDX header.

Deliberately *not* automated: whether a third-party licence is compatible. `THIRD_PARTY_NOTICES.md`
records that verdict per component; the audit only checks that the document exists and is coherent.
"""

from __future__ import annotations

import argparse
import hashlib
import subprocess
import sys
from pathlib import Path

IDENTIFIER = "SPDX-License-Identifier: GPL-3.0-or-later"
OLDER = "SPDX-License-Identifier: GPL-3.0-only"

# sha256 of https://www.gnu.org/licenses/gpl-3.0.txt (35,149 bytes, 674 lines). Changing LICENSE must
# mean changing this constant on purpose, which is the point of pinning it.
LICENSE_SHA256 = "3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986"
LICENSE_MARKERS = [
    "GNU GENERAL PUBLIC LICENSE",
    "Version 3, 29 June 2007",
    "TERMS AND CONDITIONS",
    "END OF TERMS AND CONDITIONS",
    "How to Apply These Terms to Your New Programs",
]

EXCLUDED_PREFIXES = (".idea/", "gradle/", "BookCoverMatcher-QA/", "app/src/test/resources/fixtures/")
EXCLUDED_FILES = {
    "gradlew",
    "gradlew.bat",
    "native/ocr/Cargo.lock",
    "app/release/output-metadata.json",
    "app/src/main/assets/models/openclip_image.json",
    "native/ocr/assets/ppocrv6_dict.txt",
    "test_excel/doujin.xlsx",
    "app/src/androidTest/assets/cover.jpg",
}
CODE_EXTENSIONS = {".kt", ".kts", ".rs", ".java", ".js", ".ts", ".py", ".ps1", ".sh", ".pro", ".xml"}

# Files that legitimately mention the old identifier: the migrator names it, the audit names it, and the
# policy test searches for it.
OLDER_REFERENCES = {
    "tools/apply-license-headers.py",
    "tools/check-licenses.py",
    "app/src/test/java/com/bookcovermatcher/LicensingPolicyTest.kt",
}

DOCS = ["README.md", "NOTICE", "THIRD_PARTY_NOTICES.md"]

failures: list[str] = []
notes: list[str] = []


def fail(message: str) -> None:
    failures.append(message)



def check_license(root: Path) -> None:
    path = root / "LICENSE"
    if not path.is_file():
        fail("LICENSE is missing")
        return
    data = path.read_bytes()
    digest = hashlib.sha256(data).hexdigest()
    if digest != LICENSE_SHA256:
        fail(f"LICENSE sha256 is {digest}, expected the canonical GPLv3 text {LICENSE_SHA256}")
    text = data.decode("utf-8")
    for marker in LICENSE_MARKERS:
        if marker not in text:
            fail(f"LICENSE is missing the marker {marker!r}")
    notes.append(f"LICENSE: {len(data)} bytes, {text.count(chr(10)) + 1} lines, sha256 {digest[:12]}...")


def eligible(rel: str) -> bool:
    if rel in EXCLUDED_FILES or any(rel.startswith(p) for p in EXCLUDED_PREFIXES):
        return False
    return Path(rel).suffix.lower() in CODE_EXTENSIONS


def check_headers(root: Path) -> None:
    covered = 0
    for path in sorted(root.rglob("*")):
        if not path.is_file() or ".git" in path.parts or "build" in path.parts:
            continue
        rel = path.relative_to(root).as_posix()
        if not eligible(rel):
            continue
        text = path.read_text(encoding="utf-8")
        covered += 1
        if IDENTIFIER not in text:
            fail(f"{rel}: missing {IDENTIFIER}")
        if OLDER in text and rel not in OLDER_REFERENCES:
            fail(f"{rel}: still carries the previous identifier '{OLDER}'")
        if "\ufeff" in text:
            # A stray byte-order mark after the header line is a syntax error in Kotlin; keep every
            # source BOM-free so the header pass can never split one.
            fail(f"{rel}: contains a byte-order mark")
    notes.append(f"project-owned source files checked: {covered}")


def check_third_party(root: Path) -> None:
    for name in ("gradlew", "gradlew.bat"):
        text = (root / name).read_text(encoding="utf-8")
        if "SPDX-License-Identifier: Apache-2.0" not in text:
            fail(f"{name}: the Gradle wrapper's Apache-2.0 identifier was removed or altered")
        if IDENTIFIER in text:
            fail(f"{name}: the Gradle wrapper must not carry the project's identifier")
    for rel in ("native/ocr/assets/ppocrv6_dict.txt", "app/src/main/assets/models/openclip_image.json"):
        if "SPDX" in (root / rel).read_text(encoding="utf-8"):
            fail(f"{rel}: third-party data must not carry an SPDX header")
    cargo = (root / "native/ocr/Cargo.toml").read_text(encoding="utf-8")
    if 'license = "GPL-3.0-or-later"' not in cargo:
        fail('native/ocr/Cargo.toml does not declare license = "GPL-3.0-or-later"')
    notes.append(
        "third-party identifiers intact (Gradle wrapper Apache-2.0; PaddleOCR dictionary and OpenCLIP "
        "metadata unlabelled)"
    )


def check_docs(root: Path) -> None:
    for name in DOCS:
        path = root / name
        if not path.is_file():
            fail(f"{name} is missing")
            continue
        text = path.read_text(encoding="utf-8")
        if "GPL-3.0-or-later" not in text:
            fail(f"{name} never states GPL-3.0-or-later")
        if "GPL-3.0-only" in text:
            fail(f"{name} still states GPL-3.0-only")
    notices = (root / "THIRD_PARTY_NOTICES.md").read_text(encoding="utf-8")
    for required in ("PaddleOCR", "ONNX Runtime", "OpenCLIP", "Unresolved"):
        if required not in notices:
            fail(f"THIRD_PARTY_NOTICES.md no longer documents {required}")
    notes.append("documentation states GPL-3.0-or-later and keeps the third-party inventory")


def git(*args: str) -> str:
    return subprocess.run(["git", *args], capture_output=True, text=True, check=False).stdout.strip()


def check_refs() -> None:
    tags = git("tag", "--list").splitlines()
    branches = git("for-each-ref", "--format=%(refname:short)", "refs/heads").splitlines()
    missing_license = []
    missing_header = []
    for ref in tags + branches:
        if "LICENSE" not in git("ls-tree", "--name-only", ref).splitlines():
            missing_license.append(ref)
            continue
        sample = git("show", f"{ref}:app/src/main/java/com/bookcovermatcher/MainViewModel.kt")
        if not sample or IDENTIFIER not in sample:
            missing_header.append(ref)
    notes.append(f"refs scanned: {len(tags)} tag(s), {len(branches)} branch(es)")
    notes.append("without LICENSE: " + (", ".join(sorted(missing_license)) or "none"))
    notes.append("without the SPDX header: " + (", ".join(sorted(missing_header)) or "none"))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--refs", action="store_true", help="also report tags and branch tips")
    parser.add_argument("--root", default=".")
    args = parser.parse_args()
    root = Path(args.root).resolve()

    check_license(root)
    check_headers(root)
    check_third_party(root)
    check_docs(root)
    if args.refs:
        check_refs()

    for note in notes:
        print("ok   " + note)
    for failure in failures:
        print("FAIL " + failure)
    if failures:
        print(f"\n{len(failures)} licensing problem(s)")
        return 1
    print("\nlicensing audit passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
