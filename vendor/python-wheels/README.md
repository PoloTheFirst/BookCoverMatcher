# Source-built Pillow native dependencies

These four Android wheels replace incompatible native binaries used by Pillow 11.0.0. They retain Chaquopy package names/SONAMEs so the existing Python integration can use them. FreeType remains 2.9.1; libjpeg-turbo advances from 1.5.3 to 2.1.5 with the compatible JPEG v6b ABI. Both arm64-v8a and x86_64 are built for API 24 (the app requires API 26). No binary-header patching or architecture removal is used.

`manifest.json` records the exact staged wheels. These are generated third-party artifacts, not project-owned GPL code. Their embedded dist-info/licenses directories preserve upstream notices. FreeType is used under its FreeType License (FTL), including attribution: portions of this software are copyright The FreeType Project (www.freetype.org). All rights reserved. libjpeg-turbo keeps its IJG/BSD/zlib terms. This software is based in part on the work of the Independent JPEG Group. The optional turbojpeg wrapper/SIMD is not built. Original source code is not modified except the CMake target SONAME/output-name properties needed by Chaquopy.

## Rebuild on Windows

Requires Python 3.12+ (tar extraction filters), CMake and Android NDK r27d. The observed build used CMake 4.4.4 and Ninja 1.13.0. Source archive URLs and SHA-256 values are pinned in the GPL-3.0-or-later builder. It downloads and verifies sources in ignored build/native-python, not this directory. Compile/link flags set max/common page size to 16384. Debug symbols, build paths and archive timestamps may change wheel hashes between toolchains/runs: review a rebuilt artifact and update the manifest deliberately.

```powershell
python -m pip install --target build/native-python/build-tools ninja==1.13.0
python tools/build-python-native.py --ndk "$env:LOCALAPPDATA\Android\Sdk\ndk\android-ndk-r27d" --ninja build/native-python/build-tools/bin/ninja.exe
Copy-Item build/native-python/wheels/*.whl vendor/python-wheels/
python tools/verify-native-pages.py app/build/outputs/apk/debug/app-debug.apk
```

Gradle uses this directory as a pip find-links source and pins both dependency versions plus Pillow. A complete APK/AAB scan must include Chaquopy .imy archives; checking only lib/ misses these files. ELF/RELRO checks and a genuine 16 KB device inference/codec test are required after replacing wheels. Alignment work does not constitute an upstream vulnerability audit or a promise that old source versions have no CVEs.
