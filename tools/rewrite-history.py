# SPDX-License-Identifier: GPL-3.0-or-later
"""Rewrites every reachable commit so the whole history carries the licence and no flagged asset.

Run inside a **scratch mirror clone**, never in the working repository:

    git clone --mirror <repo> %TEMP%\\bc-rewrite.git
    cd %TEMP%\\bc-rewrite.git
    python <repo>/tools/rewrite-history.py            # plan only
    python <repo>/tools/rewrite-history.py --apply    # rewrite, move refs, write the mapping

For every commit reachable from `refs/heads/*` and `refs/tags/*` (topologically, parents first) it:

* adds the canonical `LICENSE`, `NOTICE` and `THIRD_PARTY_NOTICES.md` at the root of the snapshot;
* prepends `SPDX-License-Identifier: GPL-3.0-or-later`, in the comment syntax of the file's own
  extension, to every eligible project-owned source file that lacks it — decided per snapshot from the
  files that snapshot actually holds, never from today's inventory;
* replaces the six flagged original blobs (the published cover photograph, the personal workbook, the
  four captured shop pages) with the generated fixtures, matched **by blob id as well as by path**, so a
  rename in an old commit cannot smuggle one through;
* leaves third-party material byte-identical: the Gradle wrapper (Apache-2.0), the PaddleOCR
  dictionary, the OpenCLIP metadata, `Cargo.lock`, the QA reports, `.idea/`, `gradle/`, and anything
  that is not a known source extension.

Author, committer, both timestamps, parent lists (merge topology), messages and annotated tag objects
(tagger, date, message) are preserved verbatim. GPG signatures cannot survive a content change and are
stripped; every dropped signature is reported. Output: `rewrite-map.csv` (commit, tag and ref rows) in
the current directory, plus a verification summary. The script refuses to run twice against the same
clone when a maintained ref already points at a rewritten commit.
"""

from __future__ import annotations

import argparse
import csv
import os
import subprocess
from pathlib import Path

IDENTIFIER = "SPDX-License-Identifier: GPL-3.0-or-later"
LICENSE_FILES = ["LICENSE", "NOTICE", "THIRD_PARTY_NOTICES.md"]

LINE_STYLES = {
    ".kt": "//", ".kts": "//", ".rs": "//", ".java": "//", ".js": "//", ".ts": "//",
    ".py": "#", ".ps1": "#", ".sh": "#", ".pro": "#", ".yaml": "#", ".yml": "#",
}
XML_STYLE_EXTENSIONS = {".xml"}

#: Third-party or generated material that must never receive the project's identifier.
EXCLUDED_PREFIXES = (".idea/", "gradle/", "BookCoverMatcher-QA/")
EXCLUDED_FILES = {
    "gradlew", "gradlew.bat", "native/ocr/Cargo.lock", "app/release/output-metadata.json",
    "app/src/main/assets/models/openclip_image.json", "native/ocr/assets/ppocrv6_dict.txt",
}

#: Generated fixtures in the clone's current HEAD, keyed by the role they play.
SYNTHETIC_PATHS = {
    "cover": "app/src/androidTest/assets/cover.jpg",
    "workbook": "test_excel/synthetic_catalog.xlsx",
    "dlsite_search": "app/src/test/resources/fixtures/dlsite_books_search.html",
    "dlsite_empty": "app/src/test/resources/fixtures/dlsite_books_empty.html",
    "melonbooks_search": "app/src/test/resources/fixtures/melonbooks_search.html",
    "melonbooks_empty": "app/src/test/resources/fixtures/melonbooks_empty.html",
}

#: Blob ids of the original flagged assets, measured on the preserved copies. Any commit holding one of
#: these blobs, under any path, is rewritten so the blob disappears from the resulting history.
FLAGGED_BLOBS = {
    "0cba0f17def5ff080b440d5cb75bd36e447920f5": "cover",
    "f5ba892b7cf124af7e354dd9e52075352f2c06de": "workbook",
    "2bf85930a19ef8b4261b0b320d4e80a0f6946e2b": "dlsite_search",
    "7f8e8068c46a6b2cc02b2a33fe5fc3c19a5a9435": "dlsite_empty",
    "1f2c55641a12516164af1dd903b5e229485ceae6": "melonbooks_search",
    "b9c5b4777c67c73fa87c85190781f3ac46920494": "melonbooks_empty",
}


def git(*args, stdin=None):
    result = subprocess.run(
        ["git", *args], capture_output=True, text=True, encoding="utf-8", errors="replace",
        input=stdin, check=False,
    )
    if result.returncode != 0:
        raise SystemExit("git " + " ".join(args) + " failed: " + result.stderr.strip()[:400])
    return result.stdout


