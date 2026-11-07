#!/usr/bin/env python3
"""Extracts the PP-OCRv6 recognition dictionary, preserving every entry and its order.

Why this is a Python script and not the PowerShell loop it replaces
---------------------------------------------------------------
The dictionary that ships inside the model's ``inference.yml`` is a YAML **list** of 18,708 entries.
The original PowerShell extraction produced 18,704 - four entries vanished - and since PP-OCR maps
classes **by position**, four missing entries shift every following character: the decoder would have
produced plausible but wrong text. The four were lost for two different reasons, both reproducible:

* ``U+3000`` IDEOGRAPHIC SPACE (entry 1748): the raw line is ``- <U+3000>``, and ``String.Trim()``
  (or Python's ``str.strip()``) treats U+3000 as whitespace, leaving ``-`` which no longer starts
  with ``- `` and was skipped.
* ``U+303D`` (PART ALTERNATION MARK), ``U+FF9E`` and ``U+FF9F`` (halfwidth kana voicing marks):
  PowerShell's culture-sensitive ``StartsWith('- ')`` returns **False** for a line whose value
  begins with these collation-ignorable characters. ``[StringComparison]::Ordinal`` returns True.

This script therefore:

* reads the file as UTF-8 with no newline translation,
* keeps exactly one output line per list item, **including items that are empty or a space**,
* strips only ASCII spaces/tabs around a YAML scalar - never Unicode whitespace - so U+3000 survives,
* unquotes YAML single/double-quoted scalars (``''`` inside single quotes is one quote),
* never sorts, deduplicates, trims or normalises content - order is the model's order,
* reports the entry count and a SHA-256 of the produced file so a report can pin it.

The official vocabulary is also published as a file, which is the preferred source (it is committed
at ``native/ocr/assets/ppocrv6_dict.txt`` and fetched by ``tools/fetch-ppocrv6-models.ps1``):

    https://github.com/PaddlePaddle/PaddleOCR/blob/main/ppocr/utils/dict/ppocrv6_dict.txt
    git blob sha1 270082aa29aec1ff3ac74d0d575c90399216f74d   (74,947 bytes, 18,708 entries)

``--compare`` checks two dictionaries entry by entry and prints the first divergence, which is
how the "is the yml a truncated copy?" question is answered with evidence: after this fix the
yml-derived list and the official file are identical, 18,708 entries in the same order.

Usage
-----
    python tools/extract-ppocrv6-dict.py --yml rec_inference.yml -o ppocrv6_dict.txt
    python tools/extract-ppocrv6-dict.py --compare official.txt yml_derived.txt
"""

import argparse
import hashlib
import sys


def read_text(path):
    with open(path, "rb") as handle:
        return handle.read().decode("utf-8")


def unquote(raw):
    """YAML scalar -> its exact string value (single line only).

    Only ASCII spaces and tabs around the scalar are stripped. ``str.strip()`` would also remove
    U+3000 IDEOGRAPHIC SPACE - the very entry (index 1748) whose loss shifted 17,960 characters at
    commit ``ae362fd`` - so it must not be used here.
    """
    text = raw.rstrip("\n").rstrip("\r")
    stripped = text.strip(" \t")
    if len(stripped) >= 2 and stripped[0] == "'" and stripped[-1] == "'":
        return stripped[1:-1].replace("''", "'")
    if len(stripped) >= 2 and stripped[0] == '"' and stripped[-1] == '"':
        body = stripped[1:-1]
        return body.replace('\\"', '"').replace("\\n", "\n").replace("\\\\", "\\")
    return stripped


def from_yml(path):
    """Every item of `PostProcess: character_dict:` in file order."""
    text = read_text(path)
    marker = "character_dict:"
    start = text.find(marker)
    if start < 0:
        raise SystemExit("character_dict not found in %s" % path)
    entries = []
    for line in text[start + len(marker):].split("\n"):
        body = line.rstrip("\r")
        if not body.strip():
            if entries:
                break  # blank line after the list ends the block
            continue
        if not body.lstrip().startswith("- ") and not body.lstrip() == "-":
            break  # the next top-level key ends the block
        entries.append(unquote(body.lstrip()[1:]))
    return entries


def from_text_file(path):
    """One entry per line, exactly as written (a trailing newline is not an entry)."""
    text = read_text(path)
    if text.endswith("\n"):
        text = text[:-1]
    if text.endswith("\r"):
        text = text[:-1]
    return text.split("\n")


def describe(name, entries):
    digest = hashlib.sha256("\n".join(entries).encode("utf-8")).hexdigest().upper()
    empty = sum(1 for e in entries if e == "")
    wide = sum(1 for e in entries if len(e) > 1)
    print("%-22s entries=%-6d empty=%-3d multi-char=%-3d first=%r last=%r"
          % (name, len(entries), empty, wide, entries[0] if entries else None,
             entries[-1] if entries else None))
    print("%-22s content sha256=%s" % ("", digest))
    return entries


def compare(left, right):
    limit = min(len(left), len(right))
    for index in range(limit):
        if left[index] != right[index]:
            print("first divergence at index %d: left=%r right=%r"
                  % (index, left[index], right[index]))
            print("  context left : %r" % (left[max(0, index - 2):index + 3],))
            print("  context right: %r" % (right[max(0, index - 2):index + 3],))
            return 1
    if len(left) != len(right):
        print("counts differ: left=%d right=%d (identical up to %d)" % (len(left), len(right), limit))
        return 1
    print("identical: %d entries, same order" % len(left))
    return 0


def main(argv):
    # The vocabulary contains emoji and CJK; a cp1252 console would abort on them.
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--yml", help="inference.yml that embeds character_dict")
    parser.add_argument("-o", "--out", help="write the extracted dictionary here")
    parser.add_argument("--compare", nargs=2, metavar=("A", "B"),
                        help="compare two dictionary files entry by entry")
    parser.add_argument("--expect", type=int,
                        help="fail unless the produced dictionary has this many entries")
    args = parser.parse_args(argv)

    if args.compare:
        left = describe(args.compare[0], from_text_file(args.compare[0]))
        right = describe(args.compare[1], from_text_file(args.compare[1]))
        return compare(left, right)

    if not args.yml:
        parser.error("either --yml or --compare is required")
    entries = describe(args.yml, from_yml(args.yml))
    if args.expect is not None and len(entries) != args.expect:
        print("expected %d entries, got %d" % (args.expect, len(entries)), file=sys.stderr)
        return 2
    if args.out:
        with open(args.out, "wb") as handle:
            handle.write("\n".join(entries).encode("utf-8"))
            handle.write(b"\n")
        print("wrote %s" % args.out)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
