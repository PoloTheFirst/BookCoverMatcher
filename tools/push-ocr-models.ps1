# SPDX-License-Identifier: GPL-3.0-or-later

# Pushes the verified PP-OCRv6 models to a connected device, where the app installs them from
# external app storage into its private storage (hash-checked on the way in).
#
#     powershell -ExecutionPolicy Bypass -File tools/push-ocr-models.ps1
#     powershell -ExecutionPolicy Bypass -File tools/push-ocr-models.ps1 -Serial emulator-5554
#
# Source files: `-Source` (default %TEMP%\ppocrv6 with det.onnx / rec.onnx) and the dictionary that
# is committed in this repository. Both are hash-checked here, before anything is pushed, so a stale
# or wrong pair of models cannot reach the device unnoticed.
#
# The files are streamed through `adb shell run-as <package> cat > …`, i.e. written with the app's
# own UID into its **internal** files directory. `adb push` into
# /sdcard/Android/data/<package>/files would be owned by `shell`, and Android 11+ hides such
# shell-owned directories from the app, which then reports the models as missing. `run-as` requires a
# debuggable build — the only kind that offers the native engine while the migration is unfinished.

param(
    [string]$Serial = '',
    [string]$Source = (Join-Path $env:TEMP 'ppocrv6'),
    [string]$Package = 'com.bookcovermatcher'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

$expected = @{
    'det.onnx'         = 'EB13B44B25BB36F89528B68720AF8A61D9CF381176107F465DB1757B65D086E1'
    'rec.onnx'         = '9C09ABF0957F7968C7586464B7397B84AD2387A0497A351AF40E9ACC71B673BA'
    'ppocrv6_dict.txt' = 'B5F2BFE2BDD9448429E3E82B51C789775D9B42F2403D082B00662EB77E401C5D'
}

function Find-Adb {
    $candidates = New-Object System.Collections.Generic.List[string]
    $candidates.Add((Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'))
    foreach ($sdkRoot in @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME)) {
        if ($sdkRoot) { $candidates.Add((Join-Path $sdkRoot 'platform-tools\adb.exe')) }
    }
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path $candidate)) { return $candidate }
    }
    throw 'adb.exe not found - set ANDROID_SDK_ROOT or install the platform tools'
}

$adb = Find-Adb
$sourceFiles = @{
    'det.onnx'         = Join-Path $Source 'det.onnx'
    'rec.onnx'         = Join-Path $Source 'rec.onnx'
    'ppocrv6_dict.txt' = Join-Path $root 'native\ocr\assets\ppocrv6_dict.txt'
}

Write-Host 'Verifying the local files before pushing:'
foreach ($name in $sourceFiles.Keys) {
    $path = $sourceFiles[$name]
    if (-not (Test-Path $path)) { throw "missing $path - run tools/fetch-ppocrv6-models.ps1 first" }
    $sha = (Get-FileHash $path -Algorithm SHA256).Hash
    $size = (Get-Item $path).Length
    if ($sha -ne $expected[$name]) {
        throw "$name does not match the pinned SHA-256`n  got  $sha`n  want $($expected[$name])"
    }
    Write-Host ("  {0,-16} {1,12:N0} bytes  {2}" -f $name, $size, $sha.Substring(0, 16) + '…')
}

$adbArgs = @()
if ($Serial) { $adbArgs += @('-s', $Serial) }

# adb writes its progress to stderr, and Windows PowerShell turns a native command's stderr into a
# terminating error while $ErrorActionPreference is 'Stop'. Every adb call below is therefore judged
# by its exit code instead.
$ErrorActionPreference = 'Continue'

$deviceList = (& $adb @adbArgs get-state 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0) { throw "no usable device ($deviceList). Start an emulator or plug in a phone." }
Write-Host "`nDevice state: $deviceList"

$destination = "files/ocr-import/ppocrv6"
$staging = "/data/local/tmp/ppocrv6"

# `adb push` writes as `shell`; Android hides such directories from the app when they are the app's
# external files dir, and `run-as` cannot read /data/local/tmp directly (it is not traversable by the
# app's UID). So: push into a staging directory, make it world-readable, and copy it with the app's
# own UID. Every step is judged by its exit code.
$output = (& $adb @adbArgs shell "chmod 755 /data/local/tmp; mkdir -p $staging" 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0) { throw "could not create $staging on the device: $output" }

foreach ($name in $sourceFiles.Keys) {
    $local = $sourceFiles[$name]
    $output = (& $adb @adbArgs push $local "$staging/$name" 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne 0) { throw "adb push failed for $name`: $output" }
}

$output = (& $adb @adbArgs shell "chmod -R a+rX $staging" 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0) { throw "chmod failed: $output" }

$output = (& $adb @adbArgs shell "run-as $Package mkdir -p $destination" 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0) { throw "could not create $destination on the device: $output" }

foreach ($name in $sourceFiles.Keys) {
    $output = (& $adb @adbArgs shell "run-as $Package cp $staging/$name $destination/$name" 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne 0) { throw "copying $name into the app failed: $output" }
    Write-Host ("  installed {0}" -f $name)
}
(& $adb @adbArgs shell "rm -rf $staging" 2>&1 | Out-Null)

$listing = (& $adb @adbArgs shell "run-as $Package ls -l $destination" 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0) { throw "could not list $destination`: $listing" }
Write-Host "`nOn-device files (the app re-verifies each SHA-256 when it installs them):"
$listing -split "`n" | ForEach-Object { Write-Host "  $_" }

Write-Host "`nPushed to $destination"
Write-Host 'The app installs (and re-verifies) them on the next native OCR call, or the instrumentation'
Write-Host 'test does it directly:'
Write-Host '  .\gradlew.bat connectedDebugAndroidTest --tests "com.bookcovermatcher.ocr.*"'
