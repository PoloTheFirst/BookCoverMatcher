#!/usr/bin/env python3
"""
Benchmark the v1 pHash matcher against OpenCLIP + FAISS on YOUR catalogue.

For every cover in the catalogue the script simulates several phone photos of it (perspective, rotation,
lighting, glare, blur, noise, JPEG - see tools/synthetic.py), then asks each engine "which catalogue entry
is this?" and reports top-1 / top-5 accuracy, mean reciprocal rank, speed, and how the app's High/Mid/Low
levels would behave. Use it to confirm the engine switch on your data and to tune ScoreScale.

Catalogue sources (pick one):
  --xlsx catalog.xlsx --range A1:B20   pictures stored in cell notes (+ anchored pictures, like the app)
  --images folder/                     a folder of cover images
  --demo 24 [--series 3]               synthetic covers (series > 1 = volumes that look alike)

Engines:
  pHash          always (the exact v1 OpenCV pipeline: equalizeHist, 3x3 blur, 32x32, DCT, median)
  OpenCLIP       --onnx app/src/main/assets/models/openclip_image.onnx     (what the app runs)
                 --open-clip ViT-B-32:laion2b_s34b_b79k                    (PyTorch weights, any open_clip model)
  Searches use faiss.IndexFlatIP, the exact index the app's FlatIpIndex mirrors.

Example:
  python tools/benchmark.py --xlsx catalog.xlsx --range A1:B40 --onnx app/src/main/assets/models/openclip_image.onnx --trials 5
"""
from __future__ import annotations

import argparse
import io
import json
import re
import sys
import time
import zipfile
from pathlib import Path

import cv2
import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))
from synthetic import make_cover, simulate_capture  # noqa: E402

CLIP_MEAN = np.array([0.48145466, 0.4578275, 0.40821073], dtype=np.float32)[:, None, None]
CLIP_STD = np.array([0.26862954, 0.26130258, 0.27577711], dtype=np.float32)[:, None, None]
HIGH_PERCENT, MID_PERCENT = 70.0, 45.0   # keep in sync with ScoreScale.kt


# --------------------------------------------------------------------------------------------
# Catalogue loading (mirrors the app's Excel workflow: cell-note picture fills + anchored pictures)
# --------------------------------------------------------------------------------------------

def col_letters(i: int) -> str:
    s, n = "", i + 1
    while n > 0:
        n, r = divmod(n - 1, 26)
        s = chr(65 + r) + s
    return s


def parse_range(text: str):
    m = re.match(r"^([A-Z]+)(\d+)\s*:\s*([A-Z]+)(\d+)$", text.strip().upper().replace("$", ""))
    if not m:
        raise SystemExit("Invalid range - use a format like A1:B20.")
    col = lambda L: sum((ord(c) - 64) * 26 ** i for i, c in enumerate(reversed(L))) - 1
    a, b = (col(m[1]), int(m[2]) - 1), (col(m[3]), int(m[4]) - 1)
    return min(a[1], b[1]), max(a[1], b[1]), min(a[0], b[0]), max(a[0], b[0])


def resolve(base: str, target: str) -> str:
    if target.startswith("/"):
        return target[1:]
    stack: list[str] = []
    for p in (base + target).split("/"):
        if p in ("", "."):
            continue
        if p == "..":
            if stack:
                stack.pop()
        else:
            stack.append(p)
    return "/".join(stack)


def rels_map(text: str) -> dict[str, str]:
    out = {}
    for el in re.findall(r"<Relationship\b[^>]*/?>", text):
        i, t = re.search(r'\bId="([^"]+)"', el), re.search(r'\bTarget="([^"]+)"', el)
        if i and t:
            out[i[1]] = t[1]
    return out


def load_image(data: bytes) -> Image.Image | None:
    try:
        return Image.open(io.BytesIO(data)).convert("RGBA")
    except Exception:
        return None


