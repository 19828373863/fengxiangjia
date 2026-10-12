import SwiftUI
import FXJCore

struct HomeTabView: View {
    let model: AppModel
    @Binding var selection: AppTab
    @State private var confirmDelete = false
    @State private var exportURL: URL?
    @State private var roomMessage: String?

    var body: some View {
        LoadStateView(state: model.state, onSample: model.openSample, onRetry: model.load, onNew: model.createBlankRoom) { doc in
            List {
                Section(doc.home.name) {
                    ForEach(doc.home.rooms) { room in
                        NavigationLink(value: room.id) {
                            VStack(alignment: .leading) {
                                Text(room.name).font(.headline)
                                Text(String(format: "%.1f m² · %d 扇门窗 · %d 台风扇",
                                            room.area, room.openings.count, room.fans.count))
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                        }
                        .contextMenu {
                            ForEach(Array(room.walls.enumerated()), id: \.offset) { i, w in
                                Button("在\(Self.direction(w.outwardNormal))侧添加房间") {
                                    roomMessage = model.addAdjacentRoom(to: room.id, wallIndex: i)
                                }
                            }
                            if doc.home.rooms.count > 1 {
                                Button("删除此房间", role: .destructive) { model.removeRoom(room.id) }
                            }
                        }
                    }
                    if let roomMessage { Text(roomMessage).font(.caption).foregroundStyle(.orange) }
                    Text("长按房间可在其旁添加相邻房间；在相邻墙上放门即连通两室。").font(.caption2).foregroundStyle(.secondary)
                }
                Section {
                    NavigationLink {
                        HomeSettingsView(model: model, home: doc.home)
                    } label: {
                        LabeledContent("位置与朝向", value: doc.home.location == nil ? "未设置" : "上方朝\(Bearing.name(doc.home.orientationDegrees))")
                    }
                    NavigationLink {
                        ObstructionSettingsView(model: model)
                    } label: {
                        LabeledContent("楼层与窗外遮挡", value: doc.home.floorLevel.map { "\($0) 层" } ?? "未设置")
                    }
                    if let room = doc.home.rooms.first {
                        NavigationLink("编辑户型（手绘）") {
                            FloorplanEditorView(room: room) { edited in model.replaceRoom(edited) }
                        }
                    }
                    NavigationLink {
                        PaywallView(model: model)
                    } label: {
                        LabeledContent("完整版", value: model.entitlement.isUnlocked ? "已解锁" : "免费版")
                    }
                    NavigationLink(RoomScanSupport.isSupported ? "扫描新房间（RoomPlan）" : "扫描新房间（此设备不支持）") {
                        if model.entitlement.allows(.roomScan) || !RoomScanSupport.isSupported {
                            RoomScanView { model.importScannedRoom($0) }
                        } else {
                            LockedFeatureView(model: model, feature: .roomScan)
                        }
                    }
                    Button("去试摆风扇") { selection = .tryout }
                }
                Section {
                    // 点击时才生成导出文件（不在渲染时写盘）
                    if let url = exportURL {
                        ShareLink("分享导出文件", item: url)
                    } else {
                        Button("导出数据（JSON，不含位置）") { exportURL = model.exportFileURL() }
                    }
                    Button("删除全部本地数据", role: .destructive) { confirmDelete = true }
                } header: { Text("数据与隐私") } footer: {
                    Text("所有户型、方案与反馈只保存在本机，无需账户、不上传。")
                }
                if let err = model.saveError { Text(err).foregroundStyle(.red) }
            }
            .navigationDestination(for: UUID.self) { id in
                if let room = doc.home.rooms.first(where: { $0.id == id }) {
                    RoomCanvasView(room: room).padding().navigationTitle(room.name)
                }
            }
        }
        .navigationTitle(AppTab.home.title)
        .confirmationDialog("删除全部本地数据？", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("删除", role: .destructive, action: model.resetToEmpty)
        } message: { Text("户型、方案和体感反馈将被永久删除，无法恢复。") }
    }
}

extension HomeTabView {
    /// 户型坐标方向（+Y 为图上方）。
    static func direction(_ n: Vec2) -> String {
        if abs(n.x) > abs(n.y) { return n.x > 0 ? "右（东）" : "左（西）" }
        return n.y > 0 ? "上（北）" : "下（南）"
    }
}

struct TryoutTabView: View {
    let model: AppModel
    @Binding var selection: AppTab
    @State private var roomId: UUID?

    var body: some View {
        LoadStateView(state: model.state, onSample: model.openSample, onRetry: model.load) { doc in
            if let room = doc.home.rooms.first(where: { $0.id == roomId }) ?? doc.home.rooms.first {
                if doc.home.rooms.count > 1 {
                    Picker("房间", selection: Binding(get: { room.id }, set: { roomId = $0 })) {
                        ForEach(doc.home.rooms) { Text($0.name).tag($0.id) }
                    }
                    .pickerStyle(.segmented).padding(.horizontal)
                }
                WeatherPanelView(model: model).padding(.horizontal)
                TryoutView(room: room, home: doc.home, environment: model.currentEnvironment,
                           recommendationLocked: !model.entitlement.allows(.autoRecommendation),
                           onFeedback: { rating, hash in model.addFeedback(roomId: room.id, scenarioHash: hash, rating: rating) },
                           onSaveScenario: !model.canSaveMoreScenarios ? nil : { r in _ = model.saveScenario(name: "方案 \(doc.scenarios.count + 1)", room: r, environment: model.currentEnvironment) }) { model.updateLayout($0) }
                    .id(room.id)
            } else {
                ContentUnavailableView("没有房间", systemImage: "square.dashed",
                                       description: Text("请先在“我家”中添加房间"))
            }
        }
        .navigationTitle(AppTab.tryout.title)
    }
}

struct PlansTabView: View {
    let model: AppModel
    @State private var picked: [UUID] = []

    var body: some View {
        LoadStateView(state: model.state, onSample: model.openSample, onRetry: model.load) { doc in
            if doc.scenarios.isEmpty {
                ContentUnavailableView("还没有方案", systemImage: "square.split.2x1",
                                       description: Text("在“试摆”中点“保存方案”，保存两个以上即可 A/B 比较。"))
            } else {
                List {
                    Section("选择两个方案比较") {
                        ForEach(doc.scenarios) { s in
                            Button {
                                if let i = picked.firstIndex(of: s.id) { picked.remove(at: i) }
                                else { picked = Array((picked + [s.id]).suffix(2)) }
                            } label: {
                                HStack {
                                    Image(systemName: picked.contains(s.id) ? "checkmark.circle.fill" : "circle")
                                    VStack(alignment: .leading) {
                                        Text(s.name)
                                        Text(s.createdAt, style: .date).font(.caption).foregroundStyle(.secondary)
                                    }
                                    if s.geometryRevision != doc.geometryRevision {
                                        Spacer(); Text("户型已改").font(.caption2).foregroundStyle(.orange)
                                    }
                                }
                            }
                            .foregroundStyle(.primary)
                        }
                        .onDelete { idx in idx.map { doc.scenarios[$0].id }.forEach(model.deleteScenario) }
                    }
                    if picked.count == 2,
                       let a = doc.scenarios.first(where: { $0.id == picked[0] }),
                       let b = doc.scenarios.first(where: { $0.id == picked[1] }) {
                        NavigationLink("比较 \(a.name) 与 \(b.name)") {
                            ComparisonView(home: doc.home, a: a, b: b, geometryRevision: doc.geometryRevision,
                                           environment: a.environmentSnapshot)
                        }
                    }
                }
            }
        }
        .navigationTitle(AppTab.plans.title)
    }
}
