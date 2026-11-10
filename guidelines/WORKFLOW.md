# Prompt, execution, and reporting workflow

## 1. Prepare for owner review

Read the relevant historical prompt/report pairs, current architecture, build configuration, changelog guide, branch, HEAD, and working-tree status. Reconcile old requirements with actual implementation. A filename or past report is not proof that a feature exists or a test passed.

Use the owner's selected full filename, or their explicit instruction to increment a prior suffix. Create the prompt and matching `-ag.md` report skeleton in `historical_prompt/`, using [TEMPLATES.md](TEMPLATES.md). If the chosen path already exists, preserve its contents and clarify whether to revise it or create an explicitly named successor.

The prompt must be concrete and reviewable: requirements, acceptance criteria, exclusions, version allocation, implementation constraints, verification commands, expected report sections, and intended Git/publication actions. Stable and dev releases normally include eventual repository-wide publication; identify the actual target branches/remotes in the prompt rather than assuming historical `main`, `master`, or `online_improvement` instructions still apply. Separate pushes, tags, hosted releases, and APK uploads in that scope.

Mark the report **DRAFT — NOT EXECUTED**, leaving results as **Not executed** or **Pending**. Do not prefill successful outcomes, root causes, test counts, or publication claims. Tell the owner which files are ready for review, then wait for their execution instruction. This review gate is the owner's explicit workflow requirement, not an inference from old prompts. If the owner already explicitly authorized execution of that concrete prompt, proceed within that authorization.

## 2. Execute the approved scope

Recheck branch, revision, changes, version metadata, and prior evidence before modifying code. Preserve `.env`, ignored files, QA artifacts, logs, historical records, and unrelated user edits. Never print secrets or use destructive cleanup to make the tree clean.

Follow the approved requirements and current architecture. Reuse existing state ownership, persistence, navigation, and UI patterns; avoid unrelated refactors and unnecessary dependencies. Preserve saved-data compatibility. Review direct/transitive dependency licences when dependencies change; preserve project GPL-3.0-or-later and third-party notices. Historical restrictions specific to a feature or phase do not automatically apply to unrelated work.

For a bug fix, establish the reproduction and evidence, distinguish confirmed causes from hypotheses, make a focused correction, and run appropriate regression checks. For Compose UI work, distinguish logic tests and successful compilation from actual interaction and visual verification on a device. Record ADB serial, emulator/device model, Android version, app metadata, steps, and observations. Do not describe imported-image testing as camera testing or unavailable ADB access as device verification.

Validate fixtures before relying on them. Separate authentic user workbooks from generated fixtures, and do not expose workbook contents or account identifiers in reports. Continue independent authorized work when an environment-dependent check is blocked, while recording the exact blocker.

Keep a progress record for long tasks so execution can resume without repeating completed work. Update applicable documentation, including the single-current-release changelog only when preparing the corresponding stable/dev release. An internal RC or crash-fix round does not itself replace the published changelog with a fictitious release.

## 3. Finalize the report and publication

Finalize the matching report with actual changes, requirement outcomes, commands, exit/results evidence, test counts, artifact metadata, device observations, limitations, and before/after Git state. Clearly separate implemented, verified, partially verified, blocked, failed, skipped, and not executed. Preserve later instructions as separately labelled appendices if needed; never mix an unexecuted follow-up prompt into completed results.

For approved stable/dev releases, complete the specified commit/push and publication workflow after required verification. Approval to execute a prompt that explicitly includes those operations covers that scope; do not ask repeatedly for the same authorization. If publication scope is missing, prepare a reviewable result and obtain the missing instruction before publishing. Never infer authority for force-pushes, history rewrites, tag replacement, or unrelated branches from a historical prompt.

RC rounds remain internal testing and are never published releases. Crash-fix rounds follow the parent task's explicitly approved Git scope; the suffix alone authorizes no publication or version bump. Unresolved release blockers must be reported before declaring readiness or publication success.

Record actual commits, pushed branch revisions, tags/releases if authorized, and residual staged/unstaged/untracked changes. Do not claim publication merely because a local commit exists. Stop after the approved task; do not invent or execute another version or crash-fix round.
