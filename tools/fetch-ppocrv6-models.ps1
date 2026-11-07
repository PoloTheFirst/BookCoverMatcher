# Fetches the official PP-OCRv6 ONNX models and extracts the recognition dictionary.
#
# Run from the repository root:
#     powershell -ExecutionPolicy Bypass -File tools/fetch-ppocrv6-models.ps1
#
# The artifacts are Apache-2.0 and published by PaddlePaddle:
#     https://huggingface.co/PaddlePaddle/PP-OCRv6_medium_det_onnx   (62,032,837 B)
#     https://huggingface.co/PaddlePaddle/PP-OCRv6_medium_rec_onnx   (76,554,979 B)
#
# They are ~139 MB together, so they are NOT committed (see native/ocr/.gitignore). The script is
# idempotent: an existing file whose size matches is left alone. Nothing is written into the APK
# by this script - the model store in the app reads them from `native/ocr/models/` only when the
# native engine is wired up.

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$outDir = Join-Path $root 'native\ocr\models'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

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
    if ($size -ne $expectedSize) {
        throw "$file is $size bytes, expected $expectedSize - re-run the script"
    }
    Write-Host "ok: $file ($size bytes)"
    return $path
}

$det = Get-Model 'https://huggingface.co/PaddlePaddle/PP-OCRv6_medium_det_onnx/resolve/main/inference.onnx' 'ppocrv6_medium_det.onnx' 62032837
$rec = Get-Model 'https://huggingface.co/PaddlePaddle/PP-OCRv6_medium_rec_onnx/resolve/main/inference.onnx' 'ppocrv6_medium_rec.onnx' 76554979
$yml = Get-Model 'https://huggingface.co/PaddlePaddle/PP-OCRv6_medium_rec_onnx/resolve/main/inference.yml' 'ppocrv6_medium_rec_inference.yml' 150580

# The dictionary lives inside the recognition model's own inference.yml, under
# `PostProcess: character_dict:`. It is extracted verbatim, in order, because a re-ordered or
# re-coded dictionary would silently mis-map every recognised character.
$text = [System.IO.File]::ReadAllText($yml, [System.Text.Encoding]::UTF8)
$start = $text.IndexOf('character_dict:')
if ($start -lt 0) { throw 'character_dict not found in inference.yml' }
$tail = $text.Substring($start + 'character_dict:'.Length)
$end = $tail.IndexOf("`n  name:")
if ($end -lt 0) { $end = $tail.Length }
$block = $tail.Substring(0, $end)

$chars = New-Object System.Collections.Generic.List[string]
foreach ($line in ($block -split "`n")) {
    $t = $line.Trim()
    if (-not $t.StartsWith('- ')) { continue }
    $v = $t.Substring(2).Trim()
    if ($v.Length -ge 2 -and $v.StartsWith("'") -and $v.EndsWith("'")) {
        $v = $v.Substring(1, $v.Length - 2).Replace("''", "'")
    } elseif ($v.Length -ge 2 -and $v.StartsWith('"') -and $v.EndsWith('"')) {
        $v = $v.Substring(1, $v.Length - 2)
    }
    $chars.Add($v)
}
if ($chars.Count -lt 1000) { throw "dictionary looks truncated ($($chars.Count) entries)" }
$dict = Join-Path $outDir 'ppocrv6_dict.txt'
[System.IO.File]::WriteAllLines($dict, $chars, (New-Object System.Text.UTF8Encoding($false)))
Write-Host "ok: ppocrv6_dict.txt ($($chars.Count) characters)"

Write-Host ''
Write-Host 'Models are in:' $outDir
Write-Host 'Detection post-processing parameters (from PP-OCRv6_medium_det inference.yml):'
Write-Host '  DBPostProcess thresh=0.2 box_thresh=0.45 unclip_ratio=1.4 max_candidates=3000'
Write-Host 'Recognition preprocessing (from PP-OCRv6_medium_rec inference.yml):'
Write-Host '  RecResizeImg image_shape=[3,48,320], BGR, DeNormalize by 255, then (x/255-0.5)/0.5'
