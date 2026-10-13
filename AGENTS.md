# Agent collaboration rules

## English-only policy (#77, effective 2026-10-13)
Use English exclusively for project communication, interface text, accessibility labels, errors, documentation, code comments, new commit messages, issues, PRs and reviews. Do not add Chinese copy or localization. This direct user instruction supersedes earlier language requirements. Product name: FengXiangJia. Preserve package IDs, URLs, technical identities and user-owned input. Do not rewrite immutable Git history. Synced sources/ files remain read-only.

1. Read README.md, product/contracts docs, TASK_STATUS.md, HANDOFF.md, the issue and associated PRs before work.
2. One owner per task/file. Claim an issue with goal, scope, files and validation before implementation; coordinate overlapping edits.
3. Use an isolated feat/<issue>-description, fix/<issue>-description or docs/<issue>-description branch. No direct development pushes to main; administrator initialization is the exception.
4. PRs must link issues, changes, executed commands/results, screenshots, risks and unfinished work. Review before merging. Writing tests does not prove they passed.
5. Discuss changes to coordinates, topology, weather, simulation I/O, persistence and entitlements first; record the contract and decision before cross-module implementation.
6. Distinguish design, source, CI, emulator, physical device, TestFlight and store acceptance. Never fabricate test results or model accuracy.
7. Visualization and recommendations use the same computation. Mark stale/failed results pending, not current.
8. Household scope, not professional CFD. No default upload of floor plans, precise addresses or recordings; core use requires no login.
9. Handoff records task, branch, SHA, files, executed/unexecuted tests, blockers and next action in HANDOFF.md and the issue.
10. Prefer platform capabilities and license-reviewed open source. Record dependencies/notices; do not copy proprietary assets or code.

When uncertain, complete a small verifiable end-to-end flow with concrete remaining work.
