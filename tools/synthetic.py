"""Synthetic book covers and a "phone camera" simulator, shared by the sample-workbook and benchmark tools."""
from __future__ import annotations

import colorsys

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont

WORDS = ["Silent", "Harbor", "Crimson", "Atlas", "Ember", "Lunar", "Garden", "Paper", "Iron", "Velvet", "Orbit", "Tide",
         "Whisper", "Cobalt", "Sparrow", "Midnight", "Saffron", "Echo", "Glass", "Northern", "Citadel", "Ivory", "Thorn",
         "Meridian", "Lantern", "Dust", "Aurora", "Cipher", "Willow", "Zenith", "Marble", "Harvest", "Ripple", "Falcon"]
FIRST = ["A.", "M.", "J.", "L.", "R.", "S.", "K.", "T.", "E.", "N."]
LAST = ["Hale", "Moreau", "Tanaka", "Okafor", "Lindqvist", "Reyes", "Novak", "Bianchi", "Chen", "Haddad", "Silva", "Kovacs"]


def _font(size: int) -> ImageFont.FreeTypeFont | ImageFont.ImageFont:
    try:
        return ImageFont.load_default(size=size)
    except TypeError:  # very old Pillow
        return ImageFont.load_default()


def _hsv(h, s, v):
    r, g, b = colorsys.hsv_to_rgb(h % 1.0, s, v)
    return int(r * 255), int(g * 255), int(b * 255)