def flatten(img: Image.Image, max_edge: int) -> Image.Image:
    bg = Image.new("RGBA", img.size, (255, 255, 255, 255))
    bg.alpha_composite(img)
    out = bg.convert("RGB")
    s = min(1.0, max_edge / max(out.size))
    return out.resize((max(1, round(out.width * s)), max(1, round(out.height * s))), Image.LANCZOS) if s < 1 else out


def extract_xlsx(path: Path, range_text: str, floating: bool):
    r1, r2, c1, c2 = parse_range(range_text)
    z = zipfile.ZipFile(path)
    names = z.namelist()
    items: list[tuple[str, str, Image.Image]] = []

    if floating:  # first sheet's anchored pictures (same rules as WorkbookImageExtractor.kt)
        try:
            wb = z.read("xl/workbook.xml").decode("utf8", "replace")
            wrels = z.read("xl/_rels/workbook.xml.rels").decode("utf8", "replace")
            rid = re.search(r'\br:id="([^"]+)"', re.search(r"<sheet\b[^>]*>", wb)[0])[1]
            sheet = resolve("xl/", rels_map(wrels)[rid])
            srels = z.read(sheet.rsplit("/", 1)[0] + "/_rels/" + sheet.rsplit("/", 1)[1] + ".rels").decode("utf8", "replace")
            drawing = None
            for el in re.findall(r"<Relationship\b[^>]*/?>", srels):
                if re.search(r'\bType="[^"]*/drawing"', el):
                    drawing = resolve(sheet.rsplit("/", 1)[0] + "/", re.search(r'\bTarget="([^"]+)"', el)[1])
                    break
            if drawing:
                dxml = z.read(drawing).decode("utf8", "replace")
                drels = rels_map(z.read(drawing.rsplit("/", 1)[0] + "/_rels/" + drawing.rsplit("/", 1)[1] + ".rels").decode("utf8", "replace"))
                for m in re.finditer(r"<(?:\w+:)?(twoCellAnchor|oneCellAnchor|absoluteAnchor)\b[^>]*>([\s\S]*?)</(?:\w+:)?\1>", dxml):
                    body = m[2]
                    fr = re.search(r"<(?:\w+:)?from\b[^>]*>([\s\S]*?)</(?:\w+:)?from>", body)
                    if not fr or not re.search(r"<(?:\w+:)?pic\b", body):
                        continue
                    col = re.search(r"<(?:\w+:)?col\b[^>]*>\s*(\d+)\s*</", fr[1])
                    row = re.search(r"<(?:\w+:)?row\b[^>]*>\s*(\d+)\s*</", fr[1])
                    emb = re.search(r"<(?:\w+:)?blip\b[^>]*?\b(?:\w+:)?embed=\"([^\"]+)\"", body)
                    if not (col and row and emb) or emb[1] not in drels:
                        continue
                    row_i, col_i = int(row[1]), int(col[1])
                    if not (r1 <= row_i <= r2 and c1 <= col_i <= c2):
                        continue
                    media = resolve(drawing.rsplit("/", 1)[0] + "/", drels[emb[1]])
                    img = load_image(z.read(media)) if media in names else None
                    if img:
                        items.append((f"{col_letters(col_i)}{row_i + 1}", "floating", img))
        except (KeyError, TypeError, AttributeError):
            pass

    taken: set[str] = set()
    for vml_path in [n for n in names if re.match(r"^xl/drawings/vmlDrawing\d+\.vml$", n, re.I)]:
        vml = z.read(vml_path).decode("utf8", "replace")
        rels_path = "xl/drawings/_rels/" + vml_path.rsplit("/", 1)[1] + ".rels"
        if rels_path not in names:
            continue
        rm = rels_map(z.read(rels_path).decode("utf8", "replace"))
        for sm in re.finditer(r"<v:shape\s[^>]*>([\s\S]*?)</v:shape>", vml, re.I):
            row = re.search(r"<x:Row>\s*(\d+)\s*</x:Row>", sm[1], re.I)
            col = re.search(r"<x:Column>\s*(\d+)\s*</x:Column>", sm[1], re.I)
            if not (row and col):
                continue
            row_i, col_i = int(row[1]), int(col[1])
            if not (r1 <= row_i <= r2 and c1 <= col_i <= c2):
                continue
            rid = None
            for pat in (r'<v:fill\b[^>]*\bo:relid="([^"]+)"', r'<v:imagedata\b[^>]*\br:id="([^"]+)"',
                        r'<v:fill\b[^>]*\br:id="([^"]+)"', r'<v:imagedata\b[^>]*\bo:relid="([^"]+)"'):
                mm = re.search(pat, sm[0], re.I)
                if mm:
                    rid = mm[1]
                    break
            if not rid or rid not in rm:
                continue
            media = resolve("xl/drawings/", rm[rid])
            cell = f"{col_letters(col_i)}{row_i + 1}"
            if media not in names or cell in taken:
                continue
            img = load_image(z.read(media))
            if img:
                taken.add(cell)
                items.append((cell, "comment-fill", img))

    seen, out = set(), []
    for cell, src, img in items:                    # first picture per cell wins (floating first)
        if cell not in seen:
            seen.add(cell)
            out.append((cell, src, img))
    return out


