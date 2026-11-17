# Record templates

Use [prompt](templates/prompt.md), [report](templates/report.md), [guide](templates/guide.md) or [handoff](templates/handoff.md) according to the deliverable. Do not create a report skeleton merely because a prompt exists. A draft's TODO fields are not evidence; final records must describe actual results and limitations.

From the repository root in **Windows PowerShell**, `python tools/docs/new-document.py --help` explains metadata, version/category, topic and explicit predecessor revisions. The helper creates a draft and updates the index; it does not carry out the work described in the draft.

Use AUTHORING.md for filenames and scope. Substantive reports include outcome, baseline, authorized scope, changes, actual verification, limitations and next action. Prompts include objective, evidence, constraints, tasks, acceptance criteria and required outputs. Guides label command execution environments and recovery steps. Handoffs identify exact current state and a bounded next task.

Current maintained guides may be edited in place without becoming a dated task report. New standalone procedure records or task-specific guidance use the guide template. Git history retains ordinary maintained-guide edits; immutable task reports preserve their evidence snapshot.
