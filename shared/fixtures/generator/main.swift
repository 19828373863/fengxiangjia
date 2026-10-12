import Foundation
import FXJCore

func U(_ s: String) -> UUID { UUID(uuidString: s)! }
func jsonObj<T: Encodable>(_ v: T) -> Any {
    let e = DocumentCodec.encoder(); let d = try! e.encode(v)
    return try! JSONSerialization.jsonObject(with: d, options: [.fragmentsAllowed])
}
func expl(_ e: Explanation) -> String {
    switch e {
    case .closedRoomInternalOnly: return "closedRoomInternalOnly"
    case .singleSidedOpening: return "singleSidedOpening"
    case .crossVentilation: return "crossVentilation"
    case .noEnvironment: return "noEnvironment"
    case .staleEnvironment: return "staleEnvironment"
    case .fanBlocked(let id): return "fanBlocked:\(id.uuidString)"
    case .targetCovered(let id): return "targetCovered:\(id.uuidString)"
    case .targetMissed(let id): return "targetMissed:\(id.uuidString)"
    case .targetDraft(let id): return "targetDraft:\(id.uuidString)"
    case .fanAidsExhaust(let id): return "fanAidsExhaust:\(id.uuidString)"
    case .obstructionReduced(let id, let l): return "obstructionReduced:\(id.uuidString):\(l.rawValue)"
    }
}
let t0 = Date(timeIntervalSince1970: 1_800_000_000)
var cases: [(String, HomeDocument, UUID, Environment?)] = []

