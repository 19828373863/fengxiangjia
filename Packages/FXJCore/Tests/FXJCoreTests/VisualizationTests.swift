import XCTest
@testable import FXJCore

final class VisualizationTests: XCTestCase {
    func result(yaw: Double = 0, wardrobe: Bool = false) -> SimulationResult {
        var r = Room(name: "r", polygon: [Vec2(0, 0), Vec2(4, 0), Vec2(4, 3), Vec2(0, 3)])
        r.fans = [Fan(position: Vec2(0.8, 1.5), yawDegrees: yaw, power: 0.8)]
        if wardrobe { r.obstacles = [Obstacle(kind: .wardrobe, center: Vec2(2.5, 1.5), size: Vec2(0.4, 1.2), heightClass: .tall)] }
        let h = Home(name: "h", rooms: [r])
        return AirflowSolver.solve(SimulationInput(home: h, roomId: r.id, environment: nil))
    }

    func testSharesHashWithResult() {
        let res = result()
        let viz = FlowVisualization(result: res)!
        XCTAssertEqual(viz.scenarioHash, res.scenarioHash)
        XCTAssertFalse(viz.streamlines.isEmpty)
        XCTAssertFalse(viz.arrows.isEmpty)
    }

    func testDeterministic() {
        let res = result()
        XCTAssertEqual(FlowVisualization(result: res), FlowVisualization(result: res))
    }

    func testStreamlinesStayInsideFluid() {
        let res = result(wardrobe: true)
        let f = res.field!
        for line in FlowVisualization(result: res)!.streamlines {
            for p in line.points {
                guard let (ix, iy) = f.cell(of: p) else { return XCTFail("出界 \(p)") }
                XCTAssertTrue(f.mask[f.index(ix, iy)], "穿过固体 \(p)")
            }
        }
    }

    func testArrowsFollowJet() {
        let viz = FlowVisualization(result: result(yaw: 0))!
        // 风扇正前方的箭头应大致指向 +X
        let ahead = viz.arrows.filter { abs($0.position.y - 1.5) < 0.3 && $0.position.x > 1.2 && $0.position.x < 2.5 }
        XCTAssertFalse(ahead.isEmpty)
        for a in ahead { XCTAssertGreaterThan(a.direction.x, 0.7) }
    }

    func testLegendNormalizationClamped() {
        XCTAssertEqual(FlowVisualization.normalized(-1), 0)
        XCTAssertEqual(FlowVisualization.normalized(0.75), 0.5, accuracy: 1e-12)
        XCTAssertEqual(FlowVisualization.normalized(9), 1)
    }

    func testInvalidResultHasNoVisualization() {
        let bad = SimulationResult(scenarioHash: "x", modelVersion: SimulationResult.modelVersion, status: .invalidInput([]),
                                   field: nil, metrics: nil, explanations: [])
        XCTAssertNil(FlowVisualization(result: bad))
    }
}
