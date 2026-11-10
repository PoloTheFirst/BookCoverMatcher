# Prompt and report templates

Replace placeholders using the owner's selected filename and reviewed scope. Keep both files in `historical_prompt/`. A report skeleton is an expected format, not execution evidence.

## Implementation prompt: `<chosen-stem>.md`

```markdown
# BookCoverMatcher <chosen-stem> — <task title>

Status: Draft for owner review; execution pending.
Prompt: historical_prompt/<chosen-stem>.md
Report: historical_prompt/<chosen-stem>-ag.md
Kind: Standard / internal RC / development / parent-version crash fix
Parent version and code: <verified baseline>
Target APK versionName / versionCode: <values and allocation rationale>
Git/publication scope: <branches, remotes, commits, pushes, tags, release/assets>

## 1. Objective and acceptance criteria

<Concrete user-visible outcomes and completion criteria.>

## 2. Preflight and historical reconciliation

<Relevant prompt/report pairs; branch, HEAD, working tree, implementation,
version metadata, prior blockers, evidence, and facts to reverify.>

## 3. Requirements and implementation constraints

<Numbered requirements; architecture, persistence, compatibility, UI,
offline/network, privacy, licensing, dependencies, and exclusions as applicable.>

## 4. Verification

<Exact PowerShell commands appropriate to the task; regression checks;
artifact inspection; ADB reproduction and visual checks; evidence destinations;
required environments and how to report unavailable checks.>

## 5. Documentation and version handling

<Applicable docs; actual version metadata; changelog scope and guide path.>

## 6. Execution and publication boundary

Execute only after the owner's review and execution instruction.
<Explicit authorized Git actions and release gates; RC internal-only boundary.>
Preserve unrelated work and secrets. Do not start another version round.

## 7. Required report and completion criteria

Finalize the paired report using guidelines/TEMPLATES.md with observed evidence.
<Task-specific required sections and PASS / incomplete / blocked criteria.>
```

## Expected report: `<chosen-stem>-ag.md`

```markdown
# <chosen-stem> — Agent report

Status: DRAFT — NOT EXECUTED
Prompt: historical_prompt/<chosen-stem>.md
Owner execution instruction: Pending
Execution date/environment: Not executed

## 1. Summary and requirement outcomes

Pending. After execution, map each requirement to implementation and evidence.

## 2. Initial repository state and historical reconciliation

Pending: branch, HEAD, staged/unstaged/untracked work, baseline version,
prior reports checked, confirmed existing behavior, and unresolved blockers.

## 3. Investigation and changes

Not executed. Record reproduction, confirmed causes versus hypotheses,
files changed, implementation decisions, compatibility, and dependency changes.

## 4. Automated tests and builds

Not executed. Record exact commands, exit codes, passed/failed/skipped counts,
report paths, environment, and blockers. Separate tests added from tests run.

## 5. Device and visual verification

Not executed. Record ADB serial, model/API, app version, test input method,
steps, expected/observed behavior, screenshots/logs, and unverified scenarios.

## 6. Version, artifacts, documentation, and licences

Pending: configured and packaged version metadata, artifact paths/hashes when
applicable, changelog checks, documentation changes, and licence findings.

## 7. Limitations and readiness

Pending. Separate failed requirements, partial verification, environment
blockers, and accepted limitations. State evidence-based readiness.

## 8. Final Git state and publication

Not executed. Record commits, actual pushed branch revisions, authorized
tags/releases/assets, command results, and remaining working-tree changes.
For internal RC rounds, confirm that no public release was created.

## 9. Final outcome

Pending. State completed scope, missing evidence, and exact next action.
```
