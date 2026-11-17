# Image extraction report

**Method:** actual emulator UI, Images tab per fixture (`All / Linked / Unreferenced / Unresolved` chips
plus the image list), cross-checked against independent OOXML analysis.

## Result

For every fixture that imported, the Images "All" chip equalled the manifest's `media_total`, and the
Unresolved chip equalled `expected.unresolved`. No fixture produced an image attribution that
contradicted its comment cells.

Evidence highlights:

| Fixture | All | Linked | Unreferenced | Unresolved | Meaning |
|---|---|---|---|---|---|
| `image_two_per_note` | 4 | 4 | 0 | 0 | two fills on one note both listed |
| `image_broken_relid` | 3 | 2 | 0 | 1 | rId999 surfaced as unresolved, not a crash |
| `image_missing_media` | 2 | 1 | 0 | 1 | ghost.png reported missing |
| `valid_floating_images` | 6 | 4 | 2 | 0 | floating pictures extracted and counted unlinked |
| `image_unreferenced_media` | 8 | 3 | 5 | 0 | five orphan parts reported |
| `perf_1000_mixed` | 640 | 600 | 40 | 0 | 40 orphans alongside 600 linked covers |

## Association correctness

Each result row on the Search tab names its source cell and source kind, e.g.
`Cell D4 · cos 0.38 · comment-fill`. For `matching_covers.xlsx` the five rows mapped to D2-D6,
exactly the five comment cells, in rank order. Images therefore belong to the right records, not
merely the right count.

## Notes

- Corrupt image bytes (`image_corrupt_bytes`) resolve at the model level and fail at decode time
  without taking the screen down.
- `image_comment_without_shape` yields a text-only comment (no image entry) - correct.
- Floating-vs-note on the same cell de-duplicates to one picture per cell.

## Not covered

- Pixel-level rendering of every extracted image (associations and counts were verified; a sample of
  thumbnails was inspected visually).
- Export of extracted images to device storage.
