# Architecture and contracts v0.1

## Original native contract
Domain(home/rooms/openings/devices/scenarios) → Geometry(coordinates/topology) → Simulation(lightweight field) → Recommendation(comparison/explanations) → UI(SwiftUI). External RoomPlan/Location/Weather/StoreKit adapters. Lower layers must not depend on UI.

Right-handed indoor2D Cartesian coordinates, meters and user-facing degrees converted to radians internally. Define incoming/downstream wind and conversion. Convert RoomPlan before Domain import. Openings record host wall, extent and inside/outside connections; no invisible paths through walls.

Home{id,name,location?,orientation,rooms[],constraints}; Room{id,polygon,height?,purpose}; Opening{id,wallId,connects,kind,openFraction,locked}; Obstacle{id,footprint,heightClass}; Fan{id,position,yaw,power,kind}; Target{id,shape,objective}; Environment{source,observedAt,windFromDegrees,windSpeed?,temperature?,rain?,obstructionByOpening}; Scenario{id,geometryRevision,environmentSnapshot,layoutSnapshot,targets}; SimulationResult{scenarioHash,modelVersion,status,field,metrics,explanations}.

Input changes invalidate results. A/B shares environment/legend. Suggestions use the drawing's SimulationResult; unknown provenance is uncertain. Sealed-room fans cannot increase outdoor exchange. Scans editable; location failure/microphone denial do not block core use; mark stale weather. Persist schema version,IDs,conditions with migration/deletion/export; private data local by default, logs redacted.

## Web contract (#77)
Browser key fengxiangjia.web.v1 is independent of native documents. Record: version=1,room{width,depth,window,door,targetX,targetY},goal,trials[]. Dimensions in meters; screen-relative top/right/bottom/left walls, not true north. Goals stuffy/draft/breeze. Opening walls none/top/right/bottom/left. Target inside room.

Deterministic placement heuristics with reasons/assumptions; no measured wind/native solver claim. Diagram shows proposed fan aim, not a computed flow field. Input changes invalidate proposals. Saved trials capture inputs/proposal together; better/same/worse feedback belongs to that trial. Restore its own inputs; never overwrite previous feedback when creating a trial.

Storage failures visible; corrupt/future-version data preserved without automatic replacement. Explicit reset affects only this key. Export local JSON; validate schema,bounds,size before confirmed replacement on import. No upload,camera,LiDAR,weather fetch or native-schema import in this slice.