def load_catalogue(args):
    if args.xlsx:
        return extract_xlsx(args.xlsx, args.range, not args.no_floating)
    if args.images:
        files = sorted(p for p in args.images.rglob("*") if p.suffix.lower() in {".jpg", ".jpeg", ".png", ".webp", ".bmp"})
        return [(p.stem, "file", im) for p in files if (im := load_image(p.read_bytes()))]
    n = args.demo or 24
    return [(f"C{i + 1}", "synthetic", make_cover(i, args.series).convert("RGBA")) for i in range(n)]


# --------------------------------------------------------------------------------------------
# Engines
# --------------------------------------------------------------------------------------------

def phash_v1(rgb: np.ndarray) -> np.ndarray:
    """The v1 OpenCV.js pipeline (js/phash.js, phashWithOpenCV)."""
    gray = cv2.cvtColor(rgb, cv2.COLOR_RGB2GRAY)
    gray = cv2.equalizeHist(gray)
    gray = cv2.GaussianBlur(gray, (3, 3), 0)
    small = cv2.resize(gray, (32, 32), interpolation=cv2.INTER_AREA)
    coeffs = cv2.dct(small.astype(np.float32))[:8, :8].reshape(-1)
    ac = np.sort(coeffs[1:])
    mid = len(ac) // 2
    median = ac[mid] if len(ac) % 2 else (ac[mid - 1] + ac[mid]) / 2
    return coeffs > median


class PHashEngine:
    name = "pHash (v1)"

    def __init__(self):
        self.cells: list[str] = []
        self.hashes = np.zeros((0, 64), bool)

    def build(self, catalogue):
        self.cells = [c for c, _, _ in catalogue]
        self.hashes = np.stack([phash_v1(np.asarray(flatten(im, 640))) for _, _, im in catalogue])

    def rank(self, query: Image.Image, k: int):
        h = phash_v1(np.asarray(flatten(query.convert("RGBA"), 800)))
        dist = (self.hashes != h[None]).sum(1)
        order = sorted(range(len(dist)), key=lambda i: (dist[i], self.cells[i]))[:k]
        return order, [float(1 - dist[i] / 32.0) for i in order]


def preprocess_clip(img: Image.Image, size: int) -> np.ndarray:
    rgb = flatten(img.convert("RGBA"), 800).resize((size, size), Image.BICUBIC)     # whole image, like the app
    arr = np.asarray(rgb, dtype=np.float32).transpose(2, 0, 1) / 255.0
    return ((arr - CLIP_MEAN) / CLIP_STD).astype(np.float32)


