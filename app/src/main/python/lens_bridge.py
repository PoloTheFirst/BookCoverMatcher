"""In-process bridge between the Kotlin app and the original ``chrome_lens_py`` package.

This file is the only place the app touches Python. It deliberately exposes *only* what
chrome-lens-py really implements: optical character recognition and translation of a single
picture through Google's Lens ``crupload`` endpoint. The library has no product- or
shopping-search feature, so nothing is invented here - product discovery lives on the Kotlin
side in ``online/OpenLibraryBooksSource.kt``.

The upstream package is used unmodified and version-pinned in ``app/build.gradle.kts``
(``chrome-lens-py==3.8.2``, MIT licensed, pure Python).

Every function here is synchronous and returns plain Python values so that Chaquopy can hand
them straight to Kotlin. ``LensAPI`` is asynchronous, so each call runs its own event loop on
the calling (background) thread; never call these from the Android main thread.

Failure reporting
-----------------
Both functions return an **envelope** rather than raising::

    {"ok": True,  ...result fields...}
    {"ok": False, "error_code": ..., "error_message": ..., "error_detail": ...}

Raising instead would force Kotlin to classify failures by matching text inside a ``PyException``
traceback, which is exactly how one bug was reported as one generic message. ``error_code`` is a
stable machine-readable category (see ERROR_CODES), ``error_message`` is safe to show, and
``error_detail`` is the Python traceback for the developer log only. No image data appears in any
of the three.
"""

import asyncio
import traceback

from chrome_lens_py import LensAPI
from chrome_lens_py import __version__ as LENS_PY_VERSION
from chrome_lens_py.exceptions import (
    LensAPIError,
    LensAuthError,
    LensConnectionError,
    LensException,
    LensImageError,
    LensRateLimitError,
    LensTimeoutError,
)

# A cover photo handed over as JPEG/PNG bytes. Anything bigger is a bug, not a use case.
MAX_IMAGE_BYTES = 12 * 1024 * 1024

# Stable contract with Kotlin - see online/OcrResultParser.kt. Rename here and there, together.
ERROR_BAD_INPUT = "bad_input"        # the caller handed over nothing usable
ERROR_IMAGE = "image"                # Lens/Pillow could not decode a real picture
ERROR_NETWORK = "network"            # DNS / TCP / TLS / proxy
ERROR_RATE_LIMIT = "rate_limit"      # 429 / 503 after the library's own retries
ERROR_AUTH = "auth"                  # the built-in API key was rejected
ERROR_TIMEOUT = "timeout"            # no answer in time
ERROR_API = "api"                    # any other Lens API failure
ERROR_INTERNAL = "internal"          # a bug in this bridge

ERROR_CODES = (
    ERROR_BAD_INPUT, ERROR_IMAGE, ERROR_NETWORK, ERROR_RATE_LIMIT,
    ERROR_AUTH, ERROR_TIMEOUT, ERROR_API, ERROR_INTERNAL,
)

# What the wrapped library can and cannot do. Surfaced in the UI so the limitation is visible
# rather than implied.
SUPPORTS = (
    "ocr_text",
    "detected_language",
    "translation",
    "word_geometry",
    "detected_objects",
)
DOES_NOT_SUPPORT = (
    "product_search",
    "shopping_results",
    "price_lookup",
)


class BridgeError(Exception):
    """A failure that already knows which category Kotlin should report it as."""

    def __init__(self, code, message):
        super(BridgeError, self).__init__(message)
        self.code = code
        self.message = message


def _ok(**fields):
    envelope = {"ok": True}
    envelope.update(fields)
    return envelope


def _failure(code, message, detail=None):
    return {
        "ok": False,
        "error_code": code,
        "error_message": message,
        "error_detail": detail if detail is not None else traceback.format_exc(),
    }


def capabilities():
    """Static description of the wrapped library, for diagnostics and for the UI."""
    return _ok(
        library="chrome-lens-py",
        version=LENS_PY_VERSION,
        supports=", ".join(SUPPORTS),
        does_not_support=", ".join(DOES_NOT_SUPPORT),
    )


def _clean(value):
    """Chaquopy gives us ``None`` for Kotlin ``null``; empty strings mean "not set" too."""
    if value is None:
        return None
    text = str(value).strip()
    return text or None


def as_image_bytes(value):
    """Return ``value`` as a standard ``bytes`` object, or raise :class:`BridgeError`.

    Kotlin hands over a Java ``byte[]``. Chaquopy represents a Java array as an instance of its
    own type, ``java.chaquopy.jarray``: it supports the **buffer protocol**, but it is *not* an
    instance of ``bytes``, ``bytearray`` or ``memoryview``. Testing for those three - which the
    first version of this file did - therefore rejected every real call from the app.

    Testing for the buffer protocol with ``memoryview`` accepts exactly the right set: real
    ``bytes``, ``bytearray``, ``memoryview``, ``array.array`` and Chaquopy's ``jarray``. It
    rejects ``None``, ``str``, ``int``, ``list`` and arbitrary objects, which matters because a
    plain ``bytes(value)`` would happily invent data for some of them - ``bytes(5)`` is five NUL
    bytes and ``bytes([104, 105])`` is ``b"hi"``.

    ``tobytes()`` copies once into a real ``bytes`` object and maps Java's signed bytes back to
    unsigned, which is what Pillow and the Lens request need.
    """
    if value is None:
        raise BridgeError(ERROR_BAD_INPUT, "No image data was supplied.")
    try:
        view = memoryview(value)
    except TypeError:
        raise BridgeError(
            ERROR_BAD_INPUT,
            "Image data must be a byte array, not %s." % type(value).__name__,
        )
    try:
        return view.tobytes()
    except Exception as exc:  # a buffer we cannot read is bad input, not an OCR failure
        raise BridgeError(ERROR_BAD_INPUT, "Image data could not be read: %s" % exc)


