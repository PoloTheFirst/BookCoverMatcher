"""Local test of the fixed Python bridge. NO NETWORK: LensAPI is replaced by a stub.

    python tools/lens_bridge_check.py
"""
import array
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                "..", "app", "src", "main", "python"))

import lens_bridge  # noqa: E402

FAILURES = []


def check(name, condition, extra=""):
    print(("  PASS  " if condition else "  FAIL  ") + name + (("   " + extra) if extra else ""))
    if not condition:
        FAILURES.append(name)


PNG = bytes([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]) + b"fake-payload" * 4
SEEN = {}


def stub(process):
    """Replace LensAPI with one whose process_image/aclose never touch the network."""
    async def aclose(self):
        return None

    def init(self, **kwargs):     # the real LensAPI takes a timeout= keyword
        self.kwargs = kwargs

    lens_bridge.LensAPI = type("LensAPI", (), {"__init__": init,
                                               "process_image": process,
                                               "aclose": aclose})


async def ok_process(self, image, **kwargs):
    SEEN["image"] = image
    SEEN["kwargs"] = kwargs
    return {
        "text_blocks": [
            {"text": "THE HOBBIT", "geometry": {"height": 0.075}},
            {"text": "J.R.R. TOLKIEN", "geometry": {"height": 0.020}},
        ],
        "word_data": [{"word": "THE"}, {"word": "HOBBIT"}],
        "detected_language": "en",
        "translated_text": None,
        "image_size": [1280, 900],
    }


def raising_process(boom):
    async def go(self, image, **kwargs):
        raise boom
    return go


print("\n== 1. H1: the argument type Kotlin actually sends ==")
check("memoryview accepted", lens_bridge.as_image_bytes(memoryview(PNG)) == PNG)
check("bytearray accepted", lens_bridge.as_image_bytes(bytearray(PNG)) == PNG)
check("bytes still accepted", lens_bridge.as_image_bytes(PNG) == PNG)
check("array('b') accepted; Java signed bytes become unsigned",
      lens_bridge.as_image_bytes(array.array("b", [1, 127, -128, -1])) == bytes([1, 127, 128, 255]))

print("\n== 2. H1 regression: the OLD isinstance guard rejected these ==")
for label, value in [("array('b')", array.array("b", [1, 2, 3])),
                     ("array('B')", array.array("B", [1, 2, 3]))]:
    old_guard = isinstance(value, (bytes, bytearray, memoryview))
    new_ok = True
    try:
        lens_bridge.as_image_bytes(value)
    except lens_bridge.BridgeError:
        new_ok = False
    check("old guard rejected %-11s (=%s), new code accepts it" % (label, old_guard),
          (not old_guard) and new_ok)

print("\n== 3. invalid input is rejected, never invented ==")
check("bytes(5) would silently invent 5 NUL bytes", bytes(5) == b"\x00\x00\x00\x00\x00")
for label, value in [("None", None), ("int 5", 5), ("str", "hello"), ("list", [1, 2]),
                     ("object", object())]:
    try:
        lens_bridge.as_image_bytes(value)
        check("%-16s rejected" % label, False, "it was accepted!")
    except lens_bridge.BridgeError as exc:
        check("%-16s -> bad_input" % label, exc.code == lens_bridge.ERROR_BAD_INPUT)

stub(ok_process)
empty_buf = lens_bridge.ocr_image(memoryview(b""))
check("empty buffer -> bad_input, not a request",
      empty_buf.get("ok") is False and empty_buf.get("error_code") == lens_bridge.ERROR_BAD_INPUT)

print("\n== 4. success envelope (stubbed, no network) ==")
ok = lens_bridge.ocr_image(memoryview(PNG), "en", None, 30)
check("ok is True", ok.get("ok") is True, str(ok.get("error_message")))
check("text joined by newline", ok.get("text") == "THE HOBBIT\nJ.R.R. TOLKIEN")
check("block_heights aligned with blocks", ok.get("block_heights") == "0.07500,0.02000")
check("block_count == 2", ok.get("block_count") == 2)
check("word_count == 2", ok.get("word_count") == 2)
check("detected_language == en", ok.get("detected_language") == "en")
check("translated_text is None", ok.get("translated_text") is None)
check("image size passed through", (ok.get("image_width"), ok.get("image_height")) == (1280, 900))
check("library_version present", ok.get("library_version") == lens_bridge.LENS_PY_VERSION)
check("payload reached the library as real bytes",
      isinstance(SEEN.get("image"), bytes) and SEEN["image"] == PNG)
check("library got the documented kwargs",
      SEEN["kwargs"] == {"ocr_language": "en", "target_translation_language": None,
                         "output_format": "blocks"})

print("\n== 5. empty OCR text is a SUCCESS, not a failure ==")


async def empty_process(self, image, **kwargs):
    return {"text_blocks": [], "word_data": [], "detected_language": None,
            "translated_text": None, "image_size": [10, 10]}


stub(empty_process)
res = lens_bridge.ocr_image(memoryview(PNG))
check("ok is True with no text", res.get("ok") is True)
check("text is an empty string", res.get("text") == "")
check("block_count is 0", res.get("block_count") == 0)

print("\n== 6. failures are classified by exception TYPE, not by text ==")
from chrome_lens_py.exceptions import (  # noqa: E402
    LensAPIError, LensAuthError, LensConnectionError, LensImageError,
    LensRateLimitError, LensTimeoutError,
)

for boom, expected in [
    (LensRateLimitError("429 too many"), lens_bridge.ERROR_RATE_LIMIT),
    (LensAuthError("401 no key"), lens_bridge.ERROR_AUTH),
    (LensTimeoutError("slow"), lens_bridge.ERROR_TIMEOUT),
    (LensConnectionError("dns"), lens_bridge.ERROR_NETWORK),
    (LensImageError("cannot decode"), lens_bridge.ERROR_IMAGE),
    (LensAPIError("500 server"), lens_bridge.ERROR_API),
    (ValueError("a bug in the bridge"), lens_bridge.ERROR_INTERNAL),
]:
    stub(raising_process(boom))
    out = lens_bridge.ocr_image(memoryview(PNG))
    check("%-22s -> %-11s" % (type(boom).__name__, expected),
          out.get("ok") is False and out.get("error_code") == expected,
          "got %r" % (out.get("error_code"),))
    check("    ...keeps a traceback in error_detail", bool(out.get("error_detail")))
    check("    ...no image bytes leak into the report",
          "fake-payload" not in str(out.get("error_message")) + str(out.get("error_detail")))

print("\n== 7. oversized image is rejected before any request ==")
stub(ok_process)
SEEN.clear()
big = lens_bridge.ocr_image(memoryview(bytearray(lens_bridge.MAX_IMAGE_BYTES + 1)))
check("oversized -> bad_input",
      big.get("ok") is False and big.get("error_code") == lens_bridge.ERROR_BAD_INPUT)
check("no request was attempted", "image" not in SEEN)

print("\n== 8. capabilities() envelope ==")
caps = lens_bridge.capabilities()
check("ok is True", caps.get("ok") is True)
check("library name is correct", caps.get("library") == "chrome-lens-py")
check("version is non-empty", bool(caps.get("version")))
check("declares it has no product search", "product_search" in caps.get("does_not_support", ""))

print("\n" + ("ALL BRIDGE CHECKS PASSED" if not FAILURES else "FAILURES: %s" % FAILURES))
sys.exit(1 if FAILURES else 0)

