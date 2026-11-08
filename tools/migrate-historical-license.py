"""Applies the GPL-3.0-or-later licensing materials to every historical release tag.

Strategy (additive, no history rewrite): for each tag that lacks `LICENSE`, build a new tree from the
tag's own tree plus the licensing edits, commit it *on top of* the tagged commit, and move the tag to
that commit. Ancestry is preserved (the original release commit stays the parent), no branch history is
rewritten, and every tagged snapshot carries the licence, the notices and the SPDX headers.

    python tools/migrate-historical-license.py            # dry run: report per tag
    python tools/migrate-historical-license.py --apply    # create commits and move tags

Nothing is pushed here; `git push` is a separate, explicit step.
"""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
import tempfile
from pathlib import Path

IDENTIFIER = "SPDX-License-Identifier: GPL-3.0-or-later"
LINE_STYLES = {
    ".kt": "//", ".kts": "//", ".rs": "//", ".java": "//", ".js": "//", ".ts": "//",
    ".py": "#", ".ps1": "#", ".sh": "#", ".pro": "#", ".yaml": "#", ".yml": "#",
}
EXCLUDED_PREFIXES = (".idea/", "gradle/", "BookCoverMatcher-QA/", "app/src/test/resources/fixtures/")
EXCLUDED_FILES = {
    "gradlew", "gradlew.bat", "native/ocr/Cargo.lock", "app/release/output-metadata.json",
    "app/src/main/assets/models/openclip_image.json", "native/ocr/assets/ppocrv6_dict.txt",
    "test_excel/doujin.xlsx", "app/src/androidTest/assets/cover.jpg",
    "LICENSE", "NOTICE", "THIRD_PARTY_NOTICES.md",
}
LICENSE_FILES = ["LICENSE", "NOTICE", "THIRD_PARTY_NOTICES.md"]


def git(*args, env=None, stdin=None):
    result = subprocess.run(
        ["git", *args], capture_output=True, text=True, env=env, input=stdin, check=False
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
            input="\n".join(paths) + "\n", capture_output=True, text=True, check=True,
        ).stdout
    return out.split()


def migrate_tag(tag, apply, license_shas):
    commit = git("rev-parse", tag + "^{commit}").strip()
    tree = git("rev-parse", tag + "^{tree}").strip()
    with tempfile.TemporaryDirectory() as directory:
        env = dict(os.environ, GIT_INDEX_FILE=str(Path(directory) / "index"))
        git("read-tree", tree, env=env)
        entries, wanted = [], []
        for line in git("ls-files", "-s", env=env).splitlines():
            meta, path = line.split("\t", 1)
            mode, sha, _stage = meta.split()
            style = style_for(path)
            if style:
                wanted.append((mode, sha, path, style))
            else:
                entries.append((mode, sha, path))

        blobs = read_blobs(sorted({sha for _, sha, _, _ in wanted}))
        to_write, targets = [], []
        for mode, sha, path, style in wanted:
            body = blobs.get(sha)
            if body is None:
                continue
            try:
                text = body.decode("utf-8")
            except UnicodeDecodeError:
                continue
            updated = with_header(path, text, style)
            if updated == text:
                entries.append((mode, sha, path))
                continue
            to_write.append(updated.encode("utf-8"))
            targets.append((mode, path))

        for (mode, path), sha in zip(targets, write_blobs(to_write)):
            entries.append((mode, sha, path))
        for name in LICENSE_FILES:
            entries.append(("100644", license_shas[name], name))

        info = "".join(mode + " " + sha + " 0\t" + path + "\n" for mode, sha, path in entries)
        git("update-index", "--add", "--replace", "--index-info", env=env, stdin=info)
        new_tree = git("write-tree", env=env).strip()

    if new_tree == tree:
        return {"tag": tag, "commit": commit, "new": commit, "headers": len(targets), "action": "unchanged"}

    message = (
        "licensing: GPL-3.0-or-later for " + tag + "\n\n"
        "Adds the canonical GPLv3 LICENSE, NOTICE and THIRD_PARTY_NOTICES.md, and the\n"
        "SPDX-License-Identifier: GPL-3.0-or-later header on the project-owned sources of this\n"
        "release snapshot (" + str(len(targets)) + " file(s)). No application code or behaviour is changed.\n"
    )
    if not apply:
        return {"tag": tag, "commit": commit, "new": "(dry-run)", "headers": len(targets), "action": "would migrate"}

    original_message = ""
    if git("cat-file", "-t", "refs/tags/" + tag).strip() == "tag":
        parts = git("cat-file", "-p", "refs/tags/" + tag).split("\n\n", 1)
        original_message = parts[1].strip() if len(parts) > 1 else ""
    new_commit = git(
        "-c", "user.name=BookCoverMatcher licensing", "-c", "user.email=licensing@bookcovermatcher.invalid",
        "commit-tree", new_tree, "-p", commit, "-m", message,
    ).strip()
    tag_message = message + ("\nOriginal tag message:\n" + original_message + "\n" if original_message else "")
    git("tag", "-f", "-a", tag, new_commit, "-m", tag_message.strip())
    return {"tag": tag, "commit": commit, "new": new_commit, "headers": len(targets), "action": "migrated"}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    license_shas = {name: git("rev-parse", "HEAD:" + name).strip() for name in LICENSE_FILES}
    tags = git("tag", "--list").split()
    rows = [migrate_tag(tag, args.apply, license_shas) for tag in tags]
    print("tag              original   new        hdrs  action")
    for row in rows:
        print("%-16s %-10s %-10s %5d  %s" % (
            row["tag"], row["commit"][:9], str(row["new"])[:9], row["headers"], row["action"]))
    moved = [r for r in rows if r["action"] in ("migrated", "would migrate")]
    print("\n%d of %d tag(s) %s" % (
        len(moved), len(rows), "migrated" if args.apply else "need migration"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