def make_cover(index: int, series_size: int = 1, w: int = 400, h: int = 600) -> Image.Image:
    """
    One synthetic cover. With series_size > 1, covers in the same group share layout, title and palette
    and differ only in artwork details and the volume number - the hard case (think manga volumes).
    """
    group, vol = divmod(index, series_size)
    g = np.random.default_rng(10_000 + group)          # shared "series" design
    v = np.random.default_rng(20_000 + index)          # per-volume variation
    hue = g.uniform()
    top = _hsv(hue, g.uniform(0.35, 0.9), g.uniform(0.55, 0.95))
    bottom = _hsv(hue + g.uniform(0.05, 0.3), g.uniform(0.4, 0.95), g.uniform(0.25, 0.6))
    grad = np.linspace(0, 1, h)[:, None, None]
    img = (np.array(top)[None, None, :] * (1 - grad) + np.array(bottom)[None, None, :] * grad)
    img = np.broadcast_to(img, (h, w, 3)).astype(np.uint8).copy()
    pil = Image.fromarray(img, "RGB")
    d = ImageDraw.Draw(pil, "RGBA")

    for _ in range(int(g.integers(3, 8))):                # shared decoration
        kind = int(g.integers(0, 3))
        x0, y0 = int(g.integers(-40, w)), int(g.integers(-40, h))
        x1, y1 = x0 + int(g.integers(40, 260)), y0 + int(g.integers(40, 300))
        col = _hsv(hue + g.uniform(-0.1, 0.4), g.uniform(0.3, 1), g.uniform(0.5, 1)) + (int(g.integers(50, 150)),)
        if kind == 0:
            d.ellipse((x0, y0, x1, y1), fill=col)
        elif kind == 1:
            d.rectangle((x0, y0, x1, y1), fill=col)
        else:
            d.polygon([(x0, y1), ((x0 + x1) // 2, y0), (x1, y1)], fill=col)
    for _ in range(int(v.integers(2, 6))):                # per-volume artwork
        x0, y0 = int(v.integers(0, w - 60)), int(v.integers(int(h * 0.35), int(h * 0.8)))
        col = _hsv(hue + v.uniform(0.0, 0.6), v.uniform(0.3, 1), v.uniform(0.5, 1)) + (int(v.integers(90, 200)),)
        d.ellipse((x0, y0, x0 + int(v.integers(30, 120)), y0 + int(v.integers(30, 120))), fill=col)

    title = " ".join(g.choice(WORDS, size=int(g.integers(2, 4)), replace=False))
    words = title.upper().split()
    ty = int(h * 0.08)
    for word in words:
        f = _font(int(g.integers(46, 70)))
        d.text((w // 2, ty), word, font=f, fill=(255, 255, 255, 235), anchor="ma")
        ty += int(f.size * 1.15) if hasattr(f, "size") else 60
    if series_size > 1:
        d.rounded_rectangle((w - 110, h - 130, w - 20, h - 60), 14, fill=(0, 0, 0, 150))
        d.text((w - 65, h - 95), f"VOL.{vol + 1}", font=_font(30), fill=(255, 255, 255, 255), anchor="mm")
    author = f"{g.choice(FIRST)} {g.choice(LAST)}"
    d.text((w // 2, h - 40), author.upper(), font=_font(26), fill=(255, 255, 255, 220), anchor="mm")
    d.rectangle((10, 10, w - 11, h - 11), outline=(255, 255, 255, 120), width=3)
    return pil


# ---------------------------------------------------------------------------------------------
# Camera simulation: the cover is photographed on a table, then the app crops its guide rectangle.
# ---------------------------------------------------------------------------------------------

def simulate_capture(cover: Image.Image, rng: np.random.Generator, strength: float = 1.0,
                     view_w: int = 480, view_h: int = 640, guide_w: float = 0.70, guide_h: float = 0.78) -> Image.Image:
    """Returns the guide-rectangle crop of a simulated phone photo of `cover`."""
    cv_cover = np.asarray(cover.convert("RGB"))
    ch, cw = cv_cover.shape[:2]

    # background: smooth coloured gradient + fine noise (table / hand)
    c0, c1 = rng.uniform(20, 200, 3), rng.uniform(20, 200, 3)
    t = np.linspace(0, 1, view_h)[:, None, None]
    bg = (c0[None, None, :] * (1 - t) + c1[None, None, :] * t)
    bg = np.broadcast_to(bg, (view_h, view_w, 3)).copy()
    bg += rng.normal(0, 6, bg.shape)
    bg = np.clip(bg, 0, 255).astype(np.uint8)

    # target quad: cover roughly fills the guide, with scale/shift/perspective/rotation
    gw, gh = view_w * guide_w, view_h * guide_h
    fit = min(gw / cw, gh / ch) * rng.uniform(0.82, 1.08)
    tw, th = cw * fit, ch * fit
    cx = view_w / 2 + rng.uniform(-0.05, 0.05) * view_w * strength
    cy = view_h / 2 + rng.uniform(-0.05, 0.05) * view_h * strength
    quad = np.array([[-tw / 2, -th / 2], [tw / 2, -th / 2], [tw / 2, th / 2], [-tw / 2, th / 2]], np.float32)
    jitter = rng.uniform(-0.045, 0.045, (4, 2)) * np.array([tw, th]) * strength   # perspective
    ang = np.deg2rad(rng.uniform(-6, 6) * strength)
    rot = np.array([[np.cos(ang), -np.sin(ang)], [np.sin(ang), np.cos(ang)]], np.float32)
    dst = (quad + jitter) @ rot.T + np.array([cx, cy], np.float32)
    src = np.array([[0, 0], [cw, 0], [cw, ch], [0, ch]], np.float32)
    M = cv2.getPerspectiveTransform(src, dst.astype(np.float32))
    warped = cv2.warpPerspective(cv_cover, M, (view_w, view_h), flags=cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT)
    mask = cv2.warpPerspective(np.full((ch, cw), 255, np.uint8), M, (view_w, view_h), flags=cv2.INTER_LINEAR)
    m = (mask.astype(np.float32) / 255.0)[..., None]
    img = (warped * m + bg * (1 - m)).astype(np.float32)

    # lighting: gamma / contrast / colour cast / uneven illumination / specular glare
    gamma = rng.uniform(0.7, 1.45) ** strength
    img = 255 * (img / 255.0) ** gamma
    img = (img - 128) * rng.uniform(0.75, 1.2) + 128 + rng.uniform(-25, 25) * strength
    img *= 1 + rng.uniform(-0.1, 0.1, 3) * strength
    yy, xx = np.mgrid[0:view_h, 0:view_w].astype(np.float32)
    ang2 = rng.uniform(0, 2 * np.pi)
    img *= (1 + 0.28 * strength * ((np.cos(ang2) * (xx / view_w - .5) + np.sin(ang2) * (yy / view_h - .5))))[..., None]
    if rng.uniform() < 0.5 * strength:
        gx, gy = rng.uniform(0.25, 0.75) * view_w, rng.uniform(0.25, 0.75) * view_h
        gs = rng.uniform(25, 90)
        glare = np.exp(-(((xx - gx) / gs) ** 2 + ((yy - gy) / (gs * 0.6)) ** 2)) * rng.uniform(60, 190)
        img += glare[..., None]
    img = np.clip(img, 0, 255).astype(np.uint8)

    # optics: defocus / motion blur, sensor noise
    sigma = rng.uniform(0.0, 1.6) * strength
    if sigma > 0.15:
        img = cv2.GaussianBlur(img, (0, 0), sigma)
    if rng.uniform() < 0.3 * strength:
        k = int(rng.integers(5, 11)); kernel = np.zeros((k, k), np.float32); kernel[k // 2, :] = 1.0 / k
        mat = cv2.getRotationMatrix2D((k / 2 - .5, k / 2 - .5), rng.uniform(0, 180), 1.0)
        kernel = cv2.warpAffine(kernel, mat, (k, k)); kernel /= max(kernel.sum(), 1e-6)
        img = cv2.filter2D(img, -1, kernel)
    img = np.clip(img.astype(np.float32) + rng.normal(0, 7 * strength, img.shape), 0, 255).astype(np.uint8)
    ok, buf = cv2.imencode(".jpg", cv2.cvtColor(img, cv2.COLOR_RGB2BGR), [cv2.IMWRITE_JPEG_QUALITY, int(rng.integers(45, 90))])
    img = cv2.cvtColor(cv2.imdecode(buf, cv2.IMREAD_COLOR), cv2.COLOR_BGR2RGB)

    x0 = int((view_w - gw) / 2); y0 = int((view_h - gh) / 2)
    return Image.fromarray(img[y0:y0 + int(gh), x0:x0 + int(gw)], "RGB")
