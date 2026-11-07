# SPDX-License-Identifier: GPL-3.0-or-later

# Reproduces the accuracy comparison matrix: the Python reference under each preprocessing variant,
# against the generated fixtures. Development tool; writes into tools/ocr-reference/out/ (ignored).
#
#   powershell -ExecutionPolicy Bypass -File tools/ocr-reference/run_matrix.ps1
#
# Each row is scored by metrics.py, so the numbers in the report come from this script, not by hand.

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Push-Location $root
$env:PYTHONIOENCODING = 'utf-8'

$truth = 'tools/ocr-reference/out/fixtures/truth.json'
$variants = @(
    @{ name = 'ref-min-bgr'; limit = 'min'; order = 'bgr' },
    @{ name = 'ref-min-rgb'; limit = 'min'; order = 'rgb' },
    @{ name = 'ref-max-bgr'; limit = 'max'; order = 'bgr' },
    @{ name = 'ref-max-rgb'; limit = 'max'; order = 'rgb' }
)

foreach ($variant in $variants) {
    $dir = "tools/ocr-reference/out/results/$($variant.name)"
    New-Item -ItemType Directory -Force $dir | Out-Null
    Get-ChildItem 'tools/ocr-reference/out/fixtures/*.png' | ForEach-Object {
        $base = $_.BaseName
        python tools/ocr-reference/reference_paddle.py --image $_.FullName `
            --limit-type $variant.limit --det-order $variant.order --json "$dir/$base.json" *> $null
    }
    python tools/ocr-reference/metrics.py --truth $truth --results $dir --label $variant.name
    ''
}

Pop-Location
