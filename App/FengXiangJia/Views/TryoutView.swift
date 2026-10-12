import SwiftUI
import FXJCore

/// 试摆：拖动风扇与目标区域，调整朝向、档位、类型（#7）。
struct TryoutView: View {
    @State private var editor: RoomEditor
    @State private var selected: UUID?
    @State private var message: String?
    @State private var result: SimulationResult?
    @State private var resultInput: SimulationInput?
    @State private var viz: FlowVisualization?
    @State private var computing = false
    @State private var staticArrows = false
    @SwiftUI.Environment(\.accessibilityReduceMotion) private var reduceMotion
    @SwiftUI.Environment(\.scenePhase) private var scenePhase
    let home: Home
    let environment: OutdoorConditions?
    var recommendationLocked = false
    let onChange: (Room) -> Void
    var onSaveScenario: ((Room) -> Void)? = nil
    var onFeedback: ((ComfortRating, String?) -> Void)? = nil
    @State private var savedToast = false
    @State private var feedbackToast: String?
    @State private var exportImage: Image?
    @State private var showRecommend = false

    init(room: Room, home: Home, environment: OutdoorConditions? = nil, recommendationLocked: Bool = false,
         onFeedback: ((ComfortRating, String?) -> Void)? = nil,
         onSaveScenario: ((Room) -> Void)? = nil, onChange: @escaping (Room) -> Void) {
        self.onFeedback = onFeedback
        self.onSaveScenario = onSaveScenario
        _editor = State(initialValue: RoomEditor(room: room))
        self.home = home
        self.environment = environment
        self.recommendationLocked = recommendationLocked
        self.onChange = onChange
    }

    private var input: SimulationInput {
        var h = home
        if let i = h.rooms.firstIndex(where: { $0.id == editor.room.id }) { h.rooms[i] = editor.room }
        return SimulationInput(home: h, roomId: editor.room.id, environment: environment)
    }

    private var foreignOpenings: [(Vec2, Vec2)] {
        input.home.openingsAffecting(editor.room.id).compactMap { item in
            item.host.id == editor.room.id ? nil : item.host.segment(of: item.opening)
        }
    }

    private var inputHash: String { ScenarioHash.compute(input) }
    private var isStale: Bool { result.map { !$0.isCurrent(for: input) } ?? true }
    private var animate: Bool {
        !staticArrows && !reduceMotion && scenePhase == .active && !ProcessInfo.processInfo.isLowPowerModeEnabled
    }

    private var selectedFan: Fan? { editor.room.fans.first { $0.id == selected } }
    private var selectedTarget: Target? { editor.room.targets.first { $0.id == selected } }

