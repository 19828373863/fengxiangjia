import SwiftUI
import FXJCore

/// 试摆：拖动风扇与目标区域，调整朝向、档位、类型（#7）。
struct TryoutView: View {
    @State private var editor: RoomEditor
    @State private var selected: UUID?
    @State private var message: String?
    @State private var result: SimulationResult?
    @State private var viz: FlowVisualization?
    @State private var computing = false
    @State private var staticArrows = false
    @SwiftUI.Environment(\.accessibilityReduceMotion) private var reduceMotion
    @SwiftUI.Environment(\.scenePhase) private var scenePhase
    let home: Home
    let environment: OutdoorConditions?
    let onChange: (Room) -> Void
    var onSaveScenario: ((Room) -> Void)? = nil
    @State private var savedToast = false

    init(room: Room, home: Home, environment: OutdoorConditions? = nil,
         onSaveScenario: ((Room) -> Void)? = nil, onChange: @escaping (Room) -> Void) {
        self.onSaveScenario = onSaveScenario
        _editor = State(initialValue: RoomEditor(room: room))
        self.home = home
        self.environment = environment
        self.onChange = onChange
    }

    private var input: SimulationInput {
        var h = home
        if let i = h.rooms.firstIndex(where: { $0.id == editor.room.id }) { h.rooms[i] = editor.room }
        return SimulationInput(home: h, roomId: editor.room.id, environment: environment)
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
            .overlay(alignment: .topTrailing) {
                if computing || (result != nil && isStale) {
                    Label(computing ? "计算中…" : "待更新", systemImage: "arrow.triangle.2.circlepath")
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
            if let m = message { Text(m).font(.footnote).foregroundStyle(.orange) }
            inspector
        }
        .padding(.horizontal)
        .task(id: inputHash) { savedToast = false; await recompute() }
        .toolbar {
            ToolbarItemGroup(placement: .topBarLeading) {
                Button { editor.undo(); onChange(editor.room) } label: { Image(systemName: "arrow.uturn.backward") }
                    .disabled(!editor.canUndo).accessibilityLabel("撤销")
                Button { editor.redo(); onChange(editor.room) } label: { Image(systemName: "arrow.uturn.forward") }
                    .disabled(!editor.canRedo).accessibilityLabel("重做")
            }
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
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
        result = r; viz = v
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
