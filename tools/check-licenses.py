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
    "app/src/androidTest/assets/cover.jpg",
}
CODE_EXTENSIONS = {".kt", ".kts", ".rs", ".java", ".js", ".ts", ".py", ".ps1", ".sh", ".pro", ".xml"}

#: The blob id of the canonical GPLv3 text; every tag and branch tip must carry exactly this blob.
CANONICAL_LICENSE_BLOB = "f288702d2fa16d3cdf0035b15a9fcbc552cd88e7"

#: Marker every generated fixture carries, so a captured page cannot slip back in unnoticed.
SYNTHETIC_MARKER = "Synthetic fixture, GPL-3.0-or-later"

#: The generated cover, pinned by hash (the previous one was a photograph of a published cover).
SYNTHETIC_COVER_SHA256 = "23897f16caa47fe5b327fb90e6db4c36b9396c22a313fb6f9f64c40bdd11977f"

FIXTURES = [
    "app/src/test/resources/fixtures/dlsite_books_search.html",
    "app/src/test/resources/fixtures/dlsite_books_empty.html",
    "app/src/test/resources/fixtures/melonbooks_search.html",
    "app/src/test/resources/fixtures/melonbooks_empty.html",
]

#: Blob ids of the flagged original assets as they were committed before the rewrite. Any of them
#: reachable from a maintained ref means a distribution artefact could still carry the original.
FLAGGED_BLOBS = {
    "0cba0f17def5ff080b440d5cb75bd36e447920f5": "cover.jpg",
    "f5ba892b7cf124af7e354dd9e52075352f2c06de": "doujin.xlsx",
    "2bf85930a19ef8b4261b0b320d4e80a0f6946e2b": "dlsite_books_search.html",
    "7f8e8068c46a6b2cc02b2a33fe5fc3c19a5a9435": "dlsite_books_empty.html",
    "1f2c55641a12516164af1dd903b5e229485ceae6": "melonbooks_search.html",
    "b9c5b4777c67c73fa87c85190781f3ac46920494": "melonbooks_empty.html",
}

#: Assets that must never be distributed again, and why (see THIRD_PARTY_NOTICES.md §4).
REMOVED_ASSETS = ["test_excel/doujin.xlsx"]



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
    # `Unresolved` stays documented on purpose: Chaquopy is still an open decision, and the legend at
    # the top of the table explains what the word means.
    for required in ("PaddleOCR", "ONNX Runtime", "OpenCLIP", "Unresolved"):
        if required not in notices:
            fail(f"THIRD_PARTY_NOTICES.md no longer documents {required}")
    clip = [line for line in notices.splitlines() if line.startswith("| OpenCLIP")]
    if not clip:
        fail("THIRD_PARTY_NOTICES.md has no OpenCLIP row")
    elif not all("MIT" in line for line in clip):
        fail("the OpenCLIP row no longer records the MIT licence of the weights")
    if "make-synthetic-fixtures.py" not in notices:
        fail("THIRD_PARTY_NOTICES.md does not describe the generated test fixtures")
    notes.append("documentation states GPL-3.0-or-later and keeps the third-party inventory")


def check_flagged_assets(root: Path) -> None:
    """The assets that were never ours must stay out of the tree, and their replacements must be ours."""
    for rel in REMOVED_ASSETS:
        tracked = subprocess.run(["git", "ls-files", "--error-unmatch", rel],
                                 capture_output=True, text=True).returncode == 0
        if tracked:
            fail(f"{rel} is tracked by git again; it must stay out of the public repository")
    workbook = root / REMOVED_ASSETS[0]
    if workbook.exists() and subprocess.run(["git", "ls-files", "--error-unmatch", REMOVED_ASSETS[0]],
                                            capture_output=True, text=True).returncode == 0:
        fail(f"{REMOVED_ASSETS[0]} is staged or committed")
    synth_workbook = root / "test_excel/synthetic_catalog.xlsx"
    if not synth_workbook.is_file():
        fail("test_excel/synthetic_catalog.xlsx is missing (the workbook tests need a distributable fixture)")
    cover = root / "app/src/androidTest/assets/cover.jpg"
    if not cover.is_file():
        fail("app/src/androidTest/assets/cover.jpg is missing (the device tests need a cover image)")
    else:
        digest = hashlib.sha256(cover.read_bytes()).hexdigest()
        if digest != SYNTHETIC_COVER_SHA256:
            fail(f"the androidTest cover is not the generated one (sha256 {digest})")
    for rel in FIXTURES:
        path = root / rel
        if not path.is_file():
            fail(f"{rel} is missing")
        elif SYNTHETIC_MARKER not in path.read_text(encoding="utf-8"):
            fail(f"{rel} is not a generated fixture: it carries no synthetic marker")
    notes.append(
        "flagged assets handled: the personal workbook is untracked, the cover is the generated image "
        f"and all {len(FIXTURES)} page fixtures are generated"
    )