let sample = SampleData.sampleDocument()
cases.append(("sample-no-wind", sample, SampleData.roomId, nil))
cases.append(("sample-south-3ms", sample, SampleData.roomId, Environment(source: .manual, observedAt: t0, windFromDegrees: 180, windSpeed: 3)))
do { // orientation + floor + obstruction
    var d = sample; d.home.orientationDegrees = 30.25; d.home.floorLevel = 8
    let env = Environment(source: .assumed, observedAt: t0, windFromDegrees: 200.04, windSpeed: 4.5, temperature: 31.2, rain: 0.4,
                          obstructionByOpening: [U("00000000-0000-0000-0000-0000000B0001"): .nearBuilding])
    cases.append(("sample-oriented-obstructed", d, SampleData.roomId, env))
}
do { // ceiling fan, tall wardrobe, avoid-draft polygon target, power edge
    var d = sample
    var r = d.home.rooms[0]
    r.fans.append(Fan(id: U("00000000-0000-0000-0000-0000000D0002"), kind: .ceiling, position: Vec2(2.5, 2), yawDegrees: 0, power: 1))
    r.fans.append(Fan(id: U("00000000-0000-0000-0000-0000000D0003"), kind: .circulator, position: Vec2(0.4, 0.4), yawDegrees: 45.05, power: 0.85))
    r.obstacles.append(Obstacle(id: U("00000000-0000-0000-0000-0000000C0003"), kind: .wardrobe, center: Vec2(0.4, 3.0), size: Vec2(0.6, 1.6), rotationDegrees: 10, heightClass: .tall))
    r.targets.append(Target(id: U("00000000-0000-0000-0000-0000000E0002"), name: "书桌", shape: .polygon([Vec2(3.5, 1.5), Vec2(4.5, 1.5), Vec2(4.5, 2.5), Vec2(3.5, 2.5)]), objective: .avoidDirectDraft))
    d.home.rooms[0] = r
    cases.append(("ceiling-wardrobe-draft", d, SampleData.roomId, Environment(source: .manual, observedAt: t0, windFromDegrees: 90, windSpeed: 2)))
}
do { // closed room
    var d = sample; d.home.rooms[0].openings = []
    cases.append(("closed-room", d, SampleData.roomId, Environment(source: .manual, observedAt: t0, windFromDegrees: 0, windSpeed: 5)))
}
do { // L-shape, two windows, fan aiming at window
    let wid = (1...6).map { U(String(format: "00000000-0000-0000-0000-0000001A%04d", $0)) }
    var r = Room(id: U("00000000-0000-0000-0000-000000100001"), name: "L", polygon: RoomTemplate.defaultLShape.polygon, wallIds: wid, purpose: .bedroom)
    r.openings = [Opening(id: U("00000000-0000-0000-0000-0000001B0001"), wallId: wid[1], kind: .window, offset: 0.5, width: 1.2, openFraction: 0.8),
                  Opening(id: U("00000000-0000-0000-0000-0000001B0002"), wallId: wid[5], kind: .window, offset: 1, width: 1.5)]
    r.fans = [Fan(id: U("00000000-0000-0000-0000-0000001D0001"), kind: .tower, position: Vec2(4.2, 1.1), yawDegrees: 0, power: 0.5),
              Fan(id: U("00000000-0000-0000-0000-0000001D0002"), kind: .desk, position: Vec2(1, 3), yawDegrees: -90, power: 0.33)]
    r.targets = [Target(id: U("00000000-0000-0000-0000-0000001E0001"), name: "床", shape: .circle(center: Vec2(1.5, 1.5), radius: 1), objective: .wantBreeze)]
    var h = Home(id: U("00000000-0000-0000-0000-000000100000"), name: "L宅", orientationDegrees: 315, rooms: [r])
    Topology.refreshConnections(&h)
    cases.append(("lshape-east-wind", HomeDocument(home: h), r.id, Environment(source: .manual, observedAt: t0, windFromDegrees: 45, windSpeed: 3.3)))
}
do { // multi-room: bedroom adjacent via door, simulate bedroom
    var d = sample
    let bid = try! d.home.addAdjacentRoom(to: SampleData.roomId, wallIndex: 1, depth: 3, name: "卧室", purpose: .bedroom)
    // fix random ids for determinism
    var h = d.home
    let bi = h.rooms.firstIndex { $0.id == bid }!
    var b = h.rooms[bi]
    let newId = U("00000000-0000-0000-0000-000000200001")
    b.id = newId
    b.wallIds = (1...4).map { U(String(format: "00000000-0000-0000-0000-0000002A%04d", $0)) }
    b.openings = [Opening(id: U("00000000-0000-0000-0000-0000002B0001"), wallId: b.wallIds[0], kind: .door, offset: 1.0, width: 0.9),
                  Opening(id: U("00000000-0000-0000-0000-0000002B0002"), wallId: b.wallIds[2], kind: .window, offset: 1.0, width: 1.5, exteriorObstruction: .partial)]
    b.fans = [Fan(id: U("00000000-0000-0000-0000-0000002D0001"), kind: .pedestal, position: Vec2(7, 2), yawDegrees: 180, power: 0.7)]
    h.rooms[bi] = b
    Topology.refreshConnections(&h)
    d.home = h
    let env = ObstructionModel.apply(Environment(source: .manual, observedAt: t0, windFromDegrees: 270, windSpeed: 2.5), home: h)
    cases.append(("multiroom-bedroom", d, newId, env))
    cases.append(("multiroom-living", d, SampleData.roomId, env))
}
do { // invalid: self-intersecting + narrow opening
    let wid = (1...4).map { U(String(format: "00000000-0000-0000-0000-0000003A%04d", $0)) }
    var r = Room(id: U("00000000-0000-0000-0000-000000300001"), name: "bad", polygon: [Vec2(0,0), Vec2(3,3), Vec2(3,0), Vec2(0,3)], wallIds: wid)
    r.openings = [Opening(id: U("00000000-0000-0000-0000-0000003B0001"), wallId: wid[2], kind: .window, offset: 0, width: 0.1)]
    let h = Home(id: U("00000000-0000-0000-0000-000000300000"), name: "bad", rooms: [r])
    cases.append(("invalid-self-intersecting", HomeDocument(home: h), r.id, nil))
}
do { // clockwise
    let wid = (1...4).map { U(String(format: "00000000-0000-0000-0000-0000004A%04d", $0)) }
    let r = Room(id: U("00000000-0000-0000-0000-000000400001"), name: "cw", polygon: [Vec2(0,0), Vec2(0,3), Vec2(3,3), Vec2(3,0)], wallIds: wid)
    cases.append(("invalid-clockwise", HomeDocument(home: Home(id: U("00000000-0000-0000-0000-000000400000"), name: "cw", rooms: [r])), r.id, nil))
}

