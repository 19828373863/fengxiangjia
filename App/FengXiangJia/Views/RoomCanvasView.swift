import SwiftUI
import FXJCore

/// 户型坐标（米，+Y 向上）与屏幕坐标（点，+Y 向下）的适配变换。
struct FitTransform {
    let scale: Double
    let origin: CGPoint // 世界 (minX, maxY) 对应的屏幕点
    let minX: Double, maxY: Double

    init(polygon: [Vec2], size: CGSize, padding: Double = 24) {
        let xs = polygon.map(\.x), ys = polygon.map(\.y)
        minX = xs.min() ?? 0; maxY = ys.max() ?? 0
        let w = max((xs.max() ?? 1) - minX, 0.1), h = max(maxY - (ys.min() ?? 0), 0.1)
        scale = max(min((size.width - 2 * padding) / w, (size.height - 2 * padding) / h), 1)
        origin = CGPoint(x: (size.width - w * scale) / 2, y: (size.height - h * scale) / 2)
    }

    func toScreen(_ p: Vec2) -> CGPoint {
        CGPoint(x: origin.x + (p.x - minX) * scale, y: origin.y + (maxY - p.y) * scale)
    }

    func toWorld(_ s: CGPoint) -> Vec2 {
        Vec2((s.x - origin.x) / scale + minX, maxY - (s.y - origin.y) / scale)
    }
}

struct RoomCanvasView: View {
    let room: Room
    var onFanMove: ((UUID, Vec2) -> Void)? = nil
    var onDragEnd: (() -> Void)? = nil

    var body: some View {
        GeometryReader { geo in
            let t = FitTransform(polygon: room.polygon, size: geo.size)
            ZStack {
                Canvas { ctx, _ in
                    var path = Path()
                    if let first = room.polygon.first {
                        path.move(to: t.toScreen(first))
                        room.polygon.dropFirst().forEach { path.addLine(to: t.toScreen($0)) }
                        path.closeSubpath()
                    }
                    ctx.fill(path, with: .color(.blue.opacity(0.06)))
                    ctx.stroke(path, with: .color(.primary), lineWidth: 4)

                    for ob in room.obstacles {
                        let c = t.toScreen(ob.center)
                        let r = CGRect(x: c.x - ob.size.x * t.scale / 2, y: c.y - ob.size.y * t.scale / 2,
                                       width: ob.size.x * t.scale, height: ob.size.y * t.scale)
                        ctx.fill(Path(roundedRect: r, cornerRadius: 4), with: .color(.gray.opacity(0.3)))
                    }
                    for target in room.targets {
                        if case .circle(let center, let radius) = target.shape {
                            let c = t.toScreen(center), rr = radius * t.scale
                            ctx.stroke(Path(ellipseIn: CGRect(x: c.x - rr, y: c.y - rr, width: rr * 2, height: rr * 2)),
                                       with: .color(.green), style: StrokeStyle(lineWidth: 2, dash: [5, 4]))
                        }
                    }
                    for o in room.openings {
                        guard let (a, b) = room.segment(of: o) else { continue }
                        var p = Path(); p.move(to: t.toScreen(a)); p.addLine(to: t.toScreen(b))
                        ctx.stroke(p, with: .color(o.kind == .window ? .cyan : .orange), lineWidth: 7)
                    }
                }
                ForEach(room.fans) { fan in
                    let s = t.toScreen(fan.position)
                    Image(systemName: "fan.fill")
                        .font(.title)
                        .foregroundStyle(.teal)
                        .rotationEffect(.degrees(-fan.yawDegrees))
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                        .position(s)
                        .accessibilityLabel("风扇")
                        .gesture(
                            DragGesture()
                                .onChanged { v in onFanMove?(fan.id, t.toWorld(v.location)) }
                                .onEnded { _ in onDragEnd?() }
                        )
                }
            }
        }
        .aspectRatio(1, contentMode: .fit)
    }
}