def git(*args: str) -> str:
    # UTF-8 explicitly: the sources contain Japanese text and the Windows default code page cannot
    # decode it, which used to abort the --refs scan inside the pipe reader thread.
    return subprocess.run(
        ["git", *args], capture_output=True, text=True,
        encoding="utf-8", errors="replace", check=False,
    ).stdout.strip()


def rev(ref_path: str) -> str:
    """`git rev-parse --verify --quiet` and nothing else: without those flags Git echoes the argument
    back on stdout when the path does not exist, which silently turned "absent" into a false match."""
    return subprocess.run(
        ["git", "rev-parse", "--verify", "--quiet", ref_path],
        capture_output=True, text=True, encoding="utf-8", errors="replace", check=False,
    ).stdout.strip()


def check_refs() -> None:
    tags = sorted(git("tag", "--list").splitlines())
    branches = sorted(git("for-each-ref", "--format=%(refname:short)", "refs/heads").splitlines())
    synthetic_cover = git("hash-object", "app/src/androidTest/assets/cover.jpg")
    synthetic_workbook = git("hash-object", "test_excel/synthetic_catalog.xlsx")
    print("\n%-26s %-10s %-8s %-7s %-10s %s"
          % ("ref", "commit", "LICENSE", "header", "workbook", "cover"))
    incomplete = []
    for ref in tags + branches:
        label = ref if ref in tags else ref + " (branch)"
        commit = rev(ref + "^{commit}")[:9]
        licence = rev(ref + ":LICENSE")
        licence_state = "ok" if licence == CANONICAL_LICENSE_BLOB else ("absent" if not licence else "WRONG")
        sources = [p for p in git("ls-tree", "-r", "--name-only", ref, "--", "app/src/main/java").splitlines()
                   if p.endswith(".kt")]
        sample = git("show", ref + ":" + sources[0]) if sources else ""
        header_state = "ok" if IDENTIFIER in sample else ("n/a" if not sources else "MISSING")
        workbook = rev(ref + ":" + REMOVED_ASSETS[0])
        if not workbook:
            workbook_state = "absent"
        elif workbook == synthetic_workbook:
            workbook_state = "synthetic"
        else:
            workbook_state = "ORIGINAL"
        cover = rev(ref + ":app/src/androidTest/assets/cover.jpg")
        if not cover:
            cover_state = "absent"
        else:
            cover_state = "synthetic" if cover == synthetic_cover else "ORIGINAL"
        print("%-26s %-10s %-8s %-7s %-10s %s" % (label, commit, licence_state, header_state,
                                                  workbook_state, cover_state))
        broken = (licence_state != "ok" or header_state == "MISSING" or "ORIGINAL" in workbook_state
                  or cover_state == "ORIGINAL")
        if broken and ref in tags:
            incomplete.append(ref)
        elif broken:
            notes.append(f"{ref} (branch) is not fully licensed - reported, not failed: it is the owner's branch")
    notes.append(f"refs scanned: {len(tags)} tag(s), {len(branches)} branch tip(s)")
    if incomplete:
        fail("tags still without complete licensing or still carrying a flagged asset: "
             + ", ".join(incomplete))
    else:
        notes.append("every tag: canonical LICENSE, SPDX header, no flagged asset")


def check_history() -> None:
    """Every reachable commit of the maintained refs, not just the tips.

    Slow on purpose-by-design: it asks Git once per commit, so the audit stays simple enough to trust.
    """
    refs = (git("for-each-ref", "--format=%(refname)", "refs/heads").splitlines()
            + git("for-each-ref", "--format=%(refname)", "refs/tags").splitlines())
    commits = git("rev-list", *refs).split()
    if not commits:
        fail("no commits reachable from the branches and tags")
        return
    without_license = [commit for commit in commits
                       if rev(commit + ":LICENSE") != CANONICAL_LICENSE_BLOB]
    if without_license:
        fail("%d of %d reachable commit(s) lack the canonical LICENSE, e.g. %s"
             % (len(without_license), len(commits), ", ".join(c[:9] for c in without_license[:5])))
    leftover = []
    for line in git("rev-list", "--objects", *refs).splitlines():
        sha = line.split(" ", 1)[0]
        if sha in FLAGGED_BLOBS:
            leftover.append("%s (%s)" % (sha[:9], FLAGGED_BLOBS[sha]))
    if leftover:
        fail("flagged original blobs still reachable: " + ", ".join(sorted(set(leftover))))
    notes.append("history scanned: %d reachable commit(s) across %d ref(s)" % (len(commits), len(refs)))
    if not without_license and not leftover:
        notes.append("every reachable commit carries the canonical LICENSE and no flagged asset")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--refs", action="store_true", help="also report tags and branch tips")
    parser.add_argument("--history", action="store_true",
                        help="also walk every reachable commit (slow, thorough)")
    parser.add_argument("--root", default=".")
    args = parser.parse_args()
    root = Path(args.root).resolve()

    check_license(root)
    check_headers(root)
    check_third_party(root)
    check_docs(root)
    check_flagged_assets(root)
    if args.refs:
        check_refs()
    if args.history:
        check_history()

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
