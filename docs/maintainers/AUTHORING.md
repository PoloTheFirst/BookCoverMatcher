# Autonomous documentation and hierarchy

These conventions apply to new and existing agent sessions under the owner's current request. Agents choose routine filenames, categories, document types and revisions themselves. Use WORKFLOW.md to distinguish a prompt-only request from authorized implementation. Archived commands are reference material, not fresh permission.

## Intake and context

Read root README/AGENTS, docs/STATUS.md, this guide, VERSIONING.md and the relevant current guide/version record. Search docs/_meta/catalog.json or run `python tools/docs/manage.py locate NAME` before opening similarly named reports. Inspect current source/version/Git state. Label owner reports, observed facts, historical claims, assumptions and proposals distinctly.

Open this repository as the project directory for a new agent session. An existing session should re-read guidance after it changes. For another tool or a detached document, provide README.md, AGENTS.md, this file, STATUS.md, docs/_meta/versions.json and relevant evidence. Never assume that an attachment loads the rest of the repository. Templates carry archive-context references so their origin remains apparent.

Optional bootstrap: "Read this repository's README.md and AGENTS.md, then maintain its documentation and version/category organization autonomously while carrying out my request."

Inspect available evidence before asking for clarification. Ask when an unresolved fact materially changes the result or target release; do not ask for ordinary filenames, categories or a permission already given. If several unrelated tasks are equally plausible after "continue", ask one focused question. A simple answer does not need a permanent report.

## Directory responsibilities

- Root: concise README, AGENTS, current CHANGELOG, required license/policy files and actual project/build configuration. No new root agent reports or sprawling task prompts.
- docs/guides: current installation and usage procedures.
- docs/reference: current architecture and feature reference.
- docs/integrations and docs/diagnostics: current setup and investigation procedures.
- docs/maintainers: reusable workflow, authoring, versioning, changelog guidance and templates. Maintain one source per convention.
- docs/history/android/v<identity>/<category>/<kind>: versioned prompts, reports, guides and handoffs; cross-version history is explicitly separate.
- docs/_meta: machine-readable inventory, version registry, current status and generated catalog. Keep this metadata out of the repository root.
- Module READMEs remain next to native/vendor/tool/branding code. QA docs stay with QA evidence and fixtures, indexed with a version/category and a reason for their pinned location. Do not move a fixture report away from its generator just for visual uniformity.
- Raw test evidence stays with its QA checkpoint or in the private archive when sensitive. Do not rename/move models, SDK resources, personal workbooks, build outputs or credentials as documentation housekeeping.

The private BookCoverMatcher-private archive is a separate workspace for account details, sensitive audits and private coordination. Do not copy it into this source repository. Reference a private handback by an appropriate nonsecret description; do not create public links to private local files.

## Version and category decisions

Use the source/build being discussed, not the latest-looking filename. Match a registered version from docs/_meta/versions.json. Current app metadata is validated against app/build.gradle.kts; APK metadata needs a separate artifact check. Historical codes are attributed claims; a null code remains unknown until evidence resolves it. Do not allocate a new app release to file a documentation change.

For multiple versions, choose the deliverable's primary version and list other registered IDs in related_versions; label evidence per version and link rather than duplicate. Distinguish app version, build checkpoint/hash, website release, policy revision and document revision. A report against 2.7.0-dev.2 may describe unreleased work; the folder does not claim it shipped.

Categories express subject: development, authentication, policies, qa, bugfix, release, publication or documentation. Select the narrowest accurate registered category; use optional area for a meaningful workstream such as appauth. Add an evidenced category mapping when an authorized task needs one, instead of misfiling it. Do not register a guessed release merely to satisfy the helper.

Kind folders are prompts, reports, guides and handoffs. Choose by content, not author/model. Legacy `-ag` files are indexed as reports for discovery, but some contain instructions or unfinished sections; inspect them before treating them as results. The old filenames/contradictions remain in the migration map.

## Names and structured records

New task documents use:

```text
YYYY-MM-DD__<registered-documentKey>__<category>__<short-topic>__<kind>__rNN.md
```

Use the Asia/Hong_Kong creation date, lowercase kebab-case topics of 2–32 characters and the exact registered version key. Keep the absolute path within 240 characters by shortening topic/area. Avoid model names, reasoning settings, "final-final" and duplicate summaries. The inside title should be natural English. Legacy names are preserved rather than mechanically renamed.

