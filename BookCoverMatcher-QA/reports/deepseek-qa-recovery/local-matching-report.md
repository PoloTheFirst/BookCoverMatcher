# Local matching report

**Method:** emulator UI, on-device ONNX Runtime + OpenCLIP, bundled model
(`app/src/main/assets/models/openclip_image.onnx`). No network involved.

## Reference collection

`test-data/matching/matching_covers.xlsx` was generated for this engagement: 5 records whose legacy
comment images are the same synthetic covers used for camera QA, so a match could be graded rather
than merely observed.

## Results

1. **Pipeline runs end-to-end.** Scan (camera capture) -> target embedding -> `Link & Find Top 5`
   -> toast "Compared against 5 image(s)." -> ranked list on the Search tab.
2. **Ranking is ordered and transparent.** Five rows, `#1..#5`, each showing rank, cell, source kind
   and score: `D4 cos 0.38 · comment-fill 6.7%`, `D5 cos 0.28 0.0%`, `D6 0.28`, `D3 0.20`, `D2 0.19`.
   Cosine is strictly non-increasing, so ordering is correct.
3. **Correct-match test (image-file input, not camera).** Querying with an image from the device
   produced `#1 D4 cos 0.98 / 97.6%` against `#2 cos 0.51 / 25.7%` - the same record that supplies
   the matching reference image, with a wide margin. This was the "Choose image" path, so it tests
   embedding + ranking, not the camera.
4. **Correct-match test (camera).** The camera captures the emulator room scene, not a cover, so the
   resulting ranking (cos 0.38 max) is meaningless as a correctness signal. Recorded as **Blocked**
   for the camera path.
5. **Empty catalogue path.** With the default range `A1:B20` the catalogue contained no images and
   the app reported "No images found inside A1:B20 (checked floating images and comment fills)."
   Widening to `A1:Z30` produced all five results. Correct behaviour, poor default (see **UI-01**).

## Not tested

- Low-confidence thresholds and their UI treatment (no result crossed a "low confidence" boundary
  in a controlled way).
- Missing reference images / empty workbook beyond the A1:B20 case above.
- Very large reference collections (the perf_1000 fixture was verified for parsing, not for ranking
  latency).
- Inference failure handling (model is bundled and loaded; no failure was induced).
