#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Check every packaged 64-bit ELF, including Python asset archives, for 16 KB LOAD alignment.

Usage: python tools/verify-native-pages.py app/build/outputs/apk/debug/app-debug.apk
Also accepts AABs and individual .so files. ZIP alignment and runtime tests are separate checks.
"""
import argparse
import io
import json
import struct
import zipfile
from pathlib import Path


def inspect(name, data):
    if data[:4] != b"\x7fELF" or data[4] != 2:
        raise ValueError(f"Not a supported ELF64 library: {name}")
    order = "<" if data[5] == 1 else ">"
    offset = struct.unpack_from(order + "Q", data, 32)[0]
    size, count = struct.unpack_from(order + "HH", data, 54)
    loads = []
    relros = []
    for index in range(count):
        kind, flags, file_offset, address, _, _, memory_size, alignment = struct.unpack_from(
            order + "IIQQQQQQ", data, offset + index * size)
        if kind == 1:
            loads.append({"alignment": alignment, "congruent": (address - file_offset) % 16384 == 0,
                          "address": address, "end": address + memory_size, "writable": bool(flags & 2)})
        elif kind == 0x6474E552:
            relros.append((address, address + memory_size))
    relro_ok = all(end % 16384 == 0 or not any(
        load["writable"] and load["address"] <= start < load["end"] and end < load["end"]
        for load in loads) for start, end in relros)
    return {"path": name, "loads": loads, "relro_pass": relro_ok,
            "pass": bool(loads) and relro_ok and all(x["alignment"] >= 16384 and x["congruent"] for x in loads)}


def scan(name, data):
    if data[:4] == b"\x7fELF":
        yield inspect(name, data)
    elif zipfile.is_zipfile(io.BytesIO(data)):
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            for member in archive.infolist():
                if member.filename.endswith((".so", ".zip", ".imy")):
                    yield from scan(name + "!" + member.filename, archive.read(member))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("artifact", type=Path)
    parser.add_argument("--json", type=Path)
    args = parser.parse_args()
    results = list(scan(args.artifact.name, args.artifact.read_bytes()))
    if args.json:
        args.json.write_text(json.dumps(results, indent=2) + "\n", encoding="utf-8")
    failures = [row for row in results if not row["pass"]]
    for row in failures:
        print("FAIL", row["path"], row["loads"])
    print(f"ELF64: {len(results)} checked, {len(failures)} failed (including nested asset archives)")
    raise SystemExit(1 if failures or not results else 0)
