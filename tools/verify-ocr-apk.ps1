# SPDX-License-Identifier: GPL-3.0-or-later

# Verifies that a built APK really contains everything PaddleOCR needs, by reading the archive
# itself — not the source asset directory.
#
#     powershell -ExecutionPolicy Bypass -File tools/verify-ocr-apk.ps1
#     powershell -ExecutionPolicy Bypass -File tools/verify-ocr-apk.ps1 -Apk <path-to.apk>
#
# Checks, for every APK:
#   * assets/ocr/ppocrv6/{det.onnx, rec.onnx, ppocrv6_dict.txt} exist and their extracted bytes match
#     the pinned SHA-256 (the same values the app verifies on device);
#   * lib/<abi>/libbc_ocr.so exists for every ABI the app ships;
#   * lib/<abi>/libonnxruntime.so exists exactly once per ABI;
#   * no required asset was excluded by packaging.
# It also reports APK size, total model-asset size, per-ABI native sizes, and whether the models are
# stored uncompressed (they are, so extraction from the APK is a straight copy).
#
# Exit code 0 = every check passed, 1 = something is missing or wrong.

param(
    [string]$Apk = ''
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

# Keep in step with app/build.gradle.kts (preparePaddleOcrAssets) and PpocrModels.PINNED_SHA256.
$expected = [ordered]@{
    'det.onnx'         = @{ sha256 = 'eb13b44b25bb36f89528b68720af8a61d9cf381176107f465db1757b65d086e1'; size = 62032837 }
    'rec.onnx'         = @{ sha256 = '9c09abf0957f7968c7586464b7397b84ad2387a0497a351af40e9acc71b673ba'; size = 76554979 }
    'ppocrv6_dict.txt' = @{ sha256 = 'b5f2bfe2bdd9448429e3e82b51c789775d9b42f2403d082b00662eb77e401c5d'; size = 74947 }
}
$supportedAbis = @('arm64-v8a', 'x86_64')   # keep in step with ndk.abiFilters

if (-not $Apk) {
    $candidates = @(
        (Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk'),
        (Join-Path $root 'app\build\outputs\apk\release\app-release.apk')
    )
    $Apk = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
    if (-not $Apk) { throw 'no APK found - run .\gradlew.bat assembleDebug first' }
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($Apk)
$failures = New-Object System.Collections.Generic.List[string]
$modelBytes = 0L

function Get-EntrySha256($entry) {
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $stream = $entry.Open()
        try { return ($sha.ComputeHash($stream) | ForEach-Object { $_.ToString('x2') }) -join '' }
        finally { $stream.Dispose() }
    } finally { $sha.Dispose() }
}

try {
    $apkSize = (Get-Item $Apk).Length
    Write-Host ("APK: {0}" -f $Apk)
    Write-Host ("APK size: {0:N0} bytes ({1:N1} MB)" -f $apkSize, ($apkSize / 1MB))

    Write-Host "`n== PP-OCRv6 model assets =="
    Write-Host ("{0,-20} {1,-12} {2,-20} {3,-9} {4}" -f 'Asset', 'Size', 'SHA-256 (first 16)', 'Pack', 'Result')
    foreach ($name in $expected.Keys) {
        $entryName = "assets/ocr/ppocrv6/$name"
        $entry = $zip.Entries | Where-Object { $_.FullName -eq $entryName } | Select-Object -First 1
        if (-not $entry) {
            Write-Host ("{0,-20} {1,-12} {2,-20} {3,-9} FAIL (missing from APK)" -f $name, '-', '-', '-')
            $failures.Add("$name is not packaged at $entryName")
            continue
        }
        $modelBytes += $entry.Length
        $packing = if ($entry.CompressedLength -eq $entry.Length) { 'stored' } else { 'deflated' }
        $sha = Get-EntrySha256 $entry
        $want = $expected[$name]
        $ok = ($sha -eq $want.sha256) -and ($entry.Length -eq $want.size)
        Write-Host ("{0,-20} {1,-12:N0} {2,-20} {3,-9} {4}" -f $name, $entry.Length, ($sha.Substring(0, 16) + '...'), $packing, ($(if ($ok) { 'PASS' } else { 'FAIL' })))
        if (-not $ok) {
            $failures.Add("$name has sha256 $sha / $($entry.Length) bytes, expected $($want.sha256) / $($want.size)")
        }
    }
    Write-Host ("Total model assets: {0:N0} bytes ({1:N1} MB)" -f $modelBytes, ($modelBytes / 1MB))

    Write-Host "`n== Native libraries =="
    $native = $zip.Entries | Where-Object { $_.FullName -match '^lib/[^/]+/.*\.so$' } | Sort-Object FullName
    foreach ($entry in $native) {
        Write-Host ("  {0,-52} {1,12:N0} bytes" -f $entry.FullName, $entry.Length)
    }
    foreach ($abi in $supportedAbis) {
        $ocr = @($native | Where-Object { $_.FullName -eq "lib/$abi/libbc_ocr.so" })
        if ($ocr.Count -eq 0) {
            $failures.Add("lib/$abi/libbc_ocr.so is missing")
        } elseif ($ocr.Count -gt 1) {
            $failures.Add("lib/$abi/libbc_ocr.so appears $($ocr.Count) times")
        } else {
            Write-Host "  PASS  lib/$abi/libbc_ocr.so"
        }
        $ort = @($native | Where-Object { $_.FullName -eq "lib/$abi/libonnxruntime.so" })
        if ($ort.Count -eq 0) {
            $failures.Add("lib/$abi/libonnxruntime.so is missing")
        } elseif ($ort.Count -ne 1) {
            $failures.Add("lib/$abi/libonnxruntime.so appears $($ort.Count) times")
        } else {
            Write-Host "  PASS  lib/$abi/libonnxruntime.so (exactly one copy)"
        }
    }

    $stray = $zip.Entries | Where-Object { $_.FullName -match '^assets/ocr/.*\.part$' }
    foreach ($entry in $stray) { $failures.Add("a partial download was packaged: $($entry.FullName)") }
} finally {
    $zip.Dispose()
}

if ($failures.Count -gt 0) {
    Write-Host "`nVERIFICATION FAILED:"
    foreach ($failure in $failures) { Write-Host "  - $failure" }
    exit 1
}
Write-Host "`nVERIFICATION PASSED: every APK requirement is present and hash-correct."
exit 0