    var body: some View {
        VStack(spacing: 8) {
            RoomCanvasView(room: editor.room, selectedId: selected,
                           onFanMove: { id, p in run { try editor.moveFan(id, to: p) } },
                           onTargetMove: { id, p in run { try editor.moveTarget(id, to: p) } },
                           onSelect: { selected = $0 },
                           onDragEnd: { onChange(editor.room) }) { t in
                if let viz { FlowOverlayView(viz: viz, transform: t, animated: animate, stale: isStale) }
            }
            .foreignOpenings(foreignOpenings)
            .overlay(alignment: .topTrailing) {
                if computing || (result != nil && isStale) {
                    Label(computing ? "计算中…" : "待更新" + staleReasonText, systemImage: "arrow.triangle.2.circlepath")
                        .font(.caption).padding(6).background(.thinMaterial, in: Capsule())
                }
            }
            HStack {
                FlowLegendView()
                Toggle("静态箭头", isOn: $staticArrows).toggleStyle(.button).font(.caption)
                if let onSaveScenario {
                    Button(savedToast ? "已保存" : "保存方案") { onSaveScenario(editor.room); savedToast = true }
                        .buttonStyle(.bordered).font(.caption)
                }
            }
            resultSummary
            HStack {
                if let onFeedback {
                    Menu("体感反馈") {
                        ForEach(ComfortRating.allCases, id: \.self) { r in
                            Button(Self.ratingName(r)) {
                                onFeedback(r, isStale ? nil : result?.scenarioHash)
                                feedbackToast = "已记录：\(Self.ratingName(r))"
                            }
                        }
                    }
                    .font(.caption)
                }
                if let feedbackToast { Text(feedbackToast).font(.caption2).foregroundStyle(.secondary) }
                Spacer()
                if let exportImage {
                    ShareLink(item: exportImage, preview: SharePreview("风向家方案", image: exportImage)) {
                        Label("分享图片", systemImage: "square.and.arrow.up")
                    }.font(.caption)
                } else {
                    Button("生成图片") { renderImage() }.font(.caption)
                }
            }
            if let m = message { Text(m).font(.footnote).foregroundStyle(.orange) }
            inspector
        }
        .padding(.horizontal)
        .sheet(isPresented: $showRecommend) {
            if recommendationLocked {
                ContentUnavailableView("自动推荐属于完整版", systemImage: "lock",
                                       description: Text("可在“我家 › 完整版”一次性购买；手动试摆不受影响。"))
            } else {
            RecommendationView(home: input.home, roomId: editor.room.id, fanId: selectedFan?.id, environment: environment) { fan in
                commit {
                    try editor.moveFan(fan.id, to: fan.position)
                    try editor.setFanYaw(fan.id, degrees: fan.yawDegrees)
                }
            }
            }
        }
        .task(id: inputHash) { savedToast = false; exportImage = nil; await recompute() }
        .toolbar {
            ToolbarItemGroup(placement: .topBarLeading) {
                Button { editor.undo(); onChange(editor.room) } label: { Image(systemName: "arrow.uturn.backward") }
                    .disabled(!editor.canUndo).accessibilityLabel("撤销")
                Button { editor.redo(); onChange(editor.room) } label: { Image(systemName: "arrow.uturn.forward") }
                    .disabled(!editor.canRedo).accessibilityLabel("重做")
            }
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Button("推荐摆法") { showRecommend = true }.disabled(editor.room.fans.isEmpty)
                    Button("添加风扇") { add { try editor.placeFan(at: freeSpot()) } }
                    Button("添加“希望有风”区域") { add { try editor.addTarget(name: "希望有风", at: centroid, objective: .wantBreeze) } }
                    Button("添加“减少直吹”区域") { add { try editor.addTarget(name: "减少直吹", at: centroid, objective: .avoidDirectDraft) } }
                } label: { Image(systemName: "plus") }
                .accessibilityLabel("添加")
            }
        }
    }

    @ViewBuilder private var inspector: some View {
        if let fan = selectedFan {
            VStack(alignment: .leading, spacing: 6) {
                Picker("类型", selection: Binding(get: { fan.kind }, set: { k in commit { try editor.setFanKind(fan.id, k) } })) {
                    ForEach(FanKind.allCases, id: \.self) { Text(Self.kindName($0)).tag($0) }
                }
                .pickerStyle(.menu)
                LabeledContent("朝向 \(Int(fan.yawDegrees))°") {
                    Slider(value: Binding(get: { fan.yawDegrees }, set: { v in run { try editor.setFanYaw(fan.id, degrees: v) } }),
                           in: 0...355, step: 5, onEditingChanged: { if !$0 { onChange(editor.room) } })
                }
                LabeledContent("档位 \(Int(fan.power * 100))%") {
                    Slider(value: Binding(get: { fan.power }, set: { v in run { try editor.setFanPower(fan.id, v) } }),
                           in: 0...1, step: 0.1, onEditingChanged: { if !$0 { onChange(editor.room) } })
                }
                Button("删除风扇", role: .destructive) { commit { try editor.removeItem(fan.id) }; selected = nil }
            }
        } else if let t = selectedTarget {
            VStack(alignment: .leading, spacing: 6) {
                Picker("目标", selection: Binding(get: { t.objective }, set: { o in commit { try editor.setTargetObjective(t.id, o) } })) {
                    Text("希望有风").tag(TargetObjective.wantBreeze)
                    Text("减少直吹").tag(TargetObjective.avoidDirectDraft)
                }
                .pickerStyle(.segmented)
                Button("删除区域", role: .destructive) { commit { try editor.removeItem(t.id) }; selected = nil }
            }
        } else {
            Text("点选风扇或目标区域以调整；拖动可移动。风扇需离墙 0.2 m 且不能压在高家具上。")
                .font(.footnote).foregroundStyle(.secondary)
        }
    }

    @ViewBuilder private var resultSummary: some View {
        if let r = result, !isStale, let m = r.metrics {
            VStack(alignment: .leading, spacing: 2) {
                Text("室内循环 \(ExplanationText.coverage(m.circulationCoverage)) · 对外换气 \(ExplanationText.level(m.outdoorExchange))")
                    .font(.footnote.bold())
                ForEach(Array(r.explanations.prefix(3).enumerated()), id: \.offset) { _, e in
                    Text("• " + ExplanationText.text(e, room: editor.room)).font(.caption)
                }
                Text("简化示意模型（\(r.modelVersion)），非工程级计算").font(.caption2).foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        } else if case .invalidInput? = result?.status {
            Text("房间形状有误，无法计算").font(.footnote).foregroundStyle(.red)
        }
    }

    private func recompute() async {
        try? await Task.sleep(nanoseconds: 250_000_000) // 拖动防抖
        guard !Task.isCancelled else { return }
        computing = true
        let inp = input
        let (r, v) = await Task.detached(priority: .userInitiated) { () -> (SimulationResult, FlowVisualization?) in
            let r = AirflowSolver.solve(inp)
            return (r, FlowVisualization(result: r))
        }.value
        computing = false
        guard !Task.isCancelled else { return }
        result = r; viz = v; resultInput = inp
    }

    /// #18：说明结果为何过期（天气/朝向/遮挡/开口…）。
    private var staleReasonText: String {
        guard let r = result, let old = resultInput else { return "" }
        let rs = Staleness.reasons(result: r, oldInput: old, newInput: input)
        return rs.isEmpty ? "" : "（" + StaleReason.allCases.filter(rs.contains).map(Self.reasonName).joined(separator: "、") + "已变）"
    }

    static func reasonName(_ r: StaleReason) -> String {
        switch r {
        case .weather: return "天气"
        case .orientation: return "朝向"
        case .floor: return "楼层"
        case .obstruction: return "窗外遮挡"
        case .openings: return "门窗"
        case .geometry: return "户型"
        case .layout: return "风扇/目标"
        case .model: return "模型"
        case .options: return "计算参数"
        }
    }

    static func ratingName(_ r: ComfortRating) -> String {
        switch r {
        case .tooWeak: return "风太弱"
        case .justRight: return "刚好"
        case .tooStrong: return "风太大/直吹"
        case .stuffy: return "闷"
        }
    }

    /// 以静态箭头离屏渲染当前方案（与屏幕上同一结果）。
    @MainActor private func renderImage() {
        let room = editor.room, v = viz, stale = isStale
        let content = VStack(spacing: 6) {
            RoomCanvasView(room: room) { t in
                if let v { FlowOverlayView(viz: v, transform: t, animated: false, stale: stale) }
            }
            FlowLegendView()
            Text("风向家 · 简化示意，非工程计算").font(.caption2)
        }
        .padding(16).frame(width: 420).background(Color.white)
        let renderer = ImageRenderer(content: content)
        renderer.scale = 2
        if let ui = renderer.uiImage { exportImage = Image(uiImage: ui) }
    }

    static func kindName(_ k: FanKind) -> String {
        switch k {
        case .pedestal: return "落地扇"
        case .circulator: return "循环扇"
        case .desk: return "台扇"
        case .tower: return "塔扇"
        case .ceiling: return "吊扇"
        }
    }

    private var centroid: Vec2 {
        let p = editor.room.polygon
        return p.reduce(Vec2.zero, +) * (1 / Double(max(p.count, 1)))
    }

    private func freeSpot() -> Vec2 {
        let c = centroid
        if FanPlacement.check(c, in: editor.room) == nil { return c }
        for dx in stride(from: -2.0, through: 2.0, by: 0.25) {
            for dy in stride(from: -2.0, through: 2.0, by: 0.25) where FanPlacement.check(c + Vec2(dx, dy), in: editor.room) == nil {
                return c + Vec2(dx, dy)
            }
        }
        return c
    }

    private func add(_ action: () throws -> UUID) {
        do { selected = try action(); message = nil; onChange(editor.room) }
        catch { message = Self.describe(error) }
    }

    private func run(_ action: () throws -> Void) {
        do { try action(); message = nil } catch { message = Self.describe(error) }
    }

    private func commit(_ action: () throws -> Void) {
        run(action); onChange(editor.room)
    }

    static func describe(_ error: Error) -> String {
        switch error as? EditError {
        case .outsideRoom?: return "请放在房间内"
        case .tooCloseToWall?: return "离墙太近（至少 0.2 m）"
        case .onFurniture?: return "不能放在高家具上"
        default: return "无法完成该操作"
        }
    }
}
