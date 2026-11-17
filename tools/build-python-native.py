#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Rebuild Pillow's two Chaquopy native dependencies with 16 KB alignment on Windows.

Requires CMake, Ninja and an Android NDK. Outputs wheels to build/native-python/wheels.
FreeType retains its original version; libjpeg-turbo 2.1.5 retains the JPEG v6b ABI.
No binary header patching.
"""
import argparse
import base64
import csv
import hashlib
import io
import shutil
import subprocess
import tarfile
import urllib.request
import zipfile
from pathlib import Path

SOURCES = {
    "freetype": ("2.9.1", "freetype-VER-2-9-1", "https://codeload.github.com/freetype/freetype/tar.gz/refs/tags/VER-2-9-1", "b6c3ead5459df6b832d16a7025fc801eee4dcd66d4e732bdc13526d0dc0b0845"),
    "libjpeg": ("2.1.5", "libjpeg-turbo-2.1.5", "https://codeload.github.com/libjpeg-turbo/libjpeg-turbo/tar.gz/refs/tags/2.1.5", "254f3642b04e309fee775123133c6464181addc150499561020312ec61c1bf7c"),
}


def run(*args):
    subprocess.run([str(arg) for arg in args], check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ndk", type=Path, required=True)
    parser.add_argument("--ninja", required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1] / "build/native-python"
    root.mkdir(parents=True, exist_ok=True)
    output = root / "wheels"
    output.mkdir(exist_ok=True)
    for package, (version, folder, url, digest) in SOURCES.items():
        archive = root / (package + "-" + version + "-source.tar.gz")
        if not archive.exists():
            urllib.request.urlretrieve(url, archive)
        if hashlib.sha256(archive.read_bytes()).hexdigest() != digest:
            raise ValueError("Source hash mismatch: " + package)
        source = root / (folder + "-16k")
        if not source.exists():
            staging = root / (package + "-source")
            with tarfile.open(archive) as tar:
                tar.extractall(staging, filter="data")
            shutil.copytree(staging / folder, source)
            # Preserve the SONAME expected by the existing Pillow Android wheel.
            target = "freetype" if package == "freetype" else "jpeg"
            cmake = source / "CMakeLists.txt"
            with cmake.open("a", encoding="utf-8") as file:
                file.write(f'\nset_target_properties({target} PROPERTIES VERSION "" SOVERSION "" OUTPUT_NAME "{target if package == "freetype" else "jpeg_chaquopy"}")\n')
        for abi in ("arm64-v8a", "x86_64"):
            build = root / (package + "-" + version + "-" + abi)
            options = ["-DBUILD_SHARED_LIBS=ON", "-DCMAKE_DISABLE_FIND_PACKAGE_HarfBuzz=TRUE",
                       "-DCMAKE_DISABLE_FIND_PACKAGE_PNG=TRUE", "-DCMAKE_DISABLE_FIND_PACKAGE_BZip2=TRUE"] if package == "freetype" else [
                           "-DWITH_SIMD=OFF", "-DWITH_TURBOJPEG=OFF", "-DENABLE_STATIC=OFF"]
            run("cmake", "-S", source, "-B", build, "-G", "Ninja",
                "-DCMAKE_POLICY_VERSION_MINIMUM=3.5", "-DCMAKE_BUILD_TYPE=Release",
                "-DCMAKE_MAKE_PROGRAM=" + str(Path(args.ninja).resolve()),
                "-DCMAKE_TOOLCHAIN_FILE=" + str(args.ndk.resolve() / "build/cmake/android.toolchain.cmake"),
                "-DANDROID_ABI=" + abi, "-DANDROID_PLATFORM=android-24",
                "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384,-z,common-page-size=16384", *options)
            target = "freetype" if package == "freetype" else "jpeg"
            run("cmake", "--build", build, "--target", target, "--parallel", "4")
            filename = "libfreetype.so" if package == "freetype" else "libjpeg_chaquopy.so"
            library = next(build.rglob(filename))
            run("python", Path(__file__).with_name("verify-native-pages.py"), library)
            name = "chaquopy_" + package
            info = f"{name}-{version}.dist-info"
            tag = "py3-none-android_24_" + abi.replace("-", "_")
            entries = {"chaquopy/lib/" + filename: library.read_bytes(),
                       info + "/METADATA": f"Metadata-Version: 2.1\nName: {name.replace('_', '-')}\nVersion: {version}\nSummary: Source rebuild for Android 16 KB page alignment\n".encode(),
                       info + "/WHEEL": f"Wheel-Version: 1.0\nGenerator: BookCoverMatcher source rebuild\nRoot-Is-Purelib: false\nBuild: 3\nTag: {tag}\n".encode(),
                       info + "/SOURCE.txt": f"{url}\nSHA-256: {digest}\nBuilt with tools/build-python-native.py; SONAME adjusted for Chaquopy.\n".encode()}
            for notice in (["docs/FTL.TXT", "docs/GPLv2.TXT", "LICENSE.TXT"] if package == "freetype" else ["LICENSE.md", "README.ijg", "README.md"]):
                path = source / notice
                if path.exists(): entries[info + "/licenses/" + path.name] = path.read_bytes()
            records = io.StringIO(newline="")
            writer = csv.writer(records)
            for path, data in entries.items():
                writer.writerow([path, "sha256=" + base64.urlsafe_b64encode(hashlib.sha256(data).digest()).decode().rstrip("="), len(data)])
            writer.writerow([info + "/RECORD", "", ""])
            entries[info + "/RECORD"] = records.getvalue().encode()
            wheel = output / f"{name}-{version}-3-{tag}.whl"
            with zipfile.ZipFile(wheel, "w", zipfile.ZIP_DEFLATED) as zip_file:
                for path, data in entries.items(): zip_file.writestr(path, data)
            print("Built", wheel, flush=True)


if __name__ == "__main__":
    main()
