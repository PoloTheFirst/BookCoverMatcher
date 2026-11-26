# SPDX-License-Identifier: GPL-3.0-or-later
"""Prepare one BCM historical rebuild from its verified original release APK and tagged source."""
import argparse
import hashlib
import json
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import zipfile
from pathlib import Path, PurePosixPath

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(ROOT / "tools"))
from android_version import resolve_version


def sha256(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def export_source(record, target):
    reference = "refs/tags/" + record["sourceTag"] + "^{commit}"
    git = ["git", "-C", ROOT]
    tag_commit = subprocess.check_output([*git, "rev-parse", reference], text=True).strip()
    commit = record["sourceCommit"]
    if tag_commit != commit:
        parents = subprocess.check_output([*git, "show", "-s", "--format=%P", tag_commit], text=True).split()
        changes = subprocess.check_output([
            *git, "diff-tree", "--no-commit-id", "--no-renames", "-r", "--name-status", "-z", commit, tag_commit,
        ])
        if parents != [commit] or changes != b"M\0CHANGELOG.md\0":
            raise ValueError("Expected the pinned source commit or its direct CHANGELOG.md-only correction.")
    if record.get("keepTaggedCommit"):
        # The modern build embedded the checked-out revision in AGP's VCS metadata.
        subprocess.run(["git", "-C", target, "init", "--quiet"], check=True)
        subprocess.run(["git", "-C", target, "fetch", "--quiet", "--depth=1", "--no-tags", ROOT, commit], check=True)
        subprocess.run(["git", "-C", target, "checkout", "--quiet", "--detach", "FETCH_HEAD"], check=True)
        return
    with subprocess.Popen(["git", "-C", ROOT, "archive", commit], stdout=subprocess.PIPE) as process:
        with tarfile.open(fileobj=process.stdout, mode="r|") as archive:
            archive.extractall(target, filter="data")
        if process.wait():
            raise ValueError("Git could not export the full source tree.")


def set_version_code(record, target):
    code = resolve_version(record["releaseTag"])[1]
    if code != record["code"]:
        raise ValueError("Archive index and version calculation disagree.")
    gradle = target / "app/build.gradle.kts"
    text, changes = re.subn(
        r"(?m)^([ \t]*versionCode[ \t]*=[ \t]*)" + str(record["originalCode"]) + r"\b",
        lambda match: match[1] + str(code),
        gradle.read_text(encoding="utf-8"),
    )
    if changes != 1:
        raise ValueError("Expected one matching Android versionCode declaration.")
    gradle.write_bytes(text.encode("utf-8"))
    for patch in record.get("sourceCorrections", []):
        subprocess.run(
            ["git", "apply", "--unidiff-zero", "--whitespace=nowarn", HERE / patch],
            cwd=target, check=True,
        )
    if sha256(gradle) != record["gradleSha256"]:
        raise ValueError("The reconstructed app/build.gradle.kts differs from the build input.")


def copy_apk_inputs(apk, target):
    for entry in apk.infolist():
        name = entry.filename
        path = PurePosixPath(name)
        if entry.is_dir():
            continue
        if name.startswith(("assets/models/", "assets/ocr/")):
            dest = target / "app/src/main" / name
        elif re.fullmatch(r"lib/(?:arm64-v8a|x86_64)/libbc_ocr\.so", name):
            dest = target / "app/src/main/jniLibs" / path.parent.name / path.name
        else:
            continue
        if path.as_posix() != name or ".." in path.parts or "\\" in name or not dest.resolve().is_relative_to(target.resolve()):
            raise ValueError("Unexpected APK asset path.")
        dest.parent.mkdir(parents=True, exist_ok=True)
        with apk.open(entry) as src, dest.open("wb") as out:
            shutil.copyfileobj(src, out)


def restore_notice_bytes(record, apk, target):
    for filename, member in record.get("licenseInputs", {}).items():
        # The archived Windows build copied a CRLF notice into its APK.
        source = target / filename
        original = apk.read(member)
        if source.read_bytes().replace(b"\r\n", b"\n") != original.replace(b"\r\n", b"\n"):
            raise ValueError("Packaged license text differs from the recorded source.")
        source.write_bytes(original)


def public_client_settings(apk, target):
    gradle = (target / "app/build.gradle.kts").read_text(encoding="utf-8")
    if "azureEnvFile" not in gradle:
        return
    # AAPT can shorten resource paths. Locate the native-client registration by its JSON fields.
    configs = []
    for entry in apk.infolist():
        if entry.filename.startswith("res/") and entry.filename.endswith(".json") and entry.file_size < 32768:
            try:
                data = json.loads(apk.read(entry))
            except (UnicodeError, ValueError):
                continue
            if isinstance(data, dict) and {"client_id", "redirect_uri", "authorities"} <= data.keys():
                configs.append(data)
    if len(configs) != 1:
        raise ValueError("Expected one public Microsoft native-client registration in the original APK.")
    cfg = configs[0]
    values = {
        "Application_ID": cfg["client_id"],
        # This tagged build checks presence. Directory_ID is intentionally never packaged or used.
        "Directory_ID": "00000000-0000-0000-0000-000000000000",
        "Redirect_URL": cfg["redirect_uri"],
    }
    if "GOOGLE_OAUTH_CLIENT_ID" in gradle:
        android_clients = set()
        for name in apk.namelist():
            if re.fullmatch(r"classes[0-9]*\.dex", name):
                android_clients.update(re.findall(rb"[0-9]+-[A-Za-z0-9_-]+\.apps\.googleusercontent\.com", apk.read(name)))
        if len(android_clients) > 1:
            raise ValueError("Found multiple public Google client IDs. Check the tagged registration.")
        if android_clients:
            values["GOOGLE_OAUTH_CLIENT_ID"] = android_clients.pop().decode("ascii")
    if any("\n" in value or "\r" in value for value in values.values()):
        raise ValueError("Invalid native client registration.")
    (target / ".env").write_text("".join(f"{key}={value}\n" for key, value in values.items()), encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("original_apk", type=Path)
    parser.add_argument("destination", type=Path, help="New directory whose parent already exists")
    args = parser.parse_args()
    dest = args.destination.absolute()
    if dest.exists() or dest.is_symlink() or not dest.parent.is_dir():
        parser.error("Select a new directory under an existing parent.")
    records = json.loads((HERE / "artifacts.json").read_text(encoding="utf-8"))
    fingerprint = sha256(args.original_apk)
    record = next((item for item in records if item["originalSha256"] == fingerprint), None)
    if not record:
        parser.error("APK SHA-256 does not match any original release in the archive index.")
    with tempfile.TemporaryDirectory(prefix=".bcm-rebuild-", dir=dest.parent) as tmpdir:
        stage = Path(tmpdir) / "source"
        stage.mkdir()
        export_source(record, stage)
        set_version_code(record, stage)
        with zipfile.ZipFile(args.original_apk) as apk:
            copy_apk_inputs(apk, stage)
            restore_notice_bytes(record, apk, stage)
            public_client_settings(apk, stage)
        if record.get("pythonLock"):
            lock = stage / ".archive-rebuild"
            lock.mkdir()
            shutil.copyfile(HERE / record["pythonLock"], lock / "constraints.txt")
            shutil.copyfile(HERE / "python.init.gradle", lock / "python.init.gradle")
        stage.rename(dest)
    print(f"Prepared {record['sourceTag']} ({record['code']}) in {dest}")
    command = ["bash", "gradlew"]
    if record.get("pythonLock"):
        command += ["-I", ".archive-rebuild/python.init.gradle"]
    print("From that directory run", " ".join(command + [":app:" + record["gradleTask"]]))


if __name__ == "__main__":
    main()
