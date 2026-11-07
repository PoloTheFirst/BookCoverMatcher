# SPDX-License-Identifier: GPL-3.0-or-later

"""Deterministic synthetic OCR fixtures with hand-written ground truth.

Development tool only: nothing here is packaged in the APK. The fixtures are generated (not
photographed), so they are legally distributable and reproducible; the real-world covers the user
reported are *not* committed (see `metrics.py --help` for the local-fixture convention).

    python tools/ocr-reference/make_fixtures.py [--out DIR]

Writes `fixtures/*.png` and `truth.json` (regions + title + language per fixture).
"""

from __future__ import annotations

import argparse
import json
import random
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

FONT_JA = "C:/Windows/Fonts/meiryo.ttc"
FONT_HANT = "C:/Windows/Fonts/msjh.ttc"
FONT_HANS = "C:/Windows/Fonts/msyh.ttc"
FONT_EN = "C:/Windows/Fonts/arialbd.ttf"

SIZE = (600, 850)


def _font(path: str, size: int) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(path, size)


def _art_background(seed: int, soft: bool = True) -> Image.Image:
    """A dark cover with coloured blobs and a little grain: text over illustration, not on paper."""
    rng = random.Random(seed)
    img = Image.new("RGB", SIZE, (26, 28, 38))
    draw = ImageDraw.Draw(img)
    for _ in range(26):
        cx, cy = rng.randrange(SIZE[0]), rng.randrange(SIZE[1])
        r = rng.randrange(40, 190)
        colour = (rng.randrange(30, 190), rng.randrange(30, 170), rng.randrange(60, 220))
        draw.ellipse([cx - r, cy - r, cx + r, cy + r], fill=colour)
    if soft:
        img = img.filter(ImageFilter.GaussianBlur(radius=3))
    grain = Image.effect_noise(SIZE, 18).convert("L")
    img = Image.blend(img, Image.merge("RGB", (grain, grain, grain)), 0.10)
    return img


