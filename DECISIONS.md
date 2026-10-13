# Decision log

- D001: Standalone iOS App Store household tool, not professional HVAC simulation.
- D002: SwiftUI/native capabilities; prioritize local computation and no-login use.
- D003: Manual drawing essential; RoomPlan optional on compatible LiDAR devices; scans editable.
- D004: Home location independent of live phone location; query weather for the home; editable floor/obstruction.
- D005: Flow and recommendations share a snapshot; A/B uses identical weather.
- D006: Microphone weak-airflow sensing and automatic terrain reconstruction are future experiments.
- D007: Free trial + non-consumable unlock provisional; pricing requires testing.
- W001 (#77,2026-10-13): User requires English for maintained project content/UI. Preserve identities and immutable history. Earlier Chinese-localization choices are superseded.
- W002 (#77,2026-10-13): Prioritize browser goals, manual dimensions, one fan, recommendation and feedback. Browser persistence is separate from native HomeDocument. Web suggestions are explicit placement heuristics, not the native solver. RoomPlan and computer reconstruction remain separate.

D001–D007 are historical translations; W002 changes the immediate delivery priority. Every new/revised decision needs an issue and reason.
