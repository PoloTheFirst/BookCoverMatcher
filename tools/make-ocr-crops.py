#!/usr/bin/env python3
"""Renders synthetic text-line crops for the native recogniser's multilingual smoke test.

Why this exists
---------------
`native/ocr/tests/real_models.rs` can decode a directory of images (`BC_OCR_CROPS`). The repository
only ships one real cover (The Hobbit, English), so this tool renders one crop per script with a
system font and writes a `manifest.json` recording the exact source string. The crops are written
outside the repository by default: they are test inputs, not assets, and must not be committed.

The output is **synthetic**. It proves that the recogniser returns meaningful text for Japanese,
Traditional Chinese, Simplified Chinese and English; it is not a scan, so it supports no CER claim.
Report results as a qualitative smoke test and compare them against the manifest.

Usage
-----
    python tools/make-ocr-crops.py                       # -> %TEMP%\\bc-ocr-crops
    python tools/make-ocr-crops.py --out C:\\tmp\\crops --size 96
    $env:BC_OCR_CROPS = "$env:TEMP\\bc-ocr-crops"
    cargo test --test real_models -- --ignored --nocapture ppocrv6_medium_reads_multilingual_crops

The file is deliberately ASCII-only: the samples are written as ``\\u`` escapes so that no editor,
console or `Get-Content` can transcode them. The same encoding hazard is what damaged the old
dictionary extraction (see `tools/extract-ppocrv6-dict.py`).
"""

import argparse
import json
import os
import sys

from PIL import Image, ImageDraw, ImageFont

# (file stem, script label, sample text, candidate font files in preference order)
SAMPLES = [
    ("01_en", "English", "THE HOBBIT",
     ["arial.ttf", "C:/Windows/Fonts/arial.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"]),
    ("02_ja", "Japanese", "\u65e5\u672c\u8a9e\u306e\u30c6\u30ad\u30b9\u30c8\u8a8d\u8b58",
     ["meiryo.ttc", "C:/Windows/Fonts/meiryo.ttc", "C:/Windows/Fonts/YuGothM.ttc",
      "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"]),
    ("03_ja_title", "Japanese (title + volume)", "\u543e\u8f29\u306f\u732b\u3067\u3042\u308b \u7b2c1\u5dfb",
     ["meiryo.ttc", "C:/Windows/Fonts/meiryo.ttc", "C:/Windows/Fonts/YuGothM.ttc",
      "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"]),
    ("04_zh_hant", "Traditional Chinese", "\u7e41\u9ad4\u4e2d\u6587\u6e2c\u8a66",
     ["msjh.ttc", "C:/Windows/Fonts/msjh.ttc", "C:/Windows/Fonts/mingliu.ttc",
      "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"]),
    ("05_zh_hans", "Simplified Chinese", "\u7b80\u4f53\u4e2d\u6587\u6d4b\u8bd5",
     ["msyh.ttc", "C:/Windows/Fonts/msyh.ttc", "C:/Windows/Fonts/simhei.ttf",
      "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"]),
    ("06_mixed", "Mixed Latin + kana", "COMIC \u30b3\u30df\u30c3\u30af \u7b2c12\u5dfb",
     ["meiryo.ttc", "C:/Windows/Fonts/meiryo.ttc", "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"]),
]


def load_font(candidates, size):
    for name in candidates:
        path = name if os.path.isabs(name) else os.path.join(os.environ.get("WINDIR", "C:/Windows/Fonts"), name)
        if os.path.isfile(path):
            try:
                return ImageFont.truetype(path, size), path
            except OSError:
                continue
    raise SystemExit("no usable font found in %r - install one or pass a supported platform" % (candidates,))


def render(text, size, font_path_candidates, margin=24):
    font, font_path = load_font(font_path_candidates, size)
    left, top, right, bottom = font.getbbox(text)
    width = right - left + 2 * margin
    height = bottom - top + 2 * margin
    image = Image.new("RGB", (width, height), (255, 255, 255))
    draw = ImageDraw.Draw(image)
    draw.text((margin - left, margin - top), text, font=font, fill=(0, 0, 0))
    return image, font_path


def main(argv):
    # The samples are CJK; a cp1252 console would abort on them (see the encoding note above).
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", default=os.path.join(os.environ.get("TEMP", "/tmp"), "bc-ocr-crops"),
                        help="output directory (default: %%TEMP%%\\bc-ocr-crops)")
    parser.add_argument("--size", type=int, default=72, help="font size in pixels (default 72)")
    args = parser.parse_args(argv)

    os.makedirs(args.out, exist_ok=True)
    manifest = []
    for stem, label, text, fonts in SAMPLES:
        image, font_path = render(text, args.size, fonts)
        path = os.path.join(args.out, stem + ".png")
        image.save(path)
        manifest.append({
            "file": os.path.basename(path),
            "script": label,
            "text": text,
            "font": font_path,
            "size": list(image.size),
        })
        print("%-12s %-26s %s  %s" % (stem, label, image.size, text))

    with open(os.path.join(args.out, "manifest.json"), "w", encoding="utf-8") as handle:
        json.dump(manifest, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    print("\nwrote %d crops + manifest.json to %s" % (len(manifest), args.out))
    print("run: set BC_OCR_CROPS to that directory, then "
          "cargo test --test real_models -- --ignored --nocapture ppocrv6_medium_reads_multilingual_crops")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
