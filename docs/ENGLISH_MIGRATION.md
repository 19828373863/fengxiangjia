# English migration — issue #77

Effective 2026-10-13, English is required for all maintained project content and communication. The repository name, package IDs, issue numbers, branch references, hashes, URLs and historical verification facts are retained.

## Executed GitHub changes

Translated the titles/descriptions of all 76 pre-existing issues and PRs, plus all 46 issue-conversation comments. These were routine project records under the repository owner's account. Each update compared the current title/body with the saved original to detect concurrent edits, changed only text, and verified the response. A final paginated read found no CJK text in 77 issue/PR records (including #77) or 46 comments. A later English #77 contract comment adds one comment.

Labels and descriptions were already English. There were no milestones, releases or inline review comments in the inspected API collections. Repository description was empty. Local before/after records are retained outside source control; no credentials were written to them.

## Repository changes

Translated all maintained text files present on the main-branch baseline b7579eb. Added English-only instructions and a CI source-language check. Added the English guided web experience. These source changes are delivered through a review branch/PR; they do not silently change the default branch.

Historical commits and old feature-branch snapshots are not rewritten. Existing native application strings in those unmerged branches remain legacy work and require a separately tested English localization before release. User-authored private data and synced reference files are not translated or modified.

## Validation boundary

The language checker detects CJK characters in maintained source extensions; it is a regression guard, not a natural-language classifier or proof that every technical term is idiomatic English. Historical statements about Chinese localization are translated as historical statements and explicitly superseded, rather than falsely claiming those earlier builds were English.
