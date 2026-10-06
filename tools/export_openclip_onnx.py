#!/usr/bin/env python3
"""
Export an OpenCLIP *image encoder* to ONNX for the BookCover Matcher Android app.

    pip install -r tools/requirements.txt
    python tools/export_openclip_onnx.py                     # ViT-B-32 / laion2b_s34b_b79k, int8 (~90 MB)
    python tools/export_openclip_onnx.py --precision fp32    # ~350 MB, closest to PyTorch
    python tools/export_openclip_onnx.py --model ViT-B-16 --pretrained laion2b_s34b_b88k

The result is written to app/src/main/assets/models/openclip_image.onnx (bundled into the APK), or
pass --out and use the in-app "Import model" button instead.

What the exported graph contains
  input  : pixel_values  float32 [1, 3, S, S]  - already normalised with the *CLIP* mean/std
           (0.4815, 0.4578, 0.4082 / 0.2686, 0.2613, 0.2758). If the chosen model expects a different
           normalisation, the conversion is baked into the graph so the app never has to know.
  output : embedding     float32 [1, D]         - NOT normalised (the app L2-normalises).

The script then checks the exported file against PyTorch, and (for int8) against the fp32 ONNX
model, and refuses to write a model whose embeddings drifted too far.
"""
from __future__ import annotations

import argparse
import contextlib
import hashlib
import io
import json
import os
import sys
import tempfile
from pathlib import Path

import numpy as np

CLIP_MEAN = (0.48145466, 0.4578275, 0.40821073)
CLIP_STD = (0.26862954, 0.26130258, 0.27577711)
DEFAULT_OUT = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "assets" / "models" / "openclip_image.onnx"


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--model", default="ViT-B-32", help="open_clip model name (default: ViT-B-32)")
    p.add_argument("--pretrained", default="laion2b_s34b_b79k", help="open_clip pretrained tag (default: laion2b_s34b_b79k)")
    p.add_argument("--precision", choices=["int8", "fp32"], default="int8",
                   help="int8 = dynamic-quantised MatMul weights (~4x smaller); fp32 = exact (default: int8)")
    p.add_argument("--out", type=Path, default=DEFAULT_OUT, help=f"output .onnx path (default: {DEFAULT_OUT})")
    p.add_argument("--opset", type=int, default=18, help="ONNX opset (default 18; ONNX Runtime Android 1.20 reads up to 21)")
    p.add_argument("--check-images", type=Path, default=None,
                   help="folder of JPG/PNG covers used for the fp32-vs-int8 drift check (recommended)")
    p.add_argument("--min-cosine", type=float, default=0.98,
                   help="abort if the mean cosine between fp32 and int8 embeddings is below this (default 0.98)")
    p.add_argument("--force", action="store_true", help="write the file even if the drift check fails")
    p.add_argument("--random-weights", action="store_true",
                   help="DEV ONLY: skip pretrained weights (tests the export pipeline offline; useless for matching)")
    return p.parse_args()


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def load_check_batch(folder: Path | None, size: int, n: int = 24) -> np.ndarray:
    """Pre-processed images (same pipeline as the app: whole image, bicubic, CLIP normalisation)."""
    from PIL import Image

    mean = np.array(CLIP_MEAN, dtype=np.float32)[:, None, None]
    std = np.array(CLIP_STD, dtype=np.float32)[:, None, None]
    tensors = []
    if folder is not None:
        files = sorted(p for p in folder.rglob("*") if p.suffix.lower() in {".jpg", ".jpeg", ".png", ".webp", ".bmp"})[:n]
        for f in files:
            img = Image.open(f).convert("RGB").resize((size, size), Image.BICUBIC)
            arr = np.asarray(img, dtype=np.float32).transpose(2, 0, 1) / 255.0
            tensors.append((arr - mean) / std)
    if not tensors:
        print("  (no --check-images given: using structured random test images; pass real covers for a trustworthy drift check)")
        rng = np.random.default_rng(0)
        yy, xx = np.mgrid[0:size, 0:size].astype(np.float32) / size
        for _ in range(n):
            f1, f2, f3 = rng.uniform(1, 6, 3)
            img = np.stack([0.5 + 0.4 * np.sin(f1 * xx * 6 + rng.uniform(0, 6)),
                            0.5 + 0.4 * np.cos(f2 * yy * 6 + rng.uniform(0, 6)),
                            0.5 + 0.4 * np.sin(f3 * (xx + yy) * 4 + rng.uniform(0, 6))])
            img = np.clip(img + rng.normal(0, 0.04, img.shape), 0, 1).astype(np.float32)
            tensors.append((img - mean) / std)
    return np.stack(tensors).astype(np.float32)


