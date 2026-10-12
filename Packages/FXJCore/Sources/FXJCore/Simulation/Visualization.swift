import Foundation

/// 由同一 SimulationResult 派生的可视化几何（流线、箭头、图例），保证“看到的”与“建议”同源。
public struct FlowVisualization: Hashable, Sendable {
    public struct Streamline: Hashable, Sendable {
        public var points: [Vec2]
        public var speeds: [Double]
    }
    public struct Arrow: Hashable, Sendable {
        public var position: Vec2
        public var direction: Vec2
        public var speed: Double
    }

    /// 固定图例范围（m/s）：A/B 比较必须共用，禁止按方案自动缩放。
    public static let legendRange: ClosedRange<Double> = 0...1.5

    public var scenarioHash: String
    public var streamlines: [Streamline]
    public var arrows: [Arrow]

    public static func normalized(_ speed: Double) -> Double {
        min(max((speed - legendRange.lowerBound) / (legendRange.upperBound - legendRange.lowerBound), 0), 1)
    }

    public init?(result: SimulationResult, seedSpacing: Double = 0.4, arrowSpacing: Double = 0.5,
                 minSpeed: Double = 0.05, maxLength: Double = 6) {
        guard let f = result.field else { return nil }
        scenarioHash = result.scenarioHash
        streamlines = Self.streamlines(f, spacing: seedSpacing, minSpeed: minSpeed, maxLength: maxLength)
        arrows = Self.arrows(f, spacing: arrowSpacing, minSpeed: minSpeed)
    }

    static func seeds(_ f: FlowField, spacing: Double) -> [Vec2] {
        var out: [Vec2] = []
        let w = Double(f.nx) * f.cellSize, h = Double(f.ny) * f.cellSize
        var y = spacing / 2
        while y < h {
            var x = spacing / 2
            while x < w {
                let p = f.origin + Vec2(x, y)
                if let (ix, iy) = f.cell(of: p), f.mask[f.index(ix, iy)] { out.append(p) }
                x += spacing
            }
            y += spacing
        }
        return out
    }

    /// 中点法（RK2）积分；出界、进入固体、低速或超长即停止。
    static func streamlines(_ f: FlowField, spacing: Double, minSpeed: Double, maxLength: Double) -> [Streamline] {
        let step = f.cellSize * 0.5
        return seeds(f, spacing: spacing).compactMap { seed in
            var p = seed, pts = [seed], sp: [Double] = []
            guard let v0 = f.sample(seed), v0.length >= minSpeed else { return nil }
            sp.append(v0.length)
            var len = 0.0
            while len < maxLength {
                guard let v1 = f.sample(p), v1.length >= minSpeed else { break }
                let mid = p + v1.normalized * (step / 2)
                guard let v2 = f.sample(mid), v2.length >= minSpeed else { break }
                let next = p + v2.normalized * step
                guard f.sample(next) != nil else { break }
                p = next; len += step
                pts.append(p); sp.append(v2.length)
            }
            return pts.count >= 4 ? Streamline(points: pts, speeds: sp) : nil
        }
    }

    static func arrows(_ f: FlowField, spacing: Double, minSpeed: Double) -> [Arrow] {
        seeds(f, spacing: spacing).compactMap { p in
            guard let v = f.sample(p), v.length >= minSpeed else { return nil }
            return Arrow(position: p, direction: v.normalized, speed: v.length)
        }
    }
}
