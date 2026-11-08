"""Applies the GPL-3.0-or-later licensing materials to every historical release tag.

Strategy (additive, no history rewrite): for each tag that lacks the licensing materials, build a new
tree from the tag's own tree plus the licensing edits, commit it *on top of* the tagged commit, and
move the tag to that commit. Ancestry is preserved (the original release commit stays the parent), no
branch history is rewritten, and every tagged snapshot carries the licence, the notices and the SPDX
headers.

The same commit also swaps the three flagged assets (a published cover photograph, a personal
workbook and the captured shop pages) for the synthetic fixtures in the current tree, so a migrated
snapshot distributes no asset whose redistribution right is unestablished. The paths are unchanged,
so the snapshot's own tests still resolve their fixtures.

    python tools/migrate-historical-license.py            # dry run: report per tag
    python tools/migrate-historical-license.py --apply    # create commits and move tags
    python tools/migrate-historical-license.py --tag v2.0.0 --apply   # one tag only

Nothing is pushed here; `git push` is a separate, explicit step.

Why `-z`: `git update-index --index-info` fed with newline-terminated records is silently discarded by
the Git build in use (2.56.0.windows.1) - it prints "Ignoring path …" and still exits 0, so
`git write-tree` returns the untouched tree. The NUL-terminated (`-z`) form is accepted, which is why
every write below goes through `-z` and is verified afterwards instead of being trusted.
"""

from __future__ import annotations

import argparse
import csv
import os
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REPORT = ROOT / "historical_prompt/gpl-v3-retag-report.csv"

IDENTIFIER = "SPDX-License-Identifier: GPL-3.0-or-later"

#: Blob id of the canonical GPLv3 text (sha256 3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986).
CANONICAL_LICENSE_BLOB = "f288702d2fa16d3cdf0035b15a9fcbc552cd88e7"

LINE_STYLES = {
    ".kt": "//", ".kts": "//", ".rs": "//", ".java": "//", ".js": "//", ".ts": "//",
    ".py": "#", ".ps1": "#", ".sh": "#", ".pro": "#", ".yaml": "#", ".yml": "#",
}
EXCLUDED_PREFIXES = (".idea/", "gradle/", "BookCoverMatcher-QA/", "app/src/test/resources/fixtures/")
EXCLUDED_FILES = {
    "gradlew", "gradlew.bat", "native/ocr/Cargo.lock", "app/release/output-metadata.json",
    "app/src/main/assets/models/openclip_image.json", "native/ocr/assets/ppocrv6_dict.txt",
    "LICENSE", "NOTICE", "THIRD_PARTY_NOTICES.md",
}
LICENSE_FILES = ["LICENSE", "NOTICE", "THIRD_PARTY_NOTICES.md"]

#: Synthetic replacement for each flagged asset, taken from the current tree.
SYNTHETIC_COVER = "app/src/androidTest/assets/cover.jpg"
SYNTHETIC_WORKBOOK = "test_excel/synthetic_catalog.xlsx"
SYNTHETIC_PAGES = {
    "dlsite": ("app/src/test/resources/fixtures/dlsite_books_search.html",
               "app/src/test/resources/fixtures/dlsite_books_empty.html"),
    "melonbooks": ("app/src/test/resources/fixtures/melonbooks_search.html",
                   "app/src/test/resources/fixtures/melonbooks_empty.html"),
}


def git(*args, env=None, stdin=None):
    result = subprocess.run(
        ["git", *args], capture_output=True, text=True, encoding="utf-8", errors="replace",
        env=env, input=stdin, check=False,
    )
    if result.returncode != 0:
        raise SystemExit("git " + " ".join(args) + " failed: " + result.stderr.strip())
    return result.stdout


def style_for(path):
    if path in EXCLUDED_FILES or any(path.startswith(p) for p in EXCLUDED_PREFIXES):
        return None
    suffix = Path(path).suffix.lower()
    if suffix in LINE_STYLES:
        return LINE_STYLES[suffix]
    return "xml" if suffix == ".xml" else None


def with_header(path, text, style):
    if IDENTIFIER in text:
        return text
    header = "<!-- " + IDENTIFIER + " -->" if style == "xml" else style + " " + IDENTIFIER
    lines = text.splitlines(keepends=True)
    at = 0
    while at < len(lines) and (lines[at].startswith("#!") or lines[at].lstrip().startswith("<?xml")):
        at += 1
    block = [header + "\n"]
    if at < len(lines) and lines[at].strip():
        block.append("\n")
    return "".join(lines[:at] + block + lines[at:])


