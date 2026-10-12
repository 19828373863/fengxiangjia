import Foundation

/// #14 住宅位置与朝向纯逻辑。住宅位置独立于手机实时位置；只在用户确认后写入 Home。
public enum Bearing {
    public static func normalize(_ d: Double) -> Double {
        let r = d.truncatingRemainder(dividingBy: 360)
        let v = r < 0 ? r + 360 : r
        return v >= 360 - 1e-9 ? 0 : v
    }

    /// 户型方向向量相对户型 +Y 的顺时针角（度）。
    public static func clockwiseFromPlanY(_ v: Vec2) -> Double { normalize(atan2(v.x, v.y) * 180 / .pi) }

    /// 指南针校准：手机顶部对准户型中方向 `planDirection` 时读到真北方位 `heading`，求 Home.orientationDegrees。
    public static func orientation(heading: Double, planDirection: Vec2) -> Double {
        normalize(heading - clockwiseFromPlanY(planDirection))
    }

    /// 户型方向在真实世界中的方位角（真北顺时针）。
    public static func compassBearing(of planDirection: Vec2, orientation: Double) -> Double {
        normalize(orientation + clockwiseFromPlanY(planDirection))
    }

    /// 圆周平均（用于平滑多次指南针读数）；读数过于分散返回 nil。
    public static func circularMean(_ samples: [Double], maxSpreadDegrees: Double = 30) -> Double? {
        guard !samples.isEmpty else { return nil }
        let s = samples.reduce(0.0) { $0 + sin($1 * .pi / 180) }, c = samples.reduce(0.0) { $0 + cos($1 * .pi / 180) }
        let r = (s * s + c * c).squareRoot() / Double(samples.count)
        // r → 1 表示读数集中；cos(spread) 近似阈值
        guard r >= cos(maxSpreadDegrees * .pi / 180) else { return nil }
        return normalize(atan2(s, c) * 180 / .pi)
    }

    /// 8 方位中文名。
    public static func name(_ bearing: Double) -> String {
        let names = ["北", "东北", "东", "东南", "南", "西南", "西", "西北"]
        return names[Int((normalize(bearing) + 22.5) / 45) % 8]
    }
}

public extension Wall {
    /// 墙外侧（窗朝向）的真实方位。
    func facingBearing(orientation: Double) -> Double { Bearing.compassBearing(of: outwardNormal, orientation: orientation) }
}

public enum LocationError: Error, Equatable, Sendable { case unparsable, outOfRange }

public extension Location {
    /// 隐私：仅保留约 1 km 精度（0.01°）。
    func coarsened(step: Double = 0.01) -> Location {
        Location(latitude: (latitude / step).rounded() * step, longitude: (longitude / step).rounded() * step, label: label)
    }

    var isValid: Bool { (-90...90).contains(latitude) && (-180...180).contains(longitude) }

    /// 解析手动输入，如 "1.35, 103.82"、"1.35 103.82"、"北纬1.35 东经103.82"（中文前缀可选）。
    static func parse(_ text: String, label: String? = nil) throws -> Location {
        var t = text
        var latSign = 1.0, lonSign = 1.0
        if t.contains("南纬") || t.contains("S") { latSign = -1 }
        if t.contains("西经") || t.contains("W") { lonSign = -1 }
        for token in ["北纬", "南纬", "东经", "西经", "N", "S", "E", "W", "°", "，"] { t = t.replacingOccurrences(of: token, with: token == "，" ? "," : " ") }
        let parts = t.split(whereSeparator: { $0 == "," || $0 == " " }).compactMap { Double($0) }
        guard parts.count == 2 else { throw LocationError.unparsable }
        let loc = Location(latitude: abs(parts[0]) * (parts[0] < 0 ? -1 : latSign), longitude: abs(parts[1]) * (parts[1] < 0 ? -1 : lonSign), label: label)
        guard loc.isValid else { throw LocationError.outOfRange }
        return loc
    }
}
