# SPDX-License-Identifier: GPL-3.0-or-later
"""Resolve a BCM Android version to its manifest name and Google Play version code."""
import argparse
import json
import re
from pathlib import Path

VERSION_FILE = Path(__file__).resolve().parents[1] / "gradle" / "android-version.txt"
VERSION = re.compile(
    r"v?(?P<name>(?P<major>0|[1-9][0-9]{0,3})\.(?P<minor>0|[1-9][0-9]?)"
    r"\.(?P<patch>0|[1-9][0-9]?)(?:-dev\.(?P<dev>[1-9][0-9]?))?)"
)


def resolve_version(value: str) -> tuple[str, int]:
    match = VERSION.fullmatch(value)
    if not match:
        raise ValueError(f"Unsupported Android version {value!r}. Use M.m.p or M.m.p-dev.N.")
    major, minor, patch = (int(match[key]) for key in ("major", "minor", "patch"))
    if major > 2099 or minor > 98 or patch > 98:
        raise ValueError("Android version limits are major 2099, minor 98 and patch 98.")

    code = major * 1_000_000 + minor * 10_000 + patch * 100
    if match["dev"] is not None:
        code += int(match["dev"]) - 100
    if not 1 <= code <= 2_100_000_000:
        raise ValueError("Android version code must be between 1 and 2,100,000,000.")
    return match["name"], code


def source_identity(tag: str) -> tuple[str, int]:
    if not tag.startswith("v"):
        raise ValueError("A BCM release source tag starts with v.")
    name, code = resolve_version(tag.removesuffix("-plus"))
    archive = Path(__file__).resolve().parent / "archive-rebuild" / "artifacts.json"
    for build in json.loads(archive.read_text(encoding="utf-8")):
        if build["sourceTag"] == tag:
            # Some early public release tags differed from the name packaged on Android.
            if build["code"] != code:
                raise ValueError("Historical release tag and recorded rebuild code disagree.")
            return build["versionName"], code
    return name, code


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("version", nargs="?", help="Android version name or v-prefixed tag")
    parser.add_argument("--source-tag", help="Release source tag, including BCM Plus -plus aliases")
    args = parser.parse_args()
    if args.version is not None and args.source_tag is not None:
        parser.error("Specify either an Android version or a source tag.")
    try:
        if args.source_tag is not None:
            name, code = source_identity(args.source_tag)
        else:
            value = args.version if args.version is not None else VERSION_FILE.read_text(encoding="utf-8").rstrip("\r\n")
            name, code = resolve_version(value)
    except ValueError as exc:
        parser.error(str(exc))
    print(name, code)


if __name__ == "__main__":
    main()