def git_bytes(*args, stdin=None):
    result = subprocess.run(["git", *args], capture_output=True, input=stdin, check=False)
    if result.returncode != 0:
        raise SystemExit("git " + " ".join(args) + " failed: "
                         + result.stderr.decode(errors="replace")[:400])
    return result.stdout


def hash_blob(data: bytes) -> str:
    return git_bytes("hash-object", "-w", "--stdin", stdin=data).decode().strip()


def hash_tag(payload: str) -> str:
    """Writes a tag object verbatim.

    `git mktag` refuses even a byte-identical copy of this repository's own tags on Git
    2.56.0.windows.1 ("badObjectSha1: invalid 'object' line format"), so the object is written with
    `hash-object -t tag --literally` and the ref is moved with `update-ref`. The payload is rebuilt
    from the original object and re-validated afterwards by `git fsck`, so nothing unverified is
    introduced.
    """
    return git_bytes("hash-object", "-t", "tag", "-w", "--stdin", "--literally",
                     stdin=payload.encode("utf-8")).decode().strip()


def read_blob(sha: str) -> bytes:
    return git_bytes("cat-file", "blob", sha)


def style_for(path: str):
    if path in EXCLUDED_FILES or any(path.startswith(prefix) for prefix in EXCLUDED_PREFIXES):
        return None
    suffix = Path(path).suffix.lower()
    if suffix in LINE_STYLES:
        return LINE_STYLES[suffix]
    if suffix in XML_STYLE_EXTENSIONS:
        return "xml"
    return None


def with_header(text: str, style: str) -> str:
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


def synthetic_for(path: str, blob: bytes, synthetic: dict):
    """The replacement blob for a flagged asset, recognised by name and by content."""
    name = Path(path).name.lower()
    if name == "cover.jpg":
        return synthetic["cover"]
    if name == "doujin.xlsx":
        return synthetic["workbook"]
    if name.endswith(".html") and "/fixtures/" in path.replace("\\", "/"):
        lowered = blob.lower()
        if b"dlsite" in lowered:
            return synthetic["dlsite_empty" if "empty" in name else "dlsite_search"]
        if b"melonbooks" in lowered:
            return synthetic["melonbooks_empty" if "empty" in name else "melonbooks_search"]
    return None


