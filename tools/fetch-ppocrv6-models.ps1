# SPDX-License-Identifier: GPL-3.0-or-later

# Fetches the official PP-OCRv6 ONNX models and the official recognition dictionary.
#
# Run from the repository root:
#     powershell -ExecutionPolicy Bypass -File tools/fetch-ppocrv6-models.ps1
#
# The models are Apache-2.0 and published by PaddlePaddle:
#     https://huggingface.co/PaddlePaddle/PP-OCRv6_medium_det_onnx   (62,032,837 B)
#     https://huggingface.co/PaddlePaddle/PP-OCRv6_medium_rec_onnx   (76,554,979 B)
#
# They are ~139 MB together, so they are NOT committed (see native/ocr/.gitignore). The script is
# idempotent: an existing file whose size matches is left alone. Nothing is written into the APK by
# this script.
#
# The DICTIONARY is different: it is 75 KB of text, it is committed at
# native/ocr/assets/ppocrv6_dict.txt, and this script re-downloads it only to verify that the
# committed copy still matches upstream.
#
# Why the dictionary is not parsed out of inference.yml any more
# -------------------------------------------------------------
# `inference.yml` embeds the same 18,708 characters as a YAML list, but one entry - index 1748,
# U+3000 IDEOGRAPHIC SPACE - is written in a form that whitespace-trimming parsers turn into an
# empty line. An empty entry is not a harmless blank: PP-OCR maps classes by position, so dropping
# it shifts every character after it and produces plausible-looking nonsense. The official
# `ppocr/utils/dict/ppocrv6_dict.txt` has no such ambiguity (one character per line, none empty),
# and its git blob hash can be checked.

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$outDir = Join-Path $root 'native\ocr\models'
$assetDict = Join-Path $root 'native\ocr\assets\ppocrv6_dict.txt'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$officialDictUrl = 'https://raw.githubusercontent.com/PaddlePaddle/PaddleOCR/main/ppocr/utils/dict/ppocrv6_dict.txt'
# git blob sha1 of the upstream file, and the sha256 of its exact bytes.
$officialDictBlobSha1 = '270082aa29aec1ff3ac74d0d575c90399216f74d'
$officialDictSha256 = 'B5F2BFE2BDD9448429E3E82B51C789775D9B42F2403D082B00662EB77E401C5D'
# 18,708 characters + CTC blank + the appended space class = the model's 18,710 outputs.
$expectedEntries = 18708

function Get-Model($url, $file, $expectedSize) {
    $path = Join-Path $outDir $file
    if ((Test-Path $path) -and ((Get-Item $path).Length -eq $expectedSize)) {
        Write-Host "present: $file ($expectedSize bytes)"
        return $path
    }
    Write-Host "downloading: $url"
    & curl.exe -L --fail --silent --show-error -o $path $url
    if (-not (Test-Path $path)) { throw "download failed: $url" }
    $size = (Get-Item $path).Length
    if ($size -ne $expectedSize) { throw "$file is $size bytes, expected $expectedSize - re-run" }
    Write-Host "ok: $file ($size bytes)"
    return $path
}

$det = Get-Model 'https://huggingface.co/PaddlePaddle/PP-OCRv6_medium_det_onnx/resolve/main/inference.onnx' 'ppocrv6_medium_det.onnx' 62032837
$rec = Get-Model 'https://huggingface.co/PaddlePaddle/PP-OCRv6_medium_rec_onnx/resolve/main/inference.onnx' 'ppocrv6_medium_rec.onnx' 76554979

# Dictionary: fetch, verify the hash, and keep the committed asset in step with upstream.
$tempDict = Join-Path $outDir 'ppocrv6_dict.txt'
Write-Host "downloading: $officialDictUrl"
& curl.exe -L --fail --silent --show-error -o $tempDict $officialDictUrl
$sha = (Get-FileHash $tempDict -Algorithm SHA256).Hash
$blob = (git hash-object $tempDict)
$entries = (Get-Content $tempDict -Encoding utf8).Count
Write-Host "dictionary: $entries entries, sha256 $sha, blob $blob"
if ($blob -ne $officialDictBlobSha1) {
    Write-Warning "upstream blob $blob differs from the recorded $officialDictBlobSha1 - review before using it"
}
if ($sha -ne $officialDictSha256) {
    Write-Warning "sha256 $sha differs from the recorded $officialDictSha256 - review before using it"
}
if ($entries -ne $expectedEntries) {
    throw "dictionary has $entries entries, expected $expectedEntries (model needs 18,710 classes = entries + blank + space)"
}
if (-not (Test-Path $assetDict) -or (Get-FileHash $assetDict -Algorithm SHA256).Hash -ne $sha) {
    Copy-Item $tempDict $assetDict -Force
    Write-Host "updated native/ocr/assets/ppocrv6_dict.txt"
}

Write-Host ''
Write-Host 'Models are in:' $outDir
Write-Host 'Detector post-processing (from PP-OCRv6_medium_det inference.yml):'
Write-Host '  DBPostProcess thresh=0.2 box_thresh=0.45 unclip_ratio=1.4 max_candidates=3000'
Write-Host 'Recognition preprocessing (from PP-OCRv6_medium_rec inference.yml):'
Write-Host '  RecResizeImg image_shape=[3,48,320], BGR, (v/255-0.5)/0.5, right-padded with 0'
Write-Host 'Recognition dictionary: 18,708 entries -> classes 0 blank, 1..18708 characters, 18709 space'