def read_blobs(shas):
    if not shas:
        return {}
    out = subprocess.run(
        ["git", "cat-file", "--batch"],
        input=("\n".join(shas) + "\n").encode(),
        capture_output=True,
        check=True,
    ).stdout
    blobs, position = {}, 0
    while position < len(out):
        end = out.index(b"\n", position)
        header = out[position:end].decode().split()
        if len(header) != 3:
            break
        sha, _kind, size = header[0], header[1], int(header[2])
        blobs[sha] = out[end + 1 : end + 1 + size]
        position = end + 1 + size + 1
    return blobs


def write_blobs(contents):
    if not contents:
        return []
    with tempfile.TemporaryDirectory() as directory:
        paths = []
        for index, body in enumerate(contents):
            path = Path(directory) / ("blob" + str(index))
            path.write_bytes(body)
            paths.append(str(path))
        out = subprocess.run(
            ["git", "hash-object", "-w", "--stdin-paths"],
            input="\n".join(paths) + "\n", capture_output=True, text=True, encoding="utf-8", check=True,
        ).stdout
    return out.split()


def git_bytes(*args):
    result = subprocess.run(["git", *args], capture_output=True, check=False)
    if result.returncode != 0:
        raise SystemExit("git " + " ".join(args) + " failed: " + result.stderr.decode(errors="replace"))
    return result.stdout


def is_asset_candidate(path):
    name = Path(path).name.lower()
    return name == "cover.jpg" or name == "doujin.xlsx" or (
        name.endswith(".html") and "/fixtures/" in path.replace("\\", "/")
    )


def substitution_for(path, body, synth):
    """The synthetic bytes that replace a flagged asset, or None when this blob is not one."""
    name = Path(path).name.lower()
    if name == "cover.jpg":
        return synth["cover"]
    if name == "doujin.xlsx":
        return synth["workbook"]
    lowered = body.lower()
    for provider, pages in synth["pages"].items():
        if provider.encode() in lowered:
            return pages[1] if "empty" in name else pages[0]
    return None


def migrate_tag(tag, apply, license_shas, synth):
    commit = git("rev-parse", tag + "^{commit}").strip()
    tree = git("rev-parse", tag + "^{tree}").strip()
    entries = []
    for record in git("ls-tree", "-r", "-z", tree).split("\0"):
        if not record:
            continue
        meta, path = record.split("\t", 1)
        mode, _kind, sha = meta.split()
        entries.append((mode, sha, path))
    existing = {path: sha for _mode, sha, path in entries}
    compliant = existing.get("LICENSE") == CANONICAL_LICENSE_BLOB

    changed, substituted = [], []
    text_files = [(mode, sha, path) for mode, sha, path in entries if style_for(path)]
    blobs = read_blobs(sorted({sha for _mode, sha, _path in text_files}))
    for mode, sha, path in text_files:
        body = blobs.get(sha)
        if body is None:
            continue
        try:
            text = body.decode("utf-8")
        except UnicodeDecodeError:
            continue
        updated = with_header(path, text, style_for(path))
        if updated != text:
            changed.append((mode, path, updated.encode("utf-8")))

    assets = [(mode, sha, path) for mode, sha, path in entries if is_asset_candidate(path)]
    asset_blobs = read_blobs(sorted({sha for _mode, sha, _path in assets}))
    for mode, sha, path in assets:
        body = asset_blobs.get(sha)
        if body is None:
            continue
        replacement = substitution_for(path, body, synth)
        if replacement is None or replacement == body:
            continue
        changed.append((mode, path, replacement))
        substituted.append(path)

    headers = len(changed) - len(substituted)
    if compliant and not changed:
        return {"tag": tag, "commit": commit, "new": commit, "headers": 0,
                "substituted": "", "result": "already compliant"}

    written = write_blobs([body for _mode, _path, body in changed])
    updates = [(mode, sha, path) for (mode, path, _body), sha in zip(changed, written)]
    for name in LICENSE_FILES:
        if existing.get(name) != license_shas[name]:
            updates.append(("100644", license_shas[name], name))

    with tempfile.TemporaryDirectory() as directory:
        env = dict(os.environ, GIT_INDEX_FILE=str((Path(directory) / "index").resolve()))
        git("read-tree", tree, env=env)
        payload = "".join(mode + " " + sha + " 0\t" + path + "\0" for mode, sha, path in updates)
        git("update-index", "--add", "--replace", "-z", "--index-info", env=env, stdin=payload)
        after = {}
        for record in git("ls-files", "-s", "-z", env=env).split("\0"):
            if not record:
                continue
            meta, path = record.split("\t", 1)
            _mode, sha, _stage = meta.split()
            after[path] = sha
        refused = [(path, sha) for _mode, sha, path in updates if after.get(path) != sha]
        if refused:
            raise SystemExit("%s: the temporary index refused %d of %d update(s): %r"
                             % (tag, len(refused), len(updates), refused[:3]))
        new_tree = git("write-tree", env=env).strip()

    if new_tree == tree:
        raise SystemExit("%s: write-tree returned the original tree %s although %d path(s) changed"
                         % (tag, tree, len(updates)))
    if git("rev-parse", new_tree + ":LICENSE").strip() != CANONICAL_LICENSE_BLOB:
        raise SystemExit("%s: the new tree does not carry the canonical LICENSE blob" % tag)

    message = (
        "licensing: GPL-3.0-or-later for " + tag + "\n\n"
        "Adds the canonical GPLv3 LICENSE, NOTICE and THIRD_PARTY_NOTICES.md, and the\n"
        "SPDX-License-Identifier: GPL-3.0-or-later header on " + str(headers)
        + " project-owned file(s)\nof this release snapshot. No application code or behaviour is changed.\n"
    )
    if substituted:
        message += (
            "\nReplaces " + str(len(substituted)) + " flagged test asset(s) with synthetic fixtures at\n"
            "the same paths (" + ", ".join(substituted) + "), because no redistribution right was\n"
            "ever established for them. Generated by tools/make-synthetic-fixtures.py.\n"
        )
    if not apply:
        return {"tag": tag, "commit": commit, "new": "(dry-run)", "headers": headers,
                "substituted": " ".join(substituted), "result": "would migrate"}

    original_message = ""
    if git("cat-file", "-t", "refs/tags/" + tag).strip() == "tag":
        parts = git("cat-file", "-p", "refs/tags/" + tag).split("\n\n", 1)
        original_message = parts[1].strip() if len(parts) > 1 else ""
    new_commit = git(
        "-c", "user.name=BookCoverMatcher licensing",
        "-c", "user.email=licensing@bookcovermatcher.invalid",
        "commit-tree", new_tree, "-p", commit, "-m", message,
    ).strip()
    tag_message = message + ("\nOriginal tag message:\n" + original_message + "\n" if original_message else "")
    git("tag", "-f", "-a", tag, new_commit, "-m", tag_message.strip())
    return {"tag": tag, "commit": commit, "new": new_commit, "headers": headers,
            "substituted": " ".join(substituted), "result": "migrated"}


