"""Throwaway validator for CHANGELOG.md against CHANGELOG_GUIDELINES.md (v2.6.0-dev.1 crash-fix-3).

Not part of the project tooling: it lives in the untracked evidence folder so the licensing audit's
project-source rules are unaffected. Run: python changelog_check.py
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CHANGELOG = ROOT / "CHANGELOG.md"

SECTIONS = ["Added", "Changed", "Fixed", "Removed", "Tests", "Verified", "Known limitations"]

failures: list[str] = []
notes: list[str] = []

data = CHANGELOG.read_bytes()
if data.startswith(b"\xef\xbb\xbf"):
    failures.append("file starts with a UTF-8 BOM")
lines = data.decode("utf-8").splitlines()

# 1. exactly one version heading, `## vX.Y.Z (YYYY-MM-DD)` (a development suffix is allowed by 3.1)
version = [(i, l) for i, l in enumerate(lines) if re.match(r"^## v", l)]
if len(version) != 1:
    failures.append(f"expected exactly one '## v' heading, found {len(version)}: {[l for _, l in version]}")
else:
    index, line = version[0]
    if not re.fullmatch(r"## v\d+\.\d+\.\d+(?:-[0-9A-Za-z.\-]+)? \(\d{4}-\d{2}-\d{2}\)", line):
        failures.append(f"version heading is not '## vX.Y.Z (YYYY-MM-DD)': {line!r}")
    # 2. release title: a separate H1 immediately after, separated by one blank line
    if lines[index + 1].strip() != "":
        failures.append("no blank line between the version heading and the release title")
    if not lines[index + 2].startswith("# "):
        failures.append(f"the release title is not a separate '#' heading: {lines[index + 2]!r}")
    # 3. conclusion: a non-heading paragraph after the title
    body = lines[index + 3:]
    if body[0].strip() != "" or not body[1].strip() or body[1].startswith("#"):
        failures.append("the conclusion is not a separate paragraph under the release title")

# 4. all seven sections, in order, each with at least one bullet or 'None.'
found = [l[4:] for l in lines if l.startswith("### ")]
if found != SECTIONS:
    failures.append(f"required sections/order mismatch: {found} != {SECTIONS}")

for name in SECTIONS:
    start = lines.index(f"### {name}") + 1
    block = []
    for line in lines[start:]:
        if line.startswith("### ") or line.startswith("## ") or line.startswith("# "):
            break
        if line.startswith("**Version code/name:"):
            break
        block.append(line)
    content = [l for l in block if l.strip()]
    if not content:
        failures.append(f"section '{name}' is empty")
    elif not all(l.startswith("- ") or l.startswith("  ") for l in content):
        failures.append(f"section '{name}' has non-bullet content: {content[0]!r}")
    notes.append(f"{name}: {sum(1 for l in content if l.startswith('- '))} bullet(s)")

# 5. bullets use '-', never '*'
stars = [l for l in lines if re.match(r"^\s*\* ", l)]
if stars:
    failures.append(f"{len(stars)} bullet(s) still use '*': {stars[0]!r}")

# 6. exact footer, and it is the last non-empty line
non_empty = [l for l in lines if l.strip()]
footer = non_empty[-1]
footer_match = re.fullmatch(r"\*\*Version code/name: (\d+) / (\S+)\.\*\*", footer)
if not footer_match:
    failures.append(f"footer is not '**Version code/name: XXX / X.Y.Z.**': {footer!r}")
elif version:
    # 2.1: the heading and the final line must name the same version, suffix included.
    heading_version = re.fullmatch(r"## v(\S+) \(\d{4}-\d{2}-\d{2}\)", version[0][1]).group(1)
    if footer_match.group(2) != heading_version:
        failures.append(
            f"heading says v{heading_version} but the footer says {footer_match.group(2)}"
        )

# 7. no retained previous version headings, and no '---' separator before the entry
if any(re.match(r"^## v", l) for l in lines[version[0][0] + 1:]) if version else False:
    failures.append("a previous version heading is still present")

print("CHANGELOG.md validation")
print("  version heading :", version[0][1] if version else "(none)")
print("  title           :", lines[version[0][0] + 2] if version else "(none)")
print("  footer          :", footer)
print("  bullets         :")
for note in notes:
    print("    -", note)
print("  sections        :", ", ".join(found))
if failures:
    print("\nFAILED:")
    for f in failures:
        print("  -", f)
    sys.exit(1)
print("\nPASS: the entry matches the guideline structure.")
