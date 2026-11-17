# Task intake, implementation and handoff

## Start from the actual request

Read root AGENTS.md, current status, the authoring/version rules and relevant maintained guides. Inspect the source configuration, branch, HEAD and staged/unstaged/untracked work. If a tool cannot read Git state, state that limitation; do not invent a clean tree or a branch. Historical filenames, test counts and recent timestamps do not establish current implementation.

Classify the request before creating records:

- A question usually needs an answer, not a new report and paired empty prompt.
- A request for a plan/prompt produces a concrete reviewable plan/prompt. Execute only if execution was also requested or already authorized.
- A direct request to implement/fix/reorganize authorizes its necessary local work. Choose routine filenames, categories and document revisions yourself using AUTHORING.md. Do not pause simply because an old workflow required an empty report skeleton and another approval.
- A request to release/publish must establish the target version and relevant Git/hosting scope. Retain applicable existing authorization; distinguish commit, push, tag, release, APK upload and Google submission. Never infer destructive history/tag changes from a historical licensing script.

Inspect available evidence before asking a question. Ask only for ambiguity that changes the outcome, unsupported release allocation or missing required authority. Continue useful independent work while waiting. If the owner supplied a filename or version explicitly, honor it and record any needed compatibility exception rather than silently normalizing it.

## Implement and keep evidence

Preserve unrelated changes, private configuration, signing continuity, authentic workbooks and prior QA records. Follow current architecture and storage compatibility. Reuse established state, persistence and UI patterns; do not perform unrelated refactors or introduce dependencies without a concrete need and applicable review.

For defects, establish reproduction, separate hypotheses from confirmed causes, make the focused fix and run relevant regression checks. For UI/cloud/camera work, distinguish JVM tests, compilation, emulator interaction, physical-device testing and live provider verification. Record environment, build identity, commands/results and limitations. Never treat picked-image testing as camera testing or old SDK tests as current AppAuth tests.

Write one concise progress/draft report for a long task, updating it in place while draft. Do not generate redundant daily summaries, prompts and handoffs with identical content. Preserve an immutable final report when the task's substantive evidence is complete; use a successor for corrections. Validate fixtures before relying on them and keep sensitive evidence in the private archive.

## Finish the authorized scope

Update the relevant maintained guides and semantic status, then run the documentation refresh/check commands. Run Android checks only where relevant to actual code/build changes. Record checks not performed. A passing documentation check proves structure and consistency, not device behavior, legal compatibility or release approval.

For an authorized stable/dev publication, follow VERSIONING.md and CHANGELOG_GUIDELINES.md: release name equals tag, release body equals the matching complete changelog, and the APK uses the canonical versioned filename. Observe existing release blockers and distinguish signing/building from uploading. RCs remain internal. Do not invent another release or crash-fix round after completing the task.

The final response identifies the canonical output, actual changes, relevant checks, remaining work and any unapplied step. A new chat should be able to continue from current status, the report and its evidence without reading the entire conversation.
