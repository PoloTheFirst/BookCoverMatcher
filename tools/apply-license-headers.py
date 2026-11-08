"""Inserts (or corrects) the project's SPDX header in project-owned source files.
# SPDX-License-Identifier: GPL-3.0-or-later

Dev tool, never shipped. Idempotent: running it twice changes nothing, and a file that still carries the
older `GPL-3.0-only` identifier is corrected rather than duplicated.

    python tools/apply-license-headers.py            # apply
    python tools/apply-license-headers.py --check    # report only, exit 1 if anything is missing

Excluded on purpose (see `EXCLUDED_PREFIXES` / `EXCLUDED_SUFFIXES` and `tools/check-licenses.py`):
third-party files (`gradlew`, `gradlew.bat`, `gradle/wrapper/*`, `.idea/*`), generated files
(`Cargo.lock`, `app/release/output-metadata.json`, `*.onnx`), third-party content (the HTML fixtures, the
test cover photograph, the sample workbook, the PaddleOCR dictionary), and documentation (`*.md`), which
is covered by LICENSE/NOTICE rather than per-file headers.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

IDENTIFIER = "SPDX-License-Identifier: GPL-3.0-or-later"
OLDER = "SPDX-License-Identifier: GPL-3.0-only"

# extension -> (prefix, suffix) of a single-line comment carrying the identifier
LINE_STYLES = {
    ".kt": ("// ", ""),
    ".kts": ("// ", ""),
    ".rs": ("// ", ""),
    ".java": ("// ", ""),
    ".js": ("// ", ""),
    ".ts": ("// ", ""),
    ".py": ("# ", ""),
    ".ps1": ("# ", ""),
    ".sh": ("# ", ""),
    ".pro": ("# ", ""),
    ".yaml": ("# ", ""),
    ".yml": ("# ", ""),
}

XML_STYLE = ("<!-- ", " -->")

EXCLUDED_PREFIXES = (
    ".idea/",
    "gradle/",
    "BookCoverMatcher-QA/",
)
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


def is_excluded(rel: str) -> bool:
    if rel in EXCLUDED_FILES:
        return True
    if any(rel.startswith(prefix) for prefix in EXCLUDED_PREFIXES):
        return True
    # Third-party captured page markup used as parser fixtures.
    if rel.startswith("app/src/test/resources/fixtures/"):
        return True
    return rel.endswith((".md", ".txt", ".lock", ".json", ".jar", ".onnx", ".xlsx", ".jpg", ".png"))


def comment_lines(rel: str) -> list[str] | None:
    suffix = Path(rel).suffix.lower()
    if suffix in LINE_STYLES:
        prefix, tail = LINE_STYLES[suffix]
        return [f"{prefix}{IDENTIFIER}{tail}"]
    if suffix == ".xml":
        prefix, tail = XML_STYLE
        return [f"{prefix}{IDENTIFIER}{tail}"]
    return None


def apply_header(rel: str, text: str) -> tuple[str, str]:
    """Returns (new_text, action) where action is 'added', 'corrected', 'ok' or 'skipped'."""
    header = comment_lines(rel)
    if header is None:
        return text, "skipped"
    if IDENTIFIER in text:
        return text, "ok"
    if OLDER in text:
        return text.replace(OLDER, IDENTIFIER), "corrected"

    lines = text.splitlines(keepends=True)
    insert_at = 0
    # Keep a shebang first, and an XML declaration first, so neither becomes invalid.
    while insert_at < len(lines) and (
        lines[insert_at].startswith("#!")
        or lines[insert_at].lstrip().startswith("<?xml")
    ):
        insert_at += 1
    block = [line + "\n" for line in header]
    if insert_at < len(lines) and lines[insert_at].strip():
        block.append("\n")
    return "".join(lines[:insert_at] + block + lines[insert_at:]), "added"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="report only; exit 1 when work is pending")
    parser.add_argument("--root", default=".")
    args = parser.parse_args()

    root = Path(args.root).resolve()
    counts = {"added": 0, "corrected": 0, "ok": 0, "skipped": 0}
    pending: list[str] = []
    for path in sorted(root.rglob("*")):
        if not path.is_file() or ".git" in path.parts or "build" in path.parts:
            continue
        rel = path.relative_to(root).as_posix()
        if is_excluded(rel):
            counts["skipped"] += 1
            continue
        if comment_lines(rel) is None:
            continue
        original = path.read_text(encoding="utf-8")
        updated, action = apply_header(rel, original)
        if action in {"added", "corrected"}:
            pending.append(f"{action}: {rel}")
            if not args.check:
                path.write_text(updated, encoding="utf-8", newline="")
        counts[action] += 1

    for line in pending:
        print(line)
    print(
        f"\nadded {counts['added']}, corrected {counts['corrected']}, "
        f"already correct {counts['ok']}, excluded {counts['skipped']}"
    )
    if args.check and pending:
        print("license headers are incomplete")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
