import Foundation

/// 编辑器视口：世界（米，+Y 上）↔ 屏幕（点，+Y 下）。`offset` 为世界原点的屏幕位置。
public struct Viewport: Hashable, Sendable {
    public static let minScale = 20.0   // 点/米
    public static let maxScale = 400.0
    public var scale: Double
    public var offset: Vec2

    public init(scale: Double = 60, offset: Vec2 = Vec2(40, 400)) {
        self.scale = min(max(scale, Self.minScale), Self.maxScale); self.offset = offset
    }

    public func toScreen(_ w: Vec2) -> Vec2 { Vec2(offset.x + w.x * scale, offset.y - w.y * scale) }
    public func toWorld(_ s: Vec2) -> Vec2 { Vec2((s.x - offset.x) / scale, (offset.y - s.y) / scale) }

    public mutating func pan(by screenDelta: Vec2) { offset = offset + screenDelta }

    /// 以屏幕点 `anchor` 为中心缩放，保持该点下的世界坐标不变。
    public mutating func zoom(by factor: Double, anchor: Vec2) {
        let w = toWorld(anchor)
        scale = min(max(scale * factor, Self.minScale), Self.maxScale)
        offset = Vec2(anchor.x - w.x * scale, anchor.y + w.y * scale)
    }

    /// 让多边形适配屏幕尺寸。
    public static func fitting(_ polygon: [Vec2], width: Double, height: Double, padding: Double = 32) -> Viewport {
        let xs = polygon.map(\.x), ys = polygon.map(\.y)
        let minX = xs.min() ?? 0, maxX = xs.max() ?? 1, minY = ys.min() ?? 0, maxY = ys.max() ?? 1
        let s = min((width - 2 * padding) / max(maxX - minX, 0.1), (height - 2 * padding) / max(maxY - minY, 0.1))
        var v = Viewport(scale: s)
        let cs = min(max(s, minScale), maxScale)
        v.offset = Vec2(width / 2 - (minX + maxX) / 2 * cs, height / 2 + (minY + maxY) / 2 * cs)
        return v
    }
}