def cosine_rows(a: np.ndarray, b: np.ndarray) -> np.ndarray:
    a = a / np.linalg.norm(a, axis=1, keepdims=True)
    b = b / np.linalg.norm(b, axis=1, keepdims=True)
    return (a * b).sum(1)


def report_graph(path: Path) -> None:
    """Prints opset + op types and rejects custom-domain ops the Android runtime would not know."""
    import onnx

    m = onnx.load(str(path), load_external_data=False)
    opsets = {o.domain or "ai.onnx": o.version for o in m.opset_import}
    ops = sorted({n.op_type for n in m.graph.node if n.domain in ("", "ai.onnx")})
    custom = sorted({f"{n.domain}::{n.op_type}" for n in m.graph.node if n.domain not in ("", "ai.onnx")})
    print(f"  graph: opset {opsets}, {len(m.graph.node)} nodes, {len(ops)} distinct ops")
    if custom:
        raise SystemExit(f"ERROR: graph uses custom-domain ops {custom}; ONNX Runtime Mobile cannot run them.")
    if opsets.get("ai.onnx", 0) > 21:
        raise SystemExit(f"ERROR: opset {opsets['ai.onnx']} is newer than ONNX Runtime Android 1.20 supports (21). Use --opset 18.")


def main() -> int:
    args = parse_args()

    import onnx  # noqa: F401  (fail early with a clear message)
    import onnxruntime as ort
    import open_clip
    import torch

    torch.set_grad_enabled(False)

    print(f"Loading {args.model} ({'random weights' if args.random_weights else args.pretrained}) ...")
    model = open_clip.create_model(args.model, pretrained=None if args.random_weights else args.pretrained)
    model.eval()

    cfg = open_clip.get_model_preprocess_cfg(model)
    size = cfg["size"][0] if isinstance(cfg["size"], (tuple, list)) else int(cfg["size"])
    m_mean = tuple(cfg.get("mean", CLIP_MEAN))
    m_std = tuple(cfg.get("std", CLIP_STD))
    print(f"  input size {size}x{size}, model mean/std {tuple(round(x, 4) for x in m_mean)} / {tuple(round(x, 4) for x in m_std)}")

    class ImageEncoder(torch.nn.Module):
        """CLIP-normalised pixels in, raw (un-normalised) embedding out."""

        def __init__(self, clip):
            super().__init__()
            self.clip = clip
            self.register_buffer("in_mean", torch.tensor(CLIP_MEAN).view(1, 3, 1, 1))
            self.register_buffer("in_std", torch.tensor(CLIP_STD).view(1, 3, 1, 1))
            self.register_buffer("m_mean", torch.tensor(m_mean).view(1, 3, 1, 1))
            self.register_buffer("m_std", torch.tensor(m_std).view(1, 3, 1, 1))
            self.rebase = not (np.allclose(CLIP_MEAN, m_mean, atol=1e-4) and np.allclose(CLIP_STD, m_std, atol=1e-4))

        def forward(self, pixel_values):
            x = pixel_values
            if self.rebase:  # model wants a different normalisation than the app supplies
                x = (x * self.in_std + self.in_mean - self.m_mean) / self.m_std
            return self.clip.encode_image(x, normalize=False)

    enc = ImageEncoder(model).eval()
    if enc.rebase:
        print("  note: model normalisation differs from CLIP's - conversion is baked into the ONNX graph")

    dummy = torch.randn(1, 3, size, size)
    ref_dim = int(enc(dummy).shape[-1])
    print(f"  embedding dimension: {ref_dim}")

    out_path: Path = args.out
    out_path.parent.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory() as tmp:
        fp32_path = Path(tmp) / "fp32.onnx"
        print(f"Exporting ONNX (opset {args.opset}, batch size 1) ...")
        # The app always embeds one image at a time, so the graph is exported with a fixed batch of 1.
        quiet = io.StringIO()
        try:  # modern exporter (torch.export based) - the default since torch 2.9
            with contextlib.redirect_stdout(quiet), contextlib.redirect_stderr(quiet):
                program = torch.onnx.export(enc, (dummy,), input_names=["pixel_values"], output_names=["embedding"],
                                            opset_version=args.opset, dynamo=True)
                program.save(str(fp32_path))
        except Exception as first_error:  # older torch -> classic TorchScript exporter
            print(f"  dynamo exporter unavailable ({type(first_error).__name__}); using the legacy exporter")
            torch.onnx.export(enc, dummy, str(fp32_path), input_names=["pixel_values"], output_names=["embedding"],
                              opset_version=args.opset, do_constant_folding=True)
        report_graph(fp32_path)

        # ---- check fp32 ONNX against PyTorch ----
        batch = load_check_batch(args.check_images, size)
        sess32 = ort.InferenceSession(str(fp32_path), providers=["CPUExecutionProvider"])
        in_name = sess32.get_inputs()[0].name
        emb32 = np.concatenate([sess32.run(None, {in_name: batch[i:i + 1]})[0] for i in range(len(batch))])
        emb_pt = np.concatenate([enc(torch.from_numpy(batch[i:i + 1])).numpy() for i in range(len(batch))])
        cos_pt = cosine_rows(emb32, emb_pt)
        print(f"fp32 ONNX vs PyTorch: min cosine {cos_pt.min():.6f}, max |diff| {np.abs(emb32 - emb_pt).max():.2e}")
        if cos_pt.min() < 0.9999:
            print("ERROR: the ONNX export does not reproduce PyTorch. Not writing a model.", file=sys.stderr)
            return 2

        if args.precision == "fp32":
            final_path = fp32_path
            final_emb = emb32
        else:
            from onnxruntime.quantization import QuantType, quantize_dynamic

            prep_path = Path(tmp) / "fp32_prep.onnx"
            src = fp32_path
            try:
                from onnxruntime.quantization.shape_inference import quant_pre_process

                quant_pre_process(str(fp32_path), str(prep_path), skip_symbolic_shape=True)
                src = prep_path
            except Exception as e:  # pre-processing is optional
                print(f"  (quant pre-process skipped: {type(e).__name__}: {e})")
            q_path = Path(tmp) / "int8.onnx"
            print("Quantising weights to int8 (dynamic) ...")
            quantize_dynamic(str(src), str(q_path), weight_type=QuantType.QInt8, op_types_to_quantize=["MatMul"], per_channel=True)
            sess8 = ort.InferenceSession(str(q_path), providers=["CPUExecutionProvider"])
            emb8 = np.concatenate([sess8.run(None, {in_name: batch[i:i + 1]})[0] for i in range(len(batch))])
            cos8 = cosine_rows(emb32, emb8)
            print(f"int8 vs fp32 over {len(batch)} images: mean cosine {cos8.mean():.5f}, min {cos8.min():.5f}")
            # does the drift change which neighbour wins?
            n32 = emb32 / np.linalg.norm(emb32, axis=1, keepdims=True)
            n8 = emb8 / np.linalg.norm(emb8, axis=1, keepdims=True)
            sim32 = n32 @ n32.T; sim8 = n8 @ n8.T
            np.fill_diagonal(sim32, -2); np.fill_diagonal(sim8, -2)
            same_nn = float((sim32.argmax(1) == sim8.argmax(1)).mean())
            print(f"  nearest neighbour unchanged for {same_nn * 100:.0f}% of the check images")
            if cos8.mean() < args.min_cosine and not args.force:
                print(f"ERROR: int8 drift is too large (mean cosine {cos8.mean():.4f} < {args.min_cosine}). "
                      f"Re-run with --precision fp32, or --force to keep it anyway.", file=sys.stderr)
                return 3
            final_path = q_path
            final_emb = emb8

        out_path.write_bytes(final_path.read_bytes())

    digest = sha256_of(out_path)
    card = {
        "model": args.model,
        "pretrained": "random-weights" if args.random_weights else args.pretrained,
        "precision": args.precision,
        "input": {"name": "pixel_values", "size": size, "mean": list(CLIP_MEAN), "std": list(CLIP_STD)},
        "output": {"name": "embedding", "dim": int(final_emb.shape[1]), "normalized": False},
        "sha256": digest,
        "bytes": out_path.stat().st_size,
        "open_clip": getattr(open_clip, "__version__", "?"),
        "torch": torch.__version__,
        "onnxruntime": ort.__version__,
    }
    out_path.with_suffix(".json").write_text(json.dumps(card, indent=2) + "\n")
    print(f"\nWrote {out_path}  ({card['bytes'] / 1e6:.1f} MB, sha256 {digest[:16]}...)")
    print(f"Model card: {out_path.with_suffix('.json')}")
    if args.random_weights:
        print("WARNING: random weights - this file is only for testing the pipeline.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
