import Foundation

/// 室内二维右手笛卡尔坐标点/向量，单位：米。+Y 为户型“上”方向，朝向见 `Home.orientationDegrees`。
public struct Vec2: Codable, Hashable, Sendable {
    public var x: Double
    public var y: Double
    public init(_ x: Double, _ y: Double) { self.x = x; self.y = y }
    public init(x: Double, y: Double) { self.x = x; self.y = y }

    public static let zero = Vec2(0, 0)
    public static func + (a: Vec2, b: Vec2) -> Vec2 { Vec2(a.x + b.x, a.y + b.y) }
    public static func - (a: Vec2, b: Vec2) -> Vec2 { Vec2(a.x - b.x, a.y - b.y) }
    public static func * (a: Vec2, s: Double) -> Vec2 { Vec2(a.x * s, a.y * s) }
    public func dot(_ o: Vec2) -> Double { x * o.x + y * o.y }
    public func cross(_ o: Vec2) -> Double { x * o.y - y * o.x }
    public var length: Double { (x * x + y * y).squareRoot() }
    public var normalized: Vec2 { let l = length; return l > 0 ? Vec2(x / l, y / l) : .zero }
    public func distance(to o: Vec2) -> Double { (self - o).length }
}
