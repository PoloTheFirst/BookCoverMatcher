"""Desktop proof of concept for the chrome-lens-py bridge.

Runs the *unmodified* upstream package the same way the Android app will: build a picture in
memory, hand it to LensAPI.process_image() as raw bytes, print the result shape.

    python tools/lens_poc.py [path/to/image.png]
"""

import asyncio
import io
import json
import sys

from chrome_lens_py import LensAPI
import chrome_lens_py


def sample_png() -> bytes:
    from PIL import Image, ImageDraw

    img = Image.new("RGB", (720, 300), "white")
    d = ImageDraw.Draw(img)
    d.text((30, 60), "THE HOBBIT", fill="black")
    d.text((30, 140), "J.R.R. TOLKIEN", fill="black")
    d.text((30, 210), "A fantasy novel", fill="black")
    buf = io.BytesIO()
    img.save(buf, "JPEG", quality=92)
    return buf.getvalue()


async def main() -> int:
    print("chrome-lens-py version:", chrome_lens_py.__version__)
    payload = open(sys.argv[1], "rb").read() if len(sys.argv) > 1 else sample_png()
    print("input bytes:", len(payload))

    api = LensAPI(timeout=45)
    try:
        result = await api.process_image(payload, output_format="blocks")
    finally:
        await api.aclose()

    print("RESULT KEYS:", sorted(result.keys()))
    slim = {}
    for k, v in result.items():
        if isinstance(v, (str, int, float, bool)) or v is None:
            slim[k] = v
        elif isinstance(v, list):
            slim[k] = f"<list len={len(v)}>"
        else:
            slim[k] = f"<{type(v).__name__}>"
    print("SCALAR VIEW:", json.dumps(slim, indent=2, default=str)[:1200])

    text = result.get("ocr_text")
    print("ocr_text:", repr(text))
    for i, b in enumerate(result.get("text_blocks") or [], 1):
        print(f"block {i}: keys={sorted(b)} text={b.get('text')!r} lines={len(b.get('lines') or [])}")
    wd = result.get("word_data") or []
    if wd:
        print("word_data[0]:", json.dumps(wd[0], default=str)[:300])
    print("objects:", json.dumps(result.get("objects"), default=str)[:300])
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
