# Version naming and codes

## Owner decisions

The owner chooses the full prompt filename and target version. Do not invent a next patch, minor, dev, RC, or crash-fix number. An instruction to append/increment the previous number authorizes that specific derivation. Otherwise obtain the owner's chosen filename before creating a version-specific prompt.

The owner also chooses when to execute it. Creating a prompt and a report skeleton does not authorize implementation. See [WORKFLOW.md](WORKFLOW.md).

## File pairing

For a chosen stem `S`, create `historical_prompt/S.md` as the prompt and `historical_prompt/S-ag.md` as its expected agent-report structure. Finalize the report after authorized execution.

| Kind | Prompt example | Report example | Distribution intent |
| --- | --- | --- | --- |
| Standard release | `v2.6.0.md` | `v2.6.0-ag.md` | Publish verified changes to the repository under the approved release scope |
| Internal RC round | `v2.6.0-rc.1.md` | `v2.6.0-rc.1-ag.md` | Undisclosed testing code only; never a published release |
| Development release | `v2.7.0-dev.1.md` | `v2.7.0-dev.1-ag.md` | Publish verified development changes under the approved release scope; feature may be unstable |
| Focused crash fix | `v2.6.0-crash-fix-1.md` | `v2.6.0-crash-fix-1-ag.md` | Inherit the approved parent task's scope; no standalone release implied |
| Development crash fix | `v2.7.0-dev.1-crash-fix-1.md` | `v2.7.0-dev.1-crash-fix-1-ag.md` | Same focused workflow on a development version |

Use the established dotted form `-dev.N` and `-rc.N`. The owner's prose `dev-<number>` denotes that development sequence; the supplied examples and repository use `-dev.1`. Do not silently normalize an explicitly chosen filename. Historical names such as `-stable-1`, `-append`, and unnumbered `-crash-fix` are not authority to invent new names.

## Numeric versionCode

`versionCode` is the Android integer; `versionName` is the version string. Current baseline at guideline preparation: **223 / 2.6.0** in `app/build.gradle.kts`. Reinspect the build file before future work; do not hardcode this baseline into later prompts.

- A standard release normally increments the preceding code by one when no RC already reserved its code.
- The first RC for a target stable version increments the preceding code by one. Every subsequent RC for that target and the final stable release reuse that reserved code. Finalizing stable must not increment it again.
- RCs are internal testing iterations, never GitHub Releases, public prereleases, or public release tags. Do not merge or push RC-only testing changes into public release branches. Record any approved private testing branch separately.
- Each published development version increments the preceding code by one, including each successive `dev.N`. Dev releases consume codes and affect the next stable release's allocation.
- `crash-fix-N` identifies focused attempts to resolve a serious problem without introducing a separate RC, patch, or dev release. Retain the parent application's version code/name unless the owner explicitly instructs otherwise; the prompt suffix alone does not allocate a code or change APK metadata.
- RCs are rarely used for patch releases, but follow the same allocation rule when the owner chooses one.

Independent examples (each begins at 223 / 2.6.0):

| Sequence | Codes |
| --- | --- |
| `2.6.0` → `2.6.1` → `2.7.0`, no intermediate test versions | 223 → 224 → 225 |
| `2.7.0-rc.1` → `2.7.0-rc.2` → `2.7.0` | 224 → 224 → 224 |
| `2.7.0-dev.1` → `2.7.0-dev.2` → `2.7.0` | 224 → 225 → 226 |
| `2.7.0-dev.1` → `2.7.0-rc.1` → `2.7.0-rc.2` → `2.7.0` | 224 → 225 → 225 → 225 |

The owner's illustrative RC family `2.6.0-rc.1` → `2.6.0-rc.2` → `2.6.0` shares **223** throughout. This explains allocation before that stable release; it does not authorize creating RCs retroactively for the already released 2.6.0.

Record the approved filename, parent version, allocation reason, intended APK versionName, and versionCode separately. Internal RC prompt identity must remain visible in reports even if testing APK metadata uses the target stable versionName; state that choice explicitly in the reviewable prompt. Stable/dev changelog headings and final metadata must match the actual artifact, including the dev suffix. Verify configured and packaged values when a build is executed; never claim artifact verification from configuration alone.
