import SwiftUI
import FXJCore

/// 气流叠加层：流线（动画）或静态箭头，颜色使用固定图例 FlowVisualization.legendRange。
struct FlowOverlayView: View {
    let viz: FlowVisualization
    let transform: FitTransform
    var animated: Bool
    var stale: Bool

    static func color(_ speed: Double) -> Color {
        let t = FlowVisualization.normalized(speed)
        return Color(hue: 0.6 - 0.6 * t, saturation: 0.85, brightness: 0.9)
    }

    var body: some View {
        TimelineView(.animation(minimumInterval: 1 / 30, paused: !animated || stale)) { ctx in
            let phase = animated ? ctx.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 10) : 0
            Canvas { g, _ in
                if animated {
                    for line in viz.streamlines {
                        var p = Path()
                        p.move(to: transform.toScreen(line.points[0]))
                        line.points.dropFirst().forEach { p.addLine(to: transform.toScreen($0)) }
                        let mean = line.speeds.reduce(0, +) / Double(max(line.speeds.count, 1))
                        g.stroke(p, with: .color(Self.color(mean).opacity(stale ? 0.25 : 0.8)),
                                 style: StrokeStyle(lineWidth: 1.6, lineCap: .round, dash: [6, 8],
                                                    dashPhase: -phase * 40 * max(mean, 0.2)))
                    }
                } else {
                    for a in viz.arrows {
                        let s = transform.toScreen(a.position)
                        let len = 8 + 14 * FlowVisualization.normalized(a.speed)
                        let d = CGPoint(x: a.direction.x * len, y: -a.direction.y * len)
                        var p = Path()
                        p.move(to: CGPoint(x: s.x - d.x / 2, y: s.y - d.y / 2))
                        let tip = CGPoint(x: s.x + d.x / 2, y: s.y + d.y / 2)
                        p.addLine(to: tip)
                        let ang = atan2(d.y, d.x)
                        for off in [2.6, -2.6] {
                            p.move(to: tip)
                            p.addLine(to: CGPoint(x: tip.x - 5 * cos(ang + off / 6 * .pi / 2), y: tip.y - 5 * sin(ang + off / 6 * .pi / 2)))
                        }
                        g.stroke(p, with: .color(Self.color(a.speed).opacity(stale ? 0.25 : 0.9)), lineWidth: 1.5)
                    }
                }
            }
        }
    }
}

/// 固定图例。
struct FlowLegendView: View {
    var body: some View {
        HStack(spacing: 6) {
            Text("0").font(.caption2)
            LinearGradient(colors: stride(from: 0.0, through: 1.0, by: 0.25).map {
                FlowOverlayView.color($0 * FlowVisualization.legendRange.upperBound)
            }, startPoint: .leading, endPoint: .trailing)
            .frame(height: 6).clipShape(Capsule())
            Text("≥\(FlowVisualization.legendRange.upperBound, specifier: "%.1f") m/s").font(.caption2)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("风速图例，0 到 \(FlowVisualization.legendRange.upperBound, specifier: "%.1f") 米每秒")
    }
}

enum ExplanationText {
    static func text(_ e: Explanation, room: Room) -> String {
        func name(_ id: UUID) -> String { room.targets.first { $0.id == id }?.name ?? "目标区域" }
        switch e {
        case .closedRoomInternalOnly: return "门窗都关着：风扇只让室内空气循环，不会增加与室外的换气。"
        case .singleSidedOpening: return "只有一侧开口，换气有限；再开一扇对侧门窗可形成穿堂风。"
        case .crossVentilation: return "迎风与背风开口形成穿堂风。"
        case .noEnvironment: return "未设置外部风况，换气只按开口情况粗略判断。"
        case .staleEnvironment: return "天气数据已过期，结果仅供参考。"
        case .fanBlocked: return "风扇正前方被墙或家具挡住，建议调整朝向或位置。"
        case .targetCovered(let id): return "“\(name(id))”能吹到风。"
        case .targetMissed(let id): return "“\(name(id))”风较弱，可让风扇更靠近或对准它。"
        case .targetDraft(let id): return "“\(name(id))”有明显直吹，可降低档位或偏转风扇。"
        case .fanAidsExhaust: return "风扇靠近开口朝外吹，有助于把室内空气排出。"
        }
    }

    static func coverage(_ c: Double) -> String {
        level(c >= 0.6 ? .high : c >= 0.3 ? .medium : c > 0 ? .low : .none)
    }

    static func level(_ l: Level) -> String {
        switch l { case .none: return "无"; case .low: return "弱"; case .medium: return "中"; case .high: return "强" }
    }
}
