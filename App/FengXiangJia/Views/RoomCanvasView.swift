import SwiftUI
import FXJCore

/// 户型坐标（米，+Y 向上）与屏幕坐标（点，+Y 向下）的适配变换。
struct FitTransform {
    let scale: Double
    let origin: CGPoint
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

struct RoomCanvasView<Overlay: View>: View {
    let room: Room
    var selectedId: UUID? = nil
    var onFanMove: ((UUID, Vec2) -> Void)? = nil
    var onTargetMove: ((UUID, Vec2) -> Void)? = nil
    var onSelect: ((UUID?) -> Void)? = nil
    var onDragEnd: (() -> Void)? = nil
    /// 叠加层（如气流），在房间底图之上、交互手柄之下，接收坐标变换。
    var overlay: (FitTransform) -> Overlay

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
                    ctx.fill(path, with: .color(.blue.opacity(0.05)))
                    for ob in room.obstacles {
                        let c = t.toScreen(ob.center)
                        let r = CGRect(x: -ob.size.x * t.scale / 2, y: -ob.size.y * t.scale / 2,
                                       width: ob.size.x * t.scale, height: ob.size.y * t.scale)
                        var g = ctx
                        g.translateBy(x: c.x, y: c.y)
                        g.rotate(by: .degrees(-ob.rotationDegrees))
                        g.fill(Path(roundedRect: r, cornerRadius: 4), with: .color(.gray.opacity(0.3)))
                    }
                    ctx.stroke(path, with: .color(.primary), lineWidth: 4)
                    for o in room.openings {
                        guard let (a, b) = room.segment(of: o) else { continue }
                        var p = Path(); p.move(to: t.toScreen(a)); p.addLine(to: t.toScreen(b))
                        ctx.stroke(p, with: .color(o.kind == .window ? .cyan : .orange), lineWidth: 7)
                    }
                }
                overlay(t).allowsHitTesting(false)
                ForEach(room.targets) { target in
                    let rr = targetRadius(target) * t.scale
                    Circle()
                        .strokeBorder(target.objective == .wantBreeze ? Color.green : Color.red,
                                      style: StrokeStyle(lineWidth: selectedId == target.id ? 3 : 2, dash: [5, 4]))
                        .background(Circle().fill((target.objective == .wantBreeze ? Color.green : Color.red).opacity(0.08)))
                        .frame(width: rr * 2, height: rr * 2)
                        .position(t.toScreen(target.center))
                        .accessibilityLabel(target.objective == .wantBreeze ? "希望有风区域" : "减少直吹区域")
                        .onTapGesture { onSelect?(target.id) }
                        .gesture(DragGesture()
                            .onChanged { v in onTargetMove?(target.id, t.toWorld(v.location)) }
                            .onEnded { _ in onDragEnd?() })
                }
                ForEach(room.fans) { fan in
                    Image(systemName: "fan.fill")
                        .font(.title)
                        .foregroundStyle(selectedId == fan.id ? .blue : .teal)
                        .overlay(alignment: .trailing) {
                            Image(systemName: "arrowtriangle.right.fill").font(.caption2).offset(x: 12)
                        }
                        .rotationEffect(.degrees(-fan.yawDegrees))
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                        .position(t.toScreen(fan.position))
                        .accessibilityLabel("风扇，朝向 \(Int(fan.yawDegrees)) 度")
                        .onTapGesture { onSelect?(fan.id) }
                        .gesture(DragGesture()
                            .onChanged { v in onFanMove?(fan.id, t.toWorld(v.location)) }
                            .onEnded { _ in onDragEnd?() })
                }
            }
            .contentShape(Rectangle())
            .onTapGesture { onSelect?(nil) }
        }
        .aspectRatio(1, contentMode: .fit)
    }

    private func targetRadius(_ t: Target) -> Double {
        if case .circle(_, let r) = t.shape { return r }
        return 0.5
    }
}

extension RoomCanvasView where Overlay == EmptyView {
    init(room: Room, selectedId: UUID? = nil, onFanMove: ((UUID, Vec2) -> Void)? = nil,
         onTargetMove: ((UUID, Vec2) -> Void)? = nil, onSelect: ((UUID?) -> Void)? = nil, onDragEnd: (() -> Void)? = nil) {
        self.init(room: room, selectedId: selectedId, onFanMove: onFanMove, onTargetMove: onTargetMove,
                  onSelect: onSelect, onDragEnd: onDragEnd, overlay: { _ in EmptyView() })
    }
}
