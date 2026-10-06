#!/usr/bin/env python3
"""
Creates a sample catalogue workbook so you can try the app (and tools/benchmark.py) without your own data.

    python tools/make_sample_workbook.py --count 24 --out sample_catalog.xlsx --covers-dir sample_covers
    python tools/make_sample_workbook.py --count 24 --series 3 ...      # volumes of the same series look alike

Every cover is stored the way the app expects it: as the **picture fill of a cell note** (Excel:
right-click a cell > Insert Note > Format Comment > Colors and Lines > Fill Effects > Picture).
Covers are placed in column A and B, row by row. `--covers-dir` also saves the covers and a few
simulated phone photos of them so you can test the "upload an image" path.

The file is built with openpyxl and then given Excel-style VML (`v:` / `x:` / `o:` prefixes). It has been
read back with ExcelJS, openpyxl and the app's extractor; it has not been opened in desktop Excel.
"""
from __future__ import annotations

import argparse
import io
import re
import sys
import zipfile
from pathlib import Path

import numpy as np
import openpyxl
from openpyxl.comments import Comment

sys.path.insert(0, str(Path(__file__).resolve().parent))
from synthetic import make_cover, simulate_capture  # noqa: E402


def vml_shape(shape_id: int, row: int, col: int, rid: str) -> str:
    return f""" <v:shape id="_x0000_s{shape_id}" type="#_x0000_t202" style='position:absolute;margin-left:59.25pt;margin-top:1.5pt;width:108pt;height:144pt;z-index:{shape_id - 1024};visibility:hidden' fillcolor="#ffffe1" o:insetmode="auto">
  <v:fill o:detectmouseclick="t" o:title="cover" o:relid="{rid}" recolor="t" rotate="t" type="frame"/>
  <v:shadow on="t" color="black" obscured="t"/><v:path o:connecttype="none"/>
  <v:textbox style='mso-direction-alt:auto'><div style='text-align:left'></div></v:textbox>
  <x:ClientData ObjectType="Note"><x:MoveWithCells/><x:SizeWithCells/><x:Anchor>{col + 1}, 15, {row}, 2, {col + 3}, 15, {row + 8}, 16</x:Anchor><x:AutoFill>False</x:AutoFill><x:Row>{row}</x:Row><x:Column>{col}</x:Column></x:ClientData>
 </v:shape>"""


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--count", type=int, default=24)
    ap.add_argument("--series", type=int, default=1, help="group size; >1 makes covers in a group look alike")
    ap.add_argument("--out", type=Path, default=Path("sample_catalog.xlsx"))
    ap.add_argument("--covers-dir", type=Path, default=None, help="also save covers + simulated phone photos here")
    ap.add_argument("--seed", type=int, default=0)
    args = ap.parse_args()

    args.out.parent.mkdir(parents=True, exist_ok=True)
    covers = [make_cover(i, args.series) for i in range(args.count)]
    cells = [((i // 2), (i % 2)) for i in range(args.count)]          # (row, col) zero-based: A1, B1, A2, ...

    wb = openpyxl.Workbook()
    ws = wb.active
    ws.title = "Catalog"
    ws.column_dimensions["A"].width = 18
    ws.column_dimensions["B"].width = 18
    for i, (r, c) in enumerate(cells):
        ws.cell(row=r + 1, column=c + 1, value=f"Book {i + 1}").comment = Comment("cover", "catalog")
    buf = io.BytesIO()
    wb.save(buf)

    src = zipfile.ZipFile(io.BytesIO(buf.getvalue()))
    out = zipfile.ZipFile(args.out, "w", zipfile.ZIP_DEFLATED)
    rel_entries = []
    for i, cover in enumerate(covers):
        b = io.BytesIO()
        cover.save(b, "PNG")
        out.writestr(f"xl/media/cover{i + 1}.png", b.getvalue())
        rel_entries.append(f'<Relationship Id="rId{i + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="../media/cover{i + 1}.png"/>')

    vml = ('<xml xmlns:v="urn:schemas-microsoft-com:vml" xmlns:o="urn:schemas-microsoft-com:office:office" xmlns:x="urn:schemas-microsoft-com:office:excel">\n'
           ' <o:shapelayout v:ext="edit"><o:idmap v:ext="edit" data="1"/></o:shapelayout>\n'
           ' <v:shapetype id="_x0000_t202" coordsize="21600,21600" o:spt="202" path="m,l,21600r21600,l21600,xe"><v:stroke joinstyle="miter"/><v:path gradientshapeok="t" o:connecttype="rect"/></v:shapetype>\n'
           + "\n".join(vml_shape(1025 + i, r, c, f"rId{i + 1}") for i, (r, c) in enumerate(cells)) + "\n</xml>")

    for item in src.infolist():
        data = src.read(item.filename)
        name = item.filename
        if name == "xl/drawings/commentsDrawing1.vml":
            continue                                   # replaced by Excel-style vmlDrawing1.vml below
        if name == "xl/comments/comment1.xml":
            name = "xl/comments1.xml"                  # Excel's part name (ExcelJS and Excel look for this one)
        if name == "xl/worksheets/_rels/sheet1.xml.rels":
            data = (data.decode()
                    .replace("/xl/drawings/commentsDrawing1.vml", "../drawings/vmlDrawing1.vml")
                    .replace("/xl/comments/comment1.xml", "../comments1.xml")).encode()
        if name == "[Content_Types].xml":
            text = data.decode().replace("/xl/comments/comment1.xml", "/xl/comments1.xml")
            if 'Extension="png"' not in text:
                text = text.replace("<Default Extension=\"xml\"", "<Default Extension=\"png\" ContentType=\"image/png\"/><Default Extension=\"xml\"", 1)
            data = text.encode()
        out.writestr(name, data)
    out.writestr("xl/drawings/vmlDrawing1.vml", vml)
    out.writestr("xl/drawings/_rels/vmlDrawing1.vml.rels",
                 '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
                 + "".join(rel_entries) + "</Relationships>")
    out.close()
    print(f"Wrote {args.out} with {args.count} covers in A1:B{(args.count + 1) // 2} (picture fills of cell notes)")

    if args.covers_dir:
        rng = np.random.default_rng(args.seed)
        args.covers_dir.mkdir(parents=True, exist_ok=True)
        for i, cover in enumerate(covers):
            cover.save(args.covers_dir / f"cover_{i + 1:02d}.png")
        for i in range(min(6, args.count)):
            simulate_capture(covers[i], rng).save(args.covers_dir / f"photo_of_cover_{i + 1:02d}.jpg", quality=88)
        print(f"Saved covers and simulated phone photos to {args.covers_dir}/")


if __name__ == "__main__":
    main()