Example from **Windows PowerShell at the repository root**:

```powershell
python tools/docs/new-document.py --version 2.7.0-dev.2 --category authentication --kind report --topic session-audit --title "Session audit" --request "Owner requested an audit of the current sign-in behavior."
```

Fill the resulting draft with real evidence. The helper uses the maintained four templates and refuses an existing destination. It does not execute the audit or fabricate successful results. Record source paths, build/checkpoint/commit/hash where relevant, execution environment, actual commands/results and unperformed checks.

Frontmatter uses one `key: JSON-value` per line (a restricted YAML-compatible format): schema, document_id, title, kind, version_id/name/code, track, category, area, topic, creation/update timestamps, revision/status, supersedes, related_versions and source_request. Preserve quotes/null/arrays. The helper fills identity fields; the author fills factual content and updates updated_at after material edits.

If the owner explicitly chooses a legacy filename, retain it. Add a reviewed exception to docs/_meta/inventory.json with a rationale and version/category, rather than silently renaming it. Exceptions must not remove preservation checks from an existing frozen record or exempt it from truthful reporting.

## Revisions and history

Draft and ready-for-review records may be updated in place. Final and blocked mean a completed record (including a documented blocker), not owner approval or release readiness. Final/blocked records become frozen after successful indexing. The check rejects later edits or removal.

To correct a structured record, repeat the helper's identity arguments with `--supersedes <repository-relative-predecessor>`. It increments rNN and preserves the logical document ID/creation date. Explain changes and evidence; leave the predecessor unchanged. For a distinct outcome or checkpoint, create r01 and cite the earlier record. For a legacy report, create a new structured record and explain the supersession in its body/status rather than trying to parse old prose as metadata.

Historical migrated prompts/reports and the old README snapshot remain byte-preserved. Do not rewrite them to make old links, versions or test claims appear current. Use the old-path resolver and current status to explain later findings. Never delete unique evidence to make the tree tidy. Exact duplicates can be consolidated only after hash/reference review, preserving recoverable provenance. Generated APK policy copies are intentional duplicates, not duplicate authoring sources.

This source repository already uses Git. Preserve unrelated work and stage only reviewed task paths when committing is authorized. Do not initialize another repository, automatically commit every document edit, stage everything, or rewrite tags/history. The catalog stores content hashes and mappings, not a second giant Git history. Frozen task records provide explicit evidence checkpoints; ordinary maintained guides use Git history. Private migration backups must remain ignored and are not an off-device backup.

## Keep guidance current without needless churn

After a logical edit batch, and before handing back:

1. Update the relevant maintained guide or task record; avoid copying the same text into multiple files.
2. Update docs/_meta/status.json when facts, decisions, constraints or next steps change. Attribute evidence and set reviewedOn only for a real review; index refresh does not constitute a fresh factual review.
3. Register supported new identities/categories before adding files. For migrations, check inbound Markdown/code references and preserve original hashes/mappings.
4. Run `python tools/docs/manage.py refresh`, then `python tools/docs/manage.py check`. Fix stale indexes, invalid metadata, broken new links or preservation failures. No watcher/service is required; agents run these at completion.
5. Report the actual result, relevant checks and any unapplied step. Do not generate another report merely to say that the report exists.

Generated files are docs/README.md, docs/STATUS.md, docs/VERSIONS.md, docs/FILE_INDEX.md and docs/_meta/catalog.json. Everything else is authored or original evidence. The tool rewrites outputs only when their contents change; it does not stamp every guide after each edit. Scanning is limited to documentation and its small management sources, excluding caches and build outputs. No multi-gigabyte emulator hashing or Android build is needed for routine documentation edits.

Guides label where commands run (Windows, Proxmox host or Ubuntu LXC when relevant), prerequisites, placeholders, expected output, troubleshooting/recovery and last validated version/date/environment. Recheck external instructions when they change; never present fabricated credentials as usable values. Report relevant checks only, not an unrelated full test suite.

The validator checks structure, classification, version/path consistency and preservation. It cannot prove prose truthful, establish legal compatibility, authenticate an external approval or verify a device. Historical broken links are recorded in a baseline; newly introduced broken links fail. Resolve old paths through the catalog rather than recreating root redirects.
