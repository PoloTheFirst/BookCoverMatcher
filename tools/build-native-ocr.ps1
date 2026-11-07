# SPDX-License-Identifier: GPL-3.0-or-later

# Builds `libbc_ocr.so` for every ABI the app ships and stages it where Gradle picks it up.
#
#     powershell -ExecutionPolicy Bypass -File tools/build-native-ocr.ps1
#     .\gradlew.bat assembleDebug
#
# Why a script and not a Gradle task: the crate needs a Rust toolchain and the NDK, which CI and
# most Android builds do not have. Requiring them on every `assembleDebug` would break ordinary
# builds; running this once stages `app/src/main/jniLibs/<abi>/libbc_ocr.so`, which Gradle packages
# through its normal `jniLibs` mechanism. The .so files are git-ignored: they are build outputs.
#
# ABI policy comes from `app/build.gradle.kts` (`ndk.abiFilters`): arm64-v8a for phones, x86_64 for
# the emulator. Adding an ABI there means adding it here too — nothing is silently skipped.

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$crate = Join-Path $root 'native\ocr'
$jniLibs = Join-Path $root 'app\src\main\jniLibs'

# Minimum Android API the app supports; must match `minSdk` in app/build.gradle.kts.
$apiLevel = '26'

$targets = [ordered]@{
    'arm64-v8a' = 'aarch64-linux-android'
    'x86_64'    = 'x86_64-linux-android'
}

function Find-Ndk {
    if ($env:BC_OCR_NDK) { return $env:BC_OCR_NDK }
    $sdk = $env:ANDROID_SDK_ROOT
    if (-not $sdk) { $sdk = $env:ANDROID_HOME }
    if (-not $sdk) {
        $local = Join-Path $root 'local.properties'
        if (Test-Path $local) {
            $line = Get-Content $local | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
            if ($line) {
                # Java properties escape ':' and '\' - "C\:\\Users\\..." means C:\Users\...
                $raw = $line.Substring($line.IndexOf('=') + 1)
                $sdk = $raw.Replace('\:', ':').Replace('\\', '\')
            }
        }
    }
    # The parsed value is only a hint; LOCALAPPDATA is where the Android SDK manager puts it.
    if (-not $sdk -or -not (Test-Path (Join-Path $sdk 'ndk'))) {
        $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
    }
    $ndkRoot = Join-Path $sdk 'ndk'
    if (-not (Test-Path $ndkRoot)) { throw "no NDK under $ndkRoot - install one or set BC_OCR_NDK" }
    # Newest installed NDK, by directory name (r27d > r26b for the names Android ships).
    return (Get-ChildItem $ndkRoot -Directory | Sort-Object Name -Descending | Select-Object -First 1).FullName
}

$ndk = Find-Ndk
$toolchain = Join-Path $ndk 'toolchains\llvm\prebuilt\windows-x86_64\bin'
$clang = Join-Path $toolchain 'clang.exe'
$readelf = Join-Path $toolchain 'llvm-readelf.exe'
if (-not (Test-Path $clang)) { throw "no clang in $toolchain - is this a Windows NDK?" }
Write-Host "NDK: $ndk"

$savedRustFlags = $env:RUSTFLAGS
try {
    foreach ($abi in $targets.Keys) {
        $triple = $targets[$abi]
        $linkerVar = 'CARGO_TARGET_' + ($triple -replace '-', '_').ToUpperInvariant() + '_LINKER'
        Set-Item -Path "env:$linkerVar" -Value $clang
        # clang understands a triple with the API level appended; that is what sets minSdk for the .so.
        $env:RUSTFLAGS = "-C link-arg=--target=$triple$apiLevel"

        Write-Host "`n==> cargo build --release --target $triple ($abi, API $apiLevel)"
        Push-Location $crate
        try {
            & cargo build --release --target $triple
            if ($LASTEXITCODE -ne 0) { throw "cargo build failed for $triple" }
        } finally {
            Pop-Location
        }

        $so = Join-Path $crate "target\$triple\release\libbc_ocr.so"
        if (-not (Test-Path $so)) { throw "expected $so" }
        $destination = Join-Path $jniLibs "$abi\libbc_ocr.so"
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $destination) | Out-Null
        Copy-Item $so $destination -Force
        $size = (Get-Item $destination).Length
        Write-Host ("staged {0} ({1:N0} bytes)" -f $destination, $size)

        if (Test-Path $readelf) {
            $machine = (& $readelf -h $destination | Select-String 'Machine:').Line
            Write-Host ("ELF: {0}" -f $machine.Trim())
        }
    }
} finally {
    $env:RUSTFLAGS = $savedRustFlags
}

Write-Host "`nStaged ABI directories:"
Get-ChildItem $jniLibs -Directory | ForEach-Object {
    $file = Join-Path $_.FullName 'libbc_ocr.so'
    if (Test-Path $file) {
        Write-Host ("  {0}: {1:N0} bytes" -f $_.Name, (Get-Item $file).Length)
    } else {
        Write-Host ("  {0}: missing" -f $_.Name)
    }
}
Write-Host "`nNext: .\gradlew.bat assembleDebug, then verify with tools/verify-ocr-apk.ps1"
