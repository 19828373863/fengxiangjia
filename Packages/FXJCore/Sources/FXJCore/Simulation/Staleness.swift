import Foundation

/// #18：结果过期原因。任何影响 scenarioHash 的输入变化都使旧结果过期；此处给出可读原因供 UI 提示。
public enum StaleReason: String, CaseIterable, Sendable, Hashable {
    case weather, orientation, floor, obstruction, openings, geometry, layout, model, options
}

public enum Staleness {
    /// 旧结果相对新输入的过期原因；空集合表示结果仍然有效。
    public static func reasons(result: SimulationResult, oldInput: SimulationInput, newInput: SimulationInput) -> Set<StaleReason> {
        if result.isCurrent(for: newInput) { return [] }
        var r = reasons(from: oldInput, to: newInput)
        if result.modelVersion != SimulationResult.modelVersion { r.insert(.model) }
        if r.isEmpty { r.insert(.model) } // 哈希不一致但无可识别原因：保守视为过期
        return r
    }

    public static func reasons(from a: SimulationInput, to b: SimulationInput) -> Set<StaleReason> {
        var r: Set<StaleReason> = []
        func envCore(_ e: Environment?) -> [String]? {
            e.map { [$0.source.rawValue, String(Int($0.observedAt.timeIntervalSince1970)), String(Int(($0.windFromDegrees * 10).rounded())),
                     "\($0.windSpeed.map { Int(($0 * 100).rounded()) } ?? -1)", "\($0.temperature.map { Int(($0 * 10).rounded()) } ?? -9999)",
                     "\($0.rain.map { Int(($0 * 10).rounded()) } ?? -1)"] }
        }
        if envCore(a.environment) != envCore(b.environment) { r.insert(.weather) }
        if (a.environment?.obstructionByOpening ?? [:]) != (b.environment?.obstructionByOpening ?? [:]) { r.insert(.obstruction) }
        if Int((a.home.orientationDegrees * 10).rounded()) != Int((b.home.orientationDegrees * 10).rounded()) { r.insert(.orientation) }
        if a.home.floorLevel != b.home.floorLevel { r.insert(.floor) }
        if a.options != b.options { r.insert(.options) }
        guard let ra = a.room, let rb = b.room else { r.insert(.geometry); return r }
        if ra.polygon != rb.polygon || ra.wallIds != rb.wallIds || ra.obstacles != rb.obstacles { r.insert(.geometry) }
        if ra.openings != rb.openings || a.home.rooms.count != b.home.rooms.count { r.insert(.openings) }
        if ra.fans != rb.fans || ra.targets != rb.targets { r.insert(.layout) }
        // 邻室几何/开口变化（多房间）
        let others = { (h: Home, id: EntityID) in h.rooms.filter { $0.id != id }.sorted { $0.id.uuidString < $1.id.uuidString } }
        if others(a.home, a.roomId).map(\.openings) != others(b.home, b.roomId).map(\.openings) { r.insert(.openings) }
        return r
    }
}
