import Foundation

/// 户型模板（逆时针，原点在左下角，米）。
public enum RoomTemplate: Hashable, Sendable {
    case rectangle(width: Double, depth: Double)
    /// L 形：整体 width×depth，右上角切去 cutWidth×cutDepth。
    case lShape(width: Double, depth: Double, cutWidth: Double, cutDepth: Double)

    public static let defaultRectangle = RoomTemplate.rectangle(width: 4, depth: 3)
    public static let defaultLShape = RoomTemplate.lShape(width: 5, depth: 4, cutWidth: 2, cutDepth: 1.5)

    public var polygon: [Vec2] {
        switch self {
        case let .rectangle(w, d):
            return [Vec2(0, 0), Vec2(w, 0), Vec2(w, d), Vec2(0, d)]
        case let .lShape(w, d, cw, cd):
            let cw = min(max(cw, 0.1), w - 0.1), cd = min(max(cd, 0.1), d - 0.1)
            return [Vec2(0, 0), Vec2(w, 0), Vec2(w, d - cd), Vec2(w - cw, d - cd), Vec2(w - cw, d), Vec2(0, d)]
        }
    }
}
