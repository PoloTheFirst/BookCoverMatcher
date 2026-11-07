# Checks that a built APK really contains the native OCR library, for which ABIs, and what else
# was packaged with it.
#
#     powershell -ExecutionPolicy Bypass -File tools/verify-ocr-apk.ps1
#     powershell -ExecutionPolicy Bypass -File tools/verify-ocr-apk.ps1 -Apk app\build\outputs\apk\debug\app-debug.apk
#
# An `.so` sitting in a build directory proves nothing; this reads the archive itself.

param(
    [string]$Apk = ''
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

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
try {
    Write-Host "APK: $Apk ($([math]::Round((Get-Item $Apk).Length / 1MB, 1)) MB)"

    $native = $zip.Entries | Where-Object { $_.FullName -match '^lib/[^/]+/.*\.so$' } | Sort-Object FullName
    Write-Host "`nNative libraries:"
    foreach ($entry in $native) {
        Write-Host ("  {0,-48} {1,12:N0} bytes" -f $entry.FullName, $entry.Length)
    }

    $ocr = $native | Where-Object { $_.FullName -like '*/libbc_ocr.so' }
    if (-not $ocr) {
        Write-Warning 'libbc_ocr.so is NOT packaged - build it with tools/build-native-ocr.ps1 and rebuild'
    } else {
        $protected = @('arm64-v8a', 'x86_64')   # keep in step with ndk.abiFilters in app/build.gradle.kts
        $present = $ocr | ForEach-Object { ($_.FullName -split '/')[1] }
        foreach ($abi in $protected) {
            if ($present -contains $abi) {
                Write-Host "  $abi : libbc_ocr.so present"
            } else {
                Write-Warning "$abi : libbc_ocr.so MISSING (supported ABI without the native OCR library)"
            }
        }
    }

    $runtimes = $native | Where-Object { $_.FullName -like '*/libonnxruntime.so' }
    Write-Host "`nONNX Runtime copies: $($runtimes.Count) (expected one per packaged ABI, from the Maven dependency)"
    foreach ($entry in $runtimes) { Write-Host ("  {0}" -f $entry.FullName) }

    $obsolete = $native | Where-Object { $_.FullName -match '/(libbc_ocr|libonnxruntime)\.so$' } |
        Group-Object { ($_.FullName -split '/')[1] } |
        Where-Object { ($_.Group | Where-Object { $_.FullName -like '*libbc_ocr.so' }).Count -gt 1 }
    if ($obsolete) { Write-Warning 'duplicate libbc_ocr.so entries in one ABI directory' }

    $models = $zip.Entries | Where-Object { $_.FullName -match '^assets/models/' }
    Write-Host "`nBundled models (assets/models):"
    foreach ($entry in $models) { Write-Host ("  {0,-48} {1,12:N0} bytes" -f $entry.FullName, $entry.Length) }
    if (($models | Where-Object { $_.FullName -notmatch 'openclip_image' }).Count -gt 0) {
        Write-Warning 'unexpected model files are bundled - the PP-OCRv6 weights must not be packaged'
    }
} finally {
    $zip.Dispose()
}