def classify(exc):
    """Map any exception to an ``(error_code, message)`` pair - by type, never by text."""
    if isinstance(exc, BridgeError):
        return exc.code, exc.message
    # Most specific first: LensRateLimitError and friends subclass LensAPIError, which
    # subclasses LensException, so ordering here is what makes the mapping correct.
    if isinstance(exc, LensRateLimitError):
        return ERROR_RATE_LIMIT, "Google Lens is throttling requests. Try again in a minute."
    if isinstance(exc, LensAuthError):
        return ERROR_AUTH, "Google Lens rejected the built-in API key."
    if isinstance(exc, LensTimeoutError):
        return ERROR_TIMEOUT, "Google Lens did not answer in time."
    if isinstance(exc, LensConnectionError):
        return ERROR_NETWORK, "Google Lens could not be reached. Check the connection."
    if isinstance(exc, LensImageError):
        return ERROR_IMAGE, "Google Lens could not read that picture."
    if isinstance(exc, LensAPIError):
        return ERROR_API, "Google Lens rejected the request."
    if isinstance(exc, LensException):
        return ERROR_API, "Google Lens reported an error."
    if isinstance(exc, asyncio.TimeoutError):
        return ERROR_TIMEOUT, "Google Lens did not answer in time."
    return ERROR_INTERNAL, "The Lens bridge failed unexpectedly."


def ocr_image(image_bytes, ocr_language=None, target_language=None, timeout=60):
    """Recognise the text in one encoded picture, optionally translating it.

    Args:
        image_bytes: the picture as a buffer object - a Java ``byte[]`` from Kotlin, or
            ``bytes`` / ``bytearray`` / ``memoryview`` from anywhere else.
        ocr_language: language Lens should assume for the source text, or ``None`` to detect.
        target_language: translate the recognised text into this language, or ``None``.
        timeout: seconds for the whole request.

    Returns:
        On success ``{"ok": True, ...}`` with:
            text               recognised text, one block per line ('' when nothing was found)
            block_heights      the same blocks as normalized glyph heights, comma separated -
                               the biggest glyph on a cover is nearly always the title
            block_count        number of text blocks Lens returned
            detected_language  language Lens detected, or None
            translated_text    translation, or None when not requested / none found
            image_width        width of the source picture in pixels
            image_height       height of the source picture in pixels
            word_count         number of recognised words
            library_version    chrome-lens-py version

        An empty ``text`` with ``ok`` true is a *successful* scan of a picture with no writing on
        it. It is not an error, and Kotlin renders it as "no text found".

        On failure ``{"ok": False, "error_code": ..., "error_message": ..., "error_detail": ...}``.
    """
    try:
        payload = as_image_bytes(image_bytes)
        if not payload:
            raise BridgeError(ERROR_BAD_INPUT, "The image is empty.")
        if len(payload) > MAX_IMAGE_BYTES:
            raise BridgeError(
                ERROR_BAD_INPUT,
                "The image is too large for the Lens endpoint (%d bytes)." % len(payload),
            )

        source = _clean(ocr_language)
        destination = _clean(target_language)
        try:
            timeout_s = float(timeout)
        except (TypeError, ValueError):
            raise BridgeError(ERROR_BAD_INPUT, "The timeout is not a number.")

        result = asyncio.run(_call(payload, source, destination, timeout_s))
        return _ok(**_flatten(result))
    except Exception as exc:  # noqa: BLE001 - every failure leaves as a coded envelope
        code, message = classify(exc)
        return _failure(code, message)


async def _call(payload, source, destination, timeout_s):
    """One Lens request on a private event loop owned by the calling background thread."""
    api = LensAPI(timeout=timeout_s)
    try:
        return await api.process_image(
            payload,
            ocr_language=source,
            target_translation_language=destination,
            output_format="blocks",
        )
    finally:
        await api.aclose()


def _flatten(result):
    """Reduce Lens's nested result to the flat fields Kotlin reads.

    Text and glyph height stay in the same order: the Kotlin side uses the height as the proxy
    for "this is the title", which is exactly what a cover designer uses.
    """
    texts = []
    heights = []
    for block in (result.get("text_blocks") or []):
        # One line per block, so ``text.splitlines()`` and ``block_heights`` stay aligned even
        # when Lens puts a line break inside a paragraph.
        text = " ".join(str(block.get("text") or "").split())
        if not text:
            continue
        texts.append(text)
        geometry = block.get("geometry") or {}
        try:
            heights.append(float(geometry.get("height") or 0.0))
        except (TypeError, ValueError):
            heights.append(0.0)

    size = result.get("image_size") or [0, 0]
    words = result.get("word_data") or []

    return {
        "text": "\n".join(texts),
        "block_heights": ",".join("%.5f" % h for h in heights),
        "block_count": len(texts),
        "detected_language": result.get("detected_language"),
        "translated_text": result.get("translated_text"),
        "image_width": int(size[0]) if len(size) > 0 else 0,
        "image_height": int(size[1]) if len(size) > 1 else 0,
        "word_count": len(words),
        "library_version": LENS_PY_VERSION,
    }
