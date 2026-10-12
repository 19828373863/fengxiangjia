import SwiftUI
import FXJCore

/// A/B 方案比较：同一环境快照、同一图例，定性比较（#10）。
struct ComparisonView: View {
    let home: Home
    let a: Scenario
    let b: Scenario
    let geometryRevision: Int
    let environment: OutdoorConditions?
    @State private var comparison: ScenarioComparison?

    var body: some View {
        ScrollView {
            if let c = comparison, let room = home.rooms.first(where: { $0.id == a.roomId }) {
                VStack(alignment: .leading, spacing: 12) {
                    HStack(alignment: .top, spacing: 8) {
                        panel("A · \(a.name)", room: a.applied(to: room), result: c.resultA)
                        panel("B · \(b.name)", room: b.applied(to: room), result: c.resultB)
                    }
                    FlowLegendView()
                    Text("两方案使用同一外部条件与同一图例").font(.caption).foregroundStyle(.secondary)
                    Grid(alignment: .leading, horizontalSpacing: 12, verticalSpacing: 6) {
                        GridRow { Text("项目").bold(); Text("A").bold(); Text("B").bold(); Text("较好").bold() }
                        ForEach(c.dimensions, id: \.kind) { d in
                            GridRow {
                                Text(Self.name(d.kind))
                                Text(ExplanationText.level(d.levelA))
                                Text(ExplanationText.level(d.levelB))
                                Text(d.preferred == .similar ? "相近" : d.preferred == .a ? "A" : "B")
                            }
                        }
                    }
                    .font(.callout)
                    ForEach(Array(c.notes.enumerated()), id: \.offset) { _, n in
                        Label(Self.note(n), systemImage: "info.circle").font(.caption)
                    }
                    if !c.suggestions.isEmpty {
                        Text("建议").font(.headline)
                        ForEach(Array(c.suggestions.enumerated()), id: \.offset) { i, s in
                            Text("\(i + 1). \(Self.suggestion(s, a: a.name, b: b.name))").font(.callout)
                        }
                    }
                    Text("简化示意模型，只做相对比较，不代表精确风速。").font(.caption2).foregroundStyle(.secondary)
                }
                .padding()
            } else {
                ProgressView("比较中…").padding(.top, 80)
            }
        }
        .navigationTitle("A/B 比较")
        .task {
            let (h, a, b, rev, env) = (home, a, b, geometryRevision, environment)
            comparison = await Task.detached {
                ScenarioComparison.compare(home: h, roomId: a.roomId, a: a, b: b, currentGeometryRevision: rev, environment: env)
            }.value
        }
    }

    private func panel(_ title: String, room: Room, result: SimulationResult) -> some View {
        VStack(spacing: 4) {
            Text(title).font(.caption.bold()).lineLimit(1)
            RoomCanvasView(room: room) { t in
                if let viz = FlowVisualization(result: result) {
                    FlowOverlayView(viz: viz, transform: t, animated: false, stale: false)
                }
            }
        }
    }

    static func name(_ k: ComparisonDimension.Kind) -> String {
        switch k {
        case .targetBreeze: return "目标处有风"
        case .directDraft: return "直吹风险"
        case .outdoorExchange: return "对外换气"
        case .circulation: return "室内循环"
        case .adjustmentCost: return "调整成本"
        }
    }

    static func note(_ n: ComparisonNote) -> String {
        switch n {
        case .environmentUnified: return "两方案保存时的天气不同，已统一按同一条件比较。"
        case .geometryChanged(let p): return "方案 \(p == .a ? "A" : "B") 保存后户型有修改，已按当前户型重新计算。"
        case .closedRoomNoExchange: return "门窗均关闭：风扇只改变室内循环，不增加对外换气。"
        case .invalid(let p): return "方案 \(p == .a ? "A" : "B") 无法计算（房间形状有误）。"
        }
    }

    static func suggestion(_ s: Suggestion, a: String, b: String) -> String {
        func n(_ p: Preference) -> String { p == .a ? "A（\(a)）" : "B（\(b)）" }
        switch s {
        case .preferForBreeze(let p): return "想让目标处有风，选 \(n(p))。"
        case .reduceDraft(let p): return "想减少直吹，选 \(n(p))。"
        case .openOppositeWindow: return "两方案都没有穿堂风，可尝试打开对侧门窗。"
        case .similarChooseCheaper: return "两方案效果相近，选调整更少的即可。"
        case .aimFanAtTarget: return "两方案目标处风都偏弱，可把风扇移近或对准目标。"
        }
    }
}
