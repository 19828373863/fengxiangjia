# Development and review

Workflow: issue → ownership comment → branch → focused commits → tests → PR → review → squash merge → update HANDOFF/TASK_STATUS. Use English throughout.

Use branches such as feat/12-room-editor, fix/13-openings or docs/14-contracts. Coordinate before editing another owner's files. Main accepts reviewed PRs; administrators should configure protection and required checks. This document does not prove those settings are enabled.

PRs include issue, acceptance criteria, scope, commands and accurate output summaries, simulator/device types, limitations, relevant before/after screenshots, schema/permission impact and rollback.

Cover undo, opening topology, same-condition A/B, no outdoor exchange in sealed rooms, save/restore, weather expiry and denied permissions. Label unexecuted tests "not run" with reasons. Experiments require evidence before delivery claims. Run `python scripts/check_english.py` on maintained text.

Never commit keys, Apple account data, credentials, precise addresses, private floor plans or original recordings. Request location/microphone on demand and allow refusal. Check licenses and retain notices.

Discuss public API changes in an RFC/decision issue. Update migration tests with interfaces; do not silently break models.
