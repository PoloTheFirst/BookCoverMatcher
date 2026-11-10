# BookCoverMatcher guidelines

Read these documents before preparing a version prompt or executing an approved prompt:

1. [Version naming and codes](VERSIONING.md).
2. [Prompt, execution, and reporting workflow](WORKFLOW.md).
3. [Prompt and report templates](TEMPLATES.md).
4. [Changelog guidelines](CHANGELOG_GUILDELINES.md).

The project owner's current instructions take precedence over these documents. These guidelines capture the owner's versioning clarification and recurring practices from `historical_prompt/`; archived prompts are historical evidence, not standing authorization to execute their tasks or publish changes. Task-specific requirements apply only to their approved scope.

Prompts and reports stay in `historical_prompt/`. General reusable instructions belong here. The changelog guide was moved from root `CHANGELOG_GUIDELINES.md` to `guidelines/CHANGELOG_GUILDELINES.md`; the destination spelling is intentional to match the owner's request. Historical references to the old path are retained as historical records.

## Historical sources and reconciliation

- `v2.4.0-rc.1.md` through `v2.4.0-rc.7.md` and their reports show iterative testing, requirement reconciliation, fixture validation, emulator evidence, and uncommitted review rounds.
- `v2.5.0-dev.1.md`, `v2.5.0-dev.5.md`, and their reports demonstrate feature checkpoints, architecture constraints, dependency/licence review, and evidence-backed reporting.
- `v2.6.0.md`, `v2.6.0-dev.1.md`, and their reports demonstrate repository recovery, preservation, release readiness, and explicit publication scope.
- The `v2.5.0-dev.5-crash-fix-*` and `v2.6.0*-crash-fix-*` series demonstrate focused investigation, root-cause evidence, regression checks, and remaining limitations.
- `changelog-current-release-ag.md` records the single-current-release changelog policy.

The archive is not a uniform specification. For example, `v2.5.0-dev.2.md` has a stable-v2.4.0 title, and `v2.6.0-stable-2.md` has a v2.6.1 title. Some report files also contain later instructions or unfinished templates. Verify actual code, Git history, artifacts, and execution evidence rather than copying these inconsistencies. Do not rename or rewrite historical records to make them conform.
