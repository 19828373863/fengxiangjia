# FengXiangJia

A household ventilation and fan-placement assistant. Find a practical arrangement, try it at home and record whether it feels better.

## Current direction
English is the sole project and interface language. The browser-first flow is **choose a goal → describe the room → try an adjustment → record the outcome**. Manual setup is the default; scanning may later reduce effort.

Run the local web preview:
```sh
python -m http.server 8787 --bind 127.0.0.1 --directory web
```
Open http://127.0.0.1:8787 on that computer. This loopback address is not an iPhone-accessible hosted URL. Browser records stay on that browser; export before clearing site data.

## Boundaries
- Household guidance, not certified HVAC or engineering CFD.
- Distinguish outdoor exchange, indoor circulation and personal comfort.
- Native RoomPlan needs compatible hardware and an iOS app; the website does not expose LiDAR.
- Photo/video reconstruction remains a separate experimental computer workflow, not a completed web capability.
- Native implementations remain on feature branches/PRs; this web branch does not merge them into main.

## Guides
- [Agent rules](AGENTS.md)
- [Contributing](CONTRIBUTING.md)
- [Product plan](docs/PRODUCT_AND_DEVELOPMENT.md)
- [Architecture/contracts](docs/ARCHITECTURE_AND_CONTRACTS.md)
- [Status](TASK_STATUS.md)
- [Handoff](HANDOFF.md)

The initial main snapshot contained documentation scaffolding, without an accepted iOS release or App Store publication. Later work must be assessed using its own evidence.
