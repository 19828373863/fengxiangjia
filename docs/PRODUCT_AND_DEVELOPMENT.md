# Product and development plan

## Current direction (#77)
Help users try a useful fan arrangement within roughly three minutes: an acceptance target, not a measured result.

1. Choose stuffiness, uncomfortable direct draft, or breeze at a chosen spot.
2. Enter approximate dimensions, openings and where the person sits/sleeps.
3. Show one practical arrangement and reasons; deeper analysis stays optional.
4. Save the trial and report better/no change/worse after trying it.

Navigation: Home, Improve, History. Room diagrams explain placement. Manual setup works without scanning. Native LiDAR may later reduce effort; computer photo/video reconstruction stays experimental. English only.

First web slice: one rectangular room,one fan,editable target/openings,placement heuristics,local trials/feedback,export/import and recoverable persistence. Do not call heuristic arrows simulated streamlines. Real comfort improvements need separate validation.

## Original native plan (historical scope)
Buy-once iOS household ventilation/fan comparison tool requiring no expertise. Ease,explanations and practical actions matter more than numerical precision.

Original flow: sample without sign-in → manual rectangle/L or compatible editable RoomPlan scan → approximate home location/orientation/floor/window obstruction → current/hourly weather or manual conditions with source/time → openings/obstacles/fan/breeze or avoid-draft targets → same-condition A/B coverage,draft,paths,cost with ≤3 suggestions → save/export/feedback and non-consumable expansion unlock.

Original MVP: SwiftUI2D editor,wall-attached openings,simplified furniture,one room/fan/target,animated/static arrows,A/B,local storage,sample. Later: connected rooms,1–2 fans,weather,RoomPlan,export,StoreKit purchase/restore. Microphone sensing,automatic terrain,complex thermal fields,AC jets and smart-home integrations do not block v1.

Original stack: Swift/SwiftUI,JSON or SwiftData with migration,optional RoomPlan/ARKit,CoreLocation/WeatherKit with fallback,Canvas/Metal,StoreKit2,XCTest/device validation.

Model: room/opening topology and simplified2D fan-driven field with boundaries/obstacles; household illustration,not engineering CFD. Versioned snapshots bind explanations/visualization; recompute after changes. Sealed-room fans supply circulation only.

Phases: P0 scaffold/contracts/sample; P1 editing/tryout/comparison/save; P2 regressions/explanations/manual environment/multi-room; P3 RoomPlan/location/weather/obstruction; P4 purchases/privacy/recovery/accessibility; P5 TestFlight/device/user acceptance/store release. Each needs source,tests,screenshots,handoff; later incomplete phases do not negate earlier scoped evidence.