def load_synthetic():
    """The synthetic fixtures, read from the current tree so every snapshot gets identical blobs."""

    def read(path):
        local = ROOT / path
        if local.is_file():
            return local.read_bytes()
        return git_bytes("show", "HEAD:" + path)

    return {
        "cover": read(SYNTHETIC_COVER),
        "workbook": read(SYNTHETIC_WORKBOOK),
        "pages": {provider: (read(search), read(empty))
                  for provider, (search, empty) in SYNTHETIC_PAGES.items()},
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--tag", action="append", default=[], help="migrate only this tag (repeatable)")
    parser.add_argument("--report", default=str(REPORT))
    args = parser.parse_args()

    license_shas = {name: git("rev-parse", "HEAD:" + name).strip() for name in LICENSE_FILES}
    synth = load_synthetic()
    tags = sorted(args.tag or git("tag", "--list").split())
    rows = [migrate_tag(tag, args.apply, license_shas, synth) for tag in tags]

    print("%-18s %-10s %-10s %5s  %-16s %s" % ("tag", "original", "new", "hdrs", "result", "assets"))
    for row in rows:
        assets = len(row["substituted"].split()) if row["substituted"] else 0
        print("%-18s %-10s %-10s %5d  %-16s %d" % (
            row["tag"], row["commit"][:9], str(row["new"])[:9], row["headers"], row["result"], assets))

    if args.apply:
        report = Path(args.report)
        report.parent.mkdir(parents=True, exist_ok=True)
        with report.open("w", newline="", encoding="utf-8") as handle:
            writer = csv.writer(handle)
            writer.writerow(["tag", "original_commit", "licensing_commit", "license_blob",
                             "license_sha256", "spdx_headers", "substituted_assets", "result"])
            for row in rows:
                writer.writerow([
                    row["tag"], row["commit"], row["new"], CANONICAL_LICENSE_BLOB,
                    "3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986",
                    row["headers"], row["substituted"], row["result"],
                ])
        print("\nreport: " + str(report))

    moved = [row for row in rows if row["result"] in ("migrated", "would migrate")]
    print("\n%d of %d tag(s) %s" % (len(moved), len(rows), "migrated" if args.apply else "need migration"))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
