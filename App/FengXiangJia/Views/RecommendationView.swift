import SwiftUI
import FXJCore

/// #13 推荐摆法：三种候选，图示与说明来自同一 SimulationResult。
struct RecommendationView: View {
    let home: Home
    let roomId: UUID
    let fanId: UUID?
    let environment: OutdoorConditions?
    let onApply: (Fan) -> Void
    @State private var candidates: [LayoutSearch.Candidate]?
    @SwiftUI.Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Group {
                if let candidates {
                    if candidates.isEmpty {
                        ContentUnavailableView("无法推荐", systemImage: "fan.slash", description: Text("请先在房间里放一台风扇"))
                    } else {
                        List(candidates, id: \.kind) { c in
                            VStack(alignment: .leading, spacing: 6) {
                                Text(Self.title(c.kind)).font(.headline)
                                RoomCanvasView(room: c.room, selectedId: c.fan.id) { t in
                                    if let viz = FlowVisualization(result: c.result) {
                                        FlowOverlayView(viz: viz, transform: t, animated: false, stale: false)
                                    }
                                }
                                .frame(maxHeight: 220)
                                Text("目标处有风 \(ExplanationText.level(c.breeze)) · 直吹 \(ExplanationText.level(c.draft)) · 调整 \(ExplanationText.level(c.cost))")
                                    .font(.caption)
                                Text(c.isCurrent ? "当前摆法已经合适，无需调整。" : String(format: "移到 (%.1f, %.1f) m，朝向 %d°", c.fan.position.x, c.fan.position.y, Int(c.fan.yawDegrees)))
                                    .font(.caption).foregroundStyle(.secondary)
                                if !c.isCurrent {
                                    Button("采用此方案") { onApply(c.fan); dismiss() }.buttonStyle(.borderedProminent)
                                }
                            }
                            .padding(.vertical, 4)
                        }
                    }
                } else {
                    ProgressView("正在搜索可行摆法…")
                }
            }
            .navigationTitle("推荐摆法")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("关闭") { dismiss() } } }
            .task {
                let (h, r, f, e) = (home, roomId, fanId, environment)
                candidates = await Task.detached(priority: .userInitiated) {
                    LayoutSearch.search(home: h, roomId: r, fanId: f, environment: e)
                }.value
            }
        }
    }

    static func title(_ k: LayoutSearch.Kind) -> String {
        switch k {
        case .leastEffort: return "最省事"
        case .strongerBreeze: return "送风更强"
        case .lessDraft: return "少直吹"
        }
    }
}
