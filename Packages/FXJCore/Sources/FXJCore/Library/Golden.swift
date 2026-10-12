import Foundation

/// #21 确定性回归基准：示例房间在固定环境下的 scenarioHash 与关键指标。
/// 模型或哈希规则有意变更时须同时更新此处与 docs/TEST_REPORT.md，并提升 modelVersion。
public enum GoldenScenarios {
    public struct Case: Sendable {
        public var name: String
        public var environment: Environment?
        public var scenarioHash: String
        public var outdoorExchange: Level
        public var crossVentilation: Bool
        public var circulationCoverage: Double
        public var meanSpeed: Double
        public var targetBreeze: Double
    }

    public static let observedAt = Date(timeIntervalSince1970: 1_800_000_000)

    public static let cases: [Case] = [
        Case(name: "sample-no-wind", environment: nil,
             scenarioHash: "376307d75efd12390770a1ef3adb4c6a7172f75239df3c2e985a8aaac5610a6b",
             outdoorExchange: .low, crossVentilation: false, circulationCoverage: 0.3675, meanSpeed: 0.1860, targetBreeze: 0.6458),
        Case(name: "sample-south-3ms",
             environment: Environment(source: .manual, observedAt: observedAt, windFromDegrees: 180, windSpeed: 3),
             scenarioHash: "d8bb83d50ae79f0a132c28302be8fd2567bd775ca9e8810d9a276cc8c25543a0",
             outdoorExchange: .high, crossVentilation: true, circulationCoverage: 0.6125, meanSpeed: 0.3221, targetBreeze: 0.8333),
    ]

    public static func input(_ c: Case) -> SimulationInput {
        SimulationInput(home: SampleData.sampleDocument().home, roomId: SampleData.roomId, environment: c.environment)
    }
}