var out: [[String: Any]] = []
for (name, doc, rid, env) in cases {
    let input = SimulationInput(home: doc.home, roomId: rid, environment: env)
    let res = AirflowSolver.solve(input)
    var c: [String: Any] = ["name": name, "document": jsonObj(doc), "roomId": rid.uuidString,
                            "environment": env.map { jsonObj($0) } ?? NSNull(), "scenarioHash": res.scenarioHash,
                            "modelVersion": res.modelVersion, "explanations": res.explanations.map(expl)]
    switch res.status { case .ok: c["status"] = ["ok"]; case .invalidInput(let a): c["status"] = ["invalidInput"] + a }
    if let m = res.metrics {
        c["metrics"] = ["circulationCoverage": m.circulationCoverage, "meanSpeed": m.meanSpeed, "outdoorExchange": m.outdoorExchange.rawValue,
                        "hasOutdoorPath": m.hasOutdoorPath, "crossVentilation": m.crossVentilation,
                        "targets": m.targets.map { ["targetId": $0.targetId.uuidString, "objective": $0.objective.rawValue,
                                                    "breezeCoverage": $0.breezeCoverage, "draftExposure": $0.draftExposure, "meanSpeed": $0.meanSpeed] }]
    }
    if let f = res.field {
        c["field"] = ["origin": [f.origin.x, f.origin.y], "cellSize": f.cellSize, "nx": f.nx, "ny": f.ny,
                      "mask": f.mask.map { $0 ? 1 : 0 }, "u": f.u, "v": f.v]
        if let vis = FlowVisualization(result: res) {
            c["visualization"] = ["streamlines": vis.streamlines.count, "arrows": vis.arrows.count,
                                  "streamlinePoints": vis.streamlines.map(\.points.count)]
        }
    }
    out.append(c)
}
// comparison case on sample
do {
    let room = sample.home.rooms[0]
    let a = Scenario.capture(name: "A", room: room, geometryRevision: 0, environment: nil, createdAt: t0)
    var rb = room; rb.fans[0].position = Vec2(1, 1); rb.fans[0].yawDegrees = 60; rb.openings[1].openFraction = 1
    let b = Scenario(id: U("00000000-0000-0000-0000-0000000F0002"), name: "B", roomId: room.id, geometryRevision: 0, environmentSnapshot: nil,
                     fans: rb.fans, openingStates: Dictionary(uniqueKeysWithValues: rb.openings.map { ($0.id, $0.openFraction) }), targets: rb.targets, createdAt: t0)
    let env = Environment(source: .manual, observedAt: t0, windFromDegrees: 180, windSpeed: 3)
    let cmp = ScenarioComparison.compare(home: sample.home, roomId: room.id, a: a, b: b, currentGeometryRevision: 0, environment: env)!
    let comp: [String: Any] = ["scenarioB": jsonObj(b), "environment": jsonObj(env),
        "hashA": cmp.resultA.scenarioHash, "hashB": cmp.resultB.scenarioHash,
        "dimensions": cmp.dimensions.map { ["kind": $0.kind.rawValue, "levelA": $0.levelA.rawValue, "levelB": $0.levelB.rawValue, "preferred": $0.preferred.rawValue] },
        "notes": cmp.notes.map { String(describing: $0) }, "suggestions": cmp.suggestions.map { String(describing: $0) }]
    let data = try! JSONSerialization.data(withJSONObject: comp, options: [.sortedKeys, .prettyPrinted])
    try! data.write(to: URL(fileURLWithPath: "out/comparison.json"))
}
let data = try! JSONSerialization.data(withJSONObject: ["generator": "FXJCore (Swift) feat/25 via Sources/Gen", "modelVersion": SimulationResult.modelVersion, "cases": out], options: [.sortedKeys])
try! data.write(to: URL(fileURLWithPath: "out/solver-golden.json"))
// persistence fixtures
var full = sample
full.scenarios = [Scenario.capture(name: "当前", room: sample.home.rooms[0], geometryRevision: 0,
                  environment: Environment(source: .manual, observedAt: t0, windFromDegrees: 180, windSpeed: 3, obstructionByOpening: [U("00000000-0000-0000-0000-0000000B0001"): .partial]), createdAt: t0)]
full.scenarios[0].id = U("00000000-0000-0000-0000-0000000F0001")
full.feedback = [ComfortFeedback(id: U("00000000-0000-0000-0000-0000000F1001"), roomId: SampleData.roomId, scenarioHash: "abc", rating: .justRight, note: "ok", createdAt: t0)]
full.home.location = Location(latitude: 1.35, longitude: 103.82, label: "SG")
try! DocumentCodec.encode(full).write(to: URL(fileURLWithPath: "out/document-v2.json"))
print("ok", out.count)