class ClipEngine:
    def __init__(self, label: str, embed_fn, size: int):
        self.name, self.embed_fn, self.size = label, embed_fn, size
        self.cells: list[str] = []
        self.index = None
        self.baseline = 0.5

    def embed(self, img: Image.Image) -> np.ndarray:
        v = self.embed_fn(preprocess_clip(img, self.size)[None])[0].astype(np.float32)
        return v / max(np.linalg.norm(v), 1e-12)

    def build(self, catalogue):
        import faiss

        self.cells = [c for c, _, _ in catalogue]
        vecs = np.stack([self.embed(im) for _, _, im in catalogue]).astype(np.float32)
        self.index = faiss.IndexFlatIP(vecs.shape[1])
        self.index.add(vecs)
        self.vecs = vecs
        sims = vecs @ vecs.T
        iu = np.triu_indices(len(vecs), 1)
        self.baseline = float(np.clip(np.median(sims[iu]), 0, 0.9)) if len(vecs) >= 3 else 0.5

    def rank(self, query: Image.Image, k: int):
        q = self.embed(query)[None]
        scores, ids = self.index.search(q, len(self.cells))        # full ranking, then the app's tie-break (cell address)
        order = sorted(range(len(self.cells)), key=lambda j: (-scores[0][j], self.cells[int(ids[0][j])]))[:k]
        return [int(ids[0][j]) for j in order], [float(scores[0][j]) for j in order]


def make_clip_engines(args) -> list[ClipEngine]:
    engines = []
    if args.onnx:
        import onnxruntime as ort

        sess = ort.InferenceSession(str(args.onnx), providers=["CPUExecutionProvider"])
        inp = sess.get_inputs()[0]
        size = int(inp.shape[-1]) if isinstance(inp.shape[-1], int) else 224
        engines.append(ClipEngine(f"OpenCLIP ONNX ({args.onnx.name})", lambda x: sess.run(None, {inp.name: x})[0], size))
    if args.open_clip:
        import open_clip
        import torch

        name, _, tag = args.open_clip.partition(":")
        model = open_clip.create_model(name, pretrained=tag or None).eval()
        cfg = open_clip.get_model_preprocess_cfg(model)
        size = cfg["size"][0] if isinstance(cfg["size"], (tuple, list)) else int(cfg["size"])
        mean, std = np.array(cfg["mean"], np.float32)[:, None, None], np.array(cfg["std"], np.float32)[:, None, None]

        def embed(x, model=model, mean=mean, std=std):
            x = x * CLIP_STD[None] + CLIP_MEAN[None]                 # back to 0..1, then the model's own normalisation
            x = (x - mean[None]) / std[None]
            with torch.no_grad():
                return model.encode_image(torch.from_numpy(x.astype(np.float32))).numpy()

        engines.append(ClipEngine(f"OpenCLIP torch ({args.open_clip})", embed, size))
    return engines


# --------------------------------------------------------------------------------------------