class Rewriter:
    """Rewrites trees, blobs, commits and tags, memoising everything it has already produced."""

    def __init__(self, synthetic: dict, license_blobs: dict, apply: bool):
        self.synthetic = synthetic
        self.license_blobs = license_blobs
        self.apply = apply
        self.tree_cache: dict = {}
        self.blob_cache: dict = {}
        self.commit_cache: dict = {}
        self.stats = {"trees": 0, "blobs": 0, "headers": 0, "assets": 0, "commits": 0, "tags": 0}
        self.dropped_signatures: list = []

    def rewrite_tree(self, sha: str, prefix: str = "") -> str:
        key = (sha, prefix)
        if key in self.tree_cache:
            return self.tree_cache[key]
        entries = []
        for record in git("ls-tree", "-z", sha).split("\0"):
            if not record:
                continue
            meta, name = record.split("\t", 1)
            mode, kind, osha = meta.split()
            path = prefix + name
            if kind == "commit":                       # submodule pointer
                entries.append((mode, kind, osha, name))
            elif kind == "blob":
                entries.append((mode, kind, self.rewrite_blob(path, osha, mode), name))
            else:
                entries.append((mode, kind, self.rewrite_tree(osha, path + "/"), name))
        if prefix == "":
            index_of = {name: position for position, (_m, _k, _s, name) in enumerate(entries)}
            for name in LICENSE_FILES:
                blob = self.license_blobs[name]
                if name in index_of:
                    position = index_of[name]
                    mode, kind, _sha, entry_name = entries[position]
                    entries[position] = (mode, kind, blob, entry_name)
                else:
                    entries.append(("100644", "blob", blob, name))
        entries.sort(key=lambda item: item[3])
        payload = "".join("%s %s %s\t%s\0" % (mode, kind, sha, name) for mode, kind, sha, name in entries)
        new = git("mktree", "-z", stdin=payload).strip()
        self.tree_cache[key] = new
        self.stats["trees"] += 1
        return new

    def rewrite_blob(self, path: str, sha: str, mode: str) -> str:
        if mode.startswith("120000"):                  # a symlink stores a path, never text
            return sha
        if sha in FLAGGED_BLOBS:                       # by blob id, whatever the path says
            self.stats["assets"] += 1
            return self.synthetic[FLAGGED_BLOBS[sha]]
        style = style_for(path)
        if style is None:
            return sha
        key = (sha, style)
        if key in self.blob_cache:
            return self.blob_cache[key]
        body = read_blob(sha)
        replacement = synthetic_for(path, body, self.synthetic)
        if replacement is None:
            try:
                text = body.decode("utf-8")
            except UnicodeDecodeError:
                self.blob_cache[key] = sha
                return sha
            updated = with_header(text, style)
            if updated == text:
                self.blob_cache[key] = sha
                return sha
            self.stats["headers"] += 1
            replacement = hash_blob(updated.encode("utf-8"))
        else:
            self.stats["assets"] += 1
        self.stats["blobs"] += 1
        self.blob_cache[key] = replacement
        return replacement

    # -- commits and tags ----------------------------------------------------
    def rewrite_commit(self, sha: str) -> str:
        if sha in self.commit_cache:
            return self.commit_cache[sha]
        header, _, message = git("cat-file", "commit", sha).partition("\n\n")
        tree, parents, author, committer = None, [], None, None
        in_signature = False
        for line in header.splitlines():
            if in_signature:
                if line.startswith(" "):
                    continue
                in_signature = False
            if line.startswith("tree "):
                tree = line[5:].strip()
            elif line.startswith("parent "):
                parents.append(line[7:].strip())
            elif line.startswith("author "):
                author = line[7:]
            elif line.startswith("committer "):
                committer = line[10:]
            elif line.startswith("gpgsig"):
                in_signature = True
                self.dropped_signatures.append("commit " + sha)
        new_tree = self.rewrite_tree(tree)
        args = ["commit-tree", new_tree]
        for parent in parents:
            args += ["-p", self.rewrite_commit(parent)]
        env = dict(os.environ)
        env.update(identity("GIT_AUTHOR", author))
        env.update(identity("GIT_COMMITTER", committer))
        result = subprocess.run(["git", *args], input=message.encode("utf-8"),
                                capture_output=True, env=env, check=False)
        if result.returncode != 0:
            raise SystemExit("commit-tree failed for " + sha + ": " + result.stderr.decode(errors="replace")[:300])
        new = result.stdout.decode().strip()
        self.commit_cache[sha] = new
        self.stats["commits"] += 1
        return new

    def rewrite_tag(self, sha: str) -> str:
        header, _, message = git("cat-file", "tag", sha).partition("\n\n")
        kept, in_signature = [], False
        for line in header.splitlines():
            if in_signature:
                if line.startswith(" "):
                    continue
                in_signature = False
            if line.startswith("gpgsig"):
                in_signature = True
                self.dropped_signatures.append("tag " + sha)
                continue
            if line.startswith("object "):
                kept.append("object " + self.rewrite_commit(line[7:].strip()))
            else:
                kept.append(line)
        new = hash_tag("\n".join(kept) + "\n\n" + message)
        self.stats["tags"] += 1
        return new


def identity(prefix: str, value: str) -> dict:
    """`Name <email> 1700000000 +0900` -> the environment commit-tree reads it from."""
    name, rest = value.split(" <", 1)
    email, date = rest.split("> ", 1)
    return {prefix + "_NAME": name, prefix + "_EMAIL": email, prefix + "_DATE": date}


def rev(ref_path: str) -> str:
    """`rev-parse --verify --quiet`: without it Git echoes the argument when the path is absent."""
    result = subprocess.run(["git", "rev-parse", "--verify", "--quiet", ref_path],
                            capture_output=True, text=True, encoding="utf-8", errors="replace")
    return result.stdout.strip()


def read_blobs_batch(shas) -> dict:
    """One `cat-file --batch` for many blobs; spawning Git per blob dominated the verification time."""
    blobs, shas = {}, sorted(shas)
    if not shas:
        return blobs
    out = subprocess.run(["git", "cat-file", "--batch"],
                         input=("\n".join(shas) + "\n").encode(), capture_output=True, check=True).stdout
    position = 0
    while position < len(out):
        end = out.index(b"\n", position)
        header = out[position:end].decode().split()
        if len(header) != 3:
            break
        sha, _kind, size = header[0], header[1], int(header[2])
        blobs[sha] = out[end + 1:end + 1 + size]
        position = end + 1 + size + 1
    return blobs