def _text_layer(size, text, font, fill, outline=None, stroke=0) -> Image.Image:
    layer = Image.new("RGBA", size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)
    draw.text(
        (size[0] // 2, size[1] // 2),
        text,
        font=font,
        fill=fill,
        anchor="mm",
        stroke_width=stroke,
        stroke_fill=outline,
    )
    return layer


def _paste_text(img, text, font_path, font_size, xy, fill, seed, angle=0.0, stroke=3) -> list[int]:
    """Pastes one text line and returns its axis-aligned box in image coordinates."""
    font = _font(font_path, font_size)
    probe = ImageDraw.Draw(Image.new("RGBA", (10, 10)))
    l, t, r, b = probe.textbbox((0, 0), text, font=font, stroke_width=stroke)
    w, h = r - l + 8, b - t + 8
    layer = _text_layer((w, h), text, font, fill, (12, 12, 16, 235), stroke)
    if angle:
        layer = layer.rotate(angle, resample=Image.BICUBIC, expand=True)
    x, y = xy
    img.paste(layer, (x, y), layer)
    return [x, y, x + layer.width, y + layer.height]


def fixture_ja_horizontal() -> tuple[Image.Image, dict]:
    img = _art_background(11)
    box = _paste_text(img, "じれったい、2ギルR", FONT_JA, 54, (40, 360), (255, 255, 255), seed=11)
    return img, {
        "lang": "ja",
        "title": "じれったい、2ギルR",
        "regions": [{"text": "じれったい、2ギルR", "box": box}],
    }


def fixture_ja_vertical() -> tuple[Image.Image, dict]:
    img = _art_background(12)
    column = "縦書き小説"
    font = _font(FONT_JA, 56)
    step = 68
    x, y0 = 430, 170
    for i, ch in enumerate(column):
        layer = _text_layer((80, 80), ch, font, (255, 245, 230), (10, 10, 14, 230), 3)
        img.paste(layer, (x - 40, y0 + i * step - 40), layer)
    box = [x - 44, y0 - 44, x + 44, y0 + len(column) * step]
    return img, {"lang": "ja", "title": column, "regions": [{"text": column, "box": box}]}


def fixture_ja_rotated() -> tuple[Image.Image, dict]:
    img = _art_background(13)
    box = _paste_text(img, "じれったい、2", FONT_JA, 52, (90, 330), (255, 250, 210), seed=13, angle=9.0)
    r18 = _paste_text(img, "R-18", FONT_EN, 26, (20, 20), (255, 40, 40), seed=13)
    return img, {
        "lang": "ja",
        "title": "じれったい、2",
        "regions": [
            {"text": "じれったい、2", "box": box},
            {"text": "R-18", "box": r18},
        ],
    }



def fixture_ja_decorative() -> tuple[Image.Image, dict]:
    """Scattered, multi-coloured characters: the reported cover's hardest feature."""
    img = _art_background(14)
    text = "じれったい"
    rng = random.Random(141)
    colours = [(255, 230, 90), (255, 140, 170), (140, 240, 255), (255, 255, 255), (180, 255, 170)]
    x, y = 70, 250
    boxes = []
    for ch in text:
        size = rng.randrange(48, 76)
        angle = rng.randrange(-12, 13)
        box = _paste_text(
            img, ch, FONT_JA, size, (x, y + rng.randrange(-18, 18)),
            rng.choice(colours), seed=141, angle=float(angle), stroke=2,
        )
        boxes.append(box)
        x += size + rng.randrange(4, 16)
    boxes.append([x - 44, y - 44, x + size, y + size])
    return img, {
        "lang": "ja",
        "title": text,
        # One region: the engine reads the scattered characters as a single line, so scoring them
        # per character would punish a correct read. The per-character boxes stay for inspection.
        "regions": [{"text": text, "box": [min(b[0] for b in boxes), min(b[1] for b in boxes),
                                           max(b[2] for b in boxes), max(b[3] for b in boxes)]}],
        "character_boxes": boxes,
    }


def fixture_zh_hant() -> tuple[Image.Image, dict]:
    img = _art_background(15)
    box = _paste_text(img, "繁體中文測試封面", FONT_HANT, 46, (30, 400), (255, 255, 255), seed=15)
    return img, {
        "lang": "zh-Hant",
        "title": "繁體中文測試封面",
        "regions": [{"text": "繁體中文測試封面", "box": box}],
    }


def fixture_zh_hans() -> tuple[Image.Image, dict]:
    img = _art_background(16)
    box = _paste_text(img, "简体中文测试封面", FONT_HANS, 46, (30, 400), (255, 255, 255), seed=16)
    return img, {
        "lang": "zh-Hans",
        "title": "简体中文测试封面",
        "regions": [{"text": "简体中文测试封面", "box": box}],
    }


def fixture_en() -> tuple[Image.Image, dict]:
    img = _art_background(17)
    t1 = _paste_text(img, "FINAL FANTASY SEVEN", FONT_EN, 34, (60, 120), (255, 255, 255), seed=17)
    t2 = _paste_text(img, "Tifa Lockhart FAN BOOK", FONT_EN, 30, (60, 170), (255, 240, 200), seed=17)
    t3 = _paste_text(img, "R-18", FONT_EN, 26, (20, 20), (255, 40, 40), seed=17)
    return img, {
        "lang": "en",
        "title": "Tifa Lockhart FAN BOOK",
        "regions": [
            {"text": "FINAL FANTASY SEVEN", "box": t1},
            {"text": "Tifa Lockhart FAN BOOK", "box": t2},
            {"text": "R-18", "box": t3},
        ],
    }


FIXTURES = {
    "ja_horizontal": fixture_ja_horizontal,
    "ja_vertical": fixture_ja_vertical,
    "ja_rotated": fixture_ja_rotated,
    "ja_decorative": fixture_ja_decorative,
    "zh_hant": fixture_zh_hant,
    "zh_hans": fixture_zh_hans,
    "en": fixture_en,
}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--out",
        default=str(Path(__file__).resolve().parent / "out" / "fixtures"),
        help="directory for the generated PNGs and truth.json",
    )
    args = parser.parse_args()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    truth = {"generator": "tools/ocr-reference/make_fixtures.py", "fixtures": []}
    for name, build in FIXTURES.items():
        img, meta = build()
        path = out / f"{name}.png"
        img.save(path)
        truth["fixtures"].append({"name": name, "file": str(path), "image_size": list(img.size), **meta})
        print(f"{name:14s} {img.size} {len(meta['regions'])} region(s) -> {path}")

    (out / "truth.json").write_text(json.dumps(truth, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"truth -> {out / 'truth.json'}")


if __name__ == "__main__":
    main()