def evaluate(engine, catalogue, queries, topk=5):
    t0 = time.perf_counter()
    engine.build(catalogue)
    build_s = time.perf_counter() - t0
    ranks, times, top_scores, true_scores, top1_ok = [], [], [], [], []
    for true_idx, q in queries:
        t = time.perf_counter()
        order, scores = engine.rank(q, len(catalogue))
        times.append(time.perf_counter() - t)
        rank = order.index(true_idx) + 1
        ranks.append(rank)
        top1_ok.append(rank == 1)
        top_scores.append(scores[0])
        true_scores.append(scores[order.index(true_idx)])
    ranks = np.array(ranks)
    res = dict(engine=engine.name, queries=len(ranks), top1=float((ranks == 1).mean()), top5=float((ranks <= topk).mean()),
               mrr=float((1.0 / ranks).mean()), build_s=build_s, ms_per_query=1000 * float(np.mean(times)))
    if isinstance(engine, ClipEngine):
        pct = lambda c: 100 * np.clip((np.array(c) - engine.baseline) / (1 - engine.baseline), 0, 1)
        top_pct, ok = pct(top_scores), np.array(top1_ok)
        res["baseline_cosine"] = engine.baseline
        res["genuine_cosine_p5_p50"] = [float(np.percentile(true_scores, 5)), float(np.percentile(true_scores, 50))]
        for label, thr in (("HIGH", HIGH_PERCENT), ("MID", MID_PERCENT)):
            hit = top_pct >= thr
            res[f"{label}_share_of_correct"] = float(hit[ok].mean()) if ok.any() else 0.0
            res[f"{label}_share_of_wrong"] = float(hit[~ok].mean()) if (~ok).any() else 0.0
    return res


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--xlsx", type=Path)
    ap.add_argument("--range", default="A1:B20")
    ap.add_argument("--no-floating", action="store_true", help="read cell-note pictures only")
    ap.add_argument("--images", type=Path)
    ap.add_argument("--demo", type=int, nargs="?", const=24)
    ap.add_argument("--series", type=int, default=1, help="with --demo: group size of look-alike covers")
    ap.add_argument("--onnx", type=Path)
    ap.add_argument("--open-clip", help="NAME:PRETRAINED, e.g. ViT-B-32:laion2b_s34b_b79k")
    ap.add_argument("--trials", type=int, default=4, help="simulated photos per cover")
    ap.add_argument("--strength", type=float, default=1.0, help="distortion strength (1.0 = typical handheld photo)")
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--json", type=Path, help="also write the results here")
    args = ap.parse_args()
    if not (args.xlsx or args.images or args.demo):
        args.demo = 24

    catalogue = load_catalogue(args)
    if len(catalogue) < 2:
        raise SystemExit("Need at least 2 catalogue images.")
    print(f"Catalogue: {len(catalogue)} images ({', '.join(sorted({s for _, s, _ in catalogue}))})")

    rng = np.random.default_rng(args.seed)
    queries = []
    for idx, (_, _, im) in enumerate(catalogue):
        base = flatten(im, 800)
        for _ in range(args.trials):
            queries.append((idx, simulate_capture(base, rng, args.strength)))
    print(f"Queries:   {len(queries)} simulated phone photos (strength {args.strength}, seed {args.seed})\n")

    engines = [PHashEngine()] + make_clip_engines(args)
    if len(engines) == 1:
        print("(no --onnx / --open-clip given: only the pHash baseline is measured)\n")
    results = [evaluate(e, catalogue, queries) for e in engines]

    print(f"{'engine':46s} {'top-1':>7s} {'top-5':>7s} {'MRR':>6s} {'ms/query':>9s}")
    for r in results:
        print(f"{r['engine'][:46]:46s} {100 * r['top1']:6.1f}% {100 * r['top5']:6.1f}% {r['mrr']:6.3f} {r['ms_per_query']:9.1f}")
    for r in results:
        if "baseline_cosine" in r:
            print(f"\n{r['engine']}\n  catalogue baseline cosine (median impostor): {r['baseline_cosine']:.3f}"
                  f"\n  cosine to the TRUE cover: p5 {r['genuine_cosine_p5_p50'][0]:.3f}, median {r['genuine_cosine_p5_p50'][1]:.3f}"
                  f"\n  top-1 shown as HIGH (>= {HIGH_PERCENT:.0f}%): {100 * r['HIGH_share_of_correct']:.0f}% of correct, {100 * r['HIGH_share_of_wrong']:.0f}% of wrong results"
                  f"\n  top-1 shown as HIGH or MID (>= {MID_PERCENT:.0f}%): {100 * r['MID_share_of_correct']:.0f}% of correct, {100 * r['MID_share_of_wrong']:.0f}% of wrong results"
                  f"\n  -> if wrong results are often HIGH, raise ScoreScale.HIGH_PERCENT; if correct ones are rarely HIGH, lower it.")
    if args.json:
        args.json.write_text(json.dumps(results, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