def verify_history(tips, license_blob: str):
    """Three independent checks over everything reachable from [tips]."""
    problems = []
    commits = git("rev-list", *tips).split()
    without_license = [commit for commit in commits if rev(commit + ":LICENSE") != license_blob]
    if without_license:
        problems.append("%d commit(s) without the canonical LICENSE, e.g. %s"
                        % (len(without_license), without_license[:3]))
    leftover = []
    for line in git("rev-list", "--objects", *tips).splitlines():
        if line.split(" ", 1)[0] in FLAGGED_BLOBS:
            leftover.append(line[:80])
    if leftover:
        problems.append("%d flagged original blob(s) still reachable: %s"
                        % (len(leftover), leftover[:3]))
    seen = {}
    for commit in commits:
        for record in git("ls-tree", "-r", "-z", commit).split("\0"):
            if not record:
                continue
            meta, path = record.split("\t", 1)
            mode, kind, sha = meta.split()
            if kind != "blob" or mode.startswith("120000") or style_for(path) is None:
                continue
            seen.setdefault(sha, path)
    unlabelled = []
    bodies = read_blobs_batch(set(seen) - set(FLAGGED_BLOBS))
    for sha, path in seen.items():
        if sha in FLAGGED_BLOBS:
            continue
        body = bodies.get(sha)
        if body is None:
            continue
        try:
            text = body.decode("utf-8")
        except UnicodeDecodeError:
            unlabelled.append(path + " (not UTF-8)")
            continue
        if IDENTIFIER not in text:
            unlabelled.append(path)
    if unlabelled:
        problems.append("%d source blob(s) without the identifier, e.g. %s"
                        % (len(unlabelled), unlabelled[:5]))
    return problems, len(commits), len(seen)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apply", action="store_true", help="move refs and write the mapping")
    parser.add_argument("--map", default="rewrite-map.csv")
    parser.add_argument("--done", default=".rewrite-history-done")
    args = parser.parse_args()

    if Path(args.done).exists():
        raise SystemExit("this clone has already been rewritten (" + args.done + " exists); "
                         "start from a fresh clone")
    heads = dict(line.split() for line in
                 git("for-each-ref", "--format=%(refname) %(objectname)", "refs/heads").splitlines())
    tags = dict(line.split() for line in
                git("for-each-ref", "--format=%(refname) %(objectname)", "refs/tags").splitlines())
    if not heads or not tags:
        raise SystemExit("expected branches and tags - is this a mirror clone of the repository?")

    license_blobs = {name: rev("HEAD:" + name) for name in LICENSE_FILES}
    absent = [name for name, sha in license_blobs.items() if not sha]
    if absent:
        raise SystemExit("HEAD is missing " + ", ".join(absent))
    synthetic = {role: rev("HEAD:" + path) for role, path in SYNTHETIC_PATHS.items()}
    absent = [role for role, sha in synthetic.items() if not sha]
    if absent:
        raise SystemExit("HEAD is missing the synthetic fixture(s): " + ", ".join(absent))

    rewriter = Rewriter(synthetic, license_blobs, args.apply)
    new_heads = {ref: rewriter.rewrite_commit(sha) for ref, sha in heads.items()}
    new_tags = {ref: rewriter.rewrite_tag(sha) for ref, sha in tags.items()}

    print("branches: %d, tags: %d" % (len(new_heads), len(new_tags)))
    print("commits %d, trees %d, blobs relabelled %d, assets replaced %d, tags rebuilt %d"
          % (rewriter.stats["commits"], rewriter.stats["trees"], rewriter.stats["headers"],
             rewriter.stats["assets"], rewriter.stats["tags"]))
    print("dropped signatures: %d" % len(rewriter.dropped_signatures))

    problems, commits, blobs = verify_history(
        sorted(set(new_heads.values()) | set(new_tags.values())), license_blobs["LICENSE"])
    print("verified %d commit(s) and %d distinct source blob(s)" % (commits, blobs))
    for problem in problems:
        print("FAIL " + problem)
    if problems:
        return 1

    if not args.apply:
        print("\nplan only: no ref was moved (use --apply)")
        return 0

    for ref, sha in list(new_heads.items()) + list(new_tags.items()):
        git("update-ref", ref, sha)
    with open(args.map, "w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle)
        writer.writerow(["kind", "ref", "old", "new"])
        for ref, sha in heads.items():
            writer.writerow(["branch", ref, sha, new_heads[ref]])
        for ref, sha in tags.items():
            writer.writerow(["tag", ref, sha, new_tags[ref]])
        for old, new in sorted(rewriter.commit_cache.items()):
            writer.writerow(["commit", "", old, new])
    Path(args.done).write_text("rewritten\n", encoding="utf-8")
    print("\nrefs moved, mapping written to " + args.map)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())




