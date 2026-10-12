import SwiftUI
import FXJCore

struct HomeTabView: View {
    let model: AppModel
    @Binding var selection: AppTab

    var body: some View {
        LoadStateView(state: model.state, onSample: model.openSample, onRetry: model.load) { doc in
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
                    }
                }
                Section {
                    Button("去试摆风扇") { selection = .tryout }
                    Button("重置为空（删除本地数据）", role: .destructive, action: model.resetToEmpty)
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
    }
}

struct TryoutTabView: View {
    let model: AppModel
    @Binding var selection: AppTab

    var body: some View {
        LoadStateView(state: model.state, onSample: model.openSample, onRetry: model.load) { doc in
            if let room = doc.home.rooms.first {
                VStack(spacing: 12) {
                    RoomCanvasView(room: room) { fanId, p in
                        model.moveFan(roomId: room.id, fanId: fanId, to: p)
                    } onDragEnd: {
                        model.persistCurrent()
                    }
                    Text("拖动风扇改变位置。气流模拟将在后续版本加入（#8）。")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                .padding()
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

    var body: some View {
        LoadStateView(state: model.state, onSample: model.openSample, onRetry: model.load) { doc in
            if doc.scenarios.isEmpty {
                ContentUnavailableView("还没有方案", systemImage: "square.split.2x1",
                                       description: Text("在“试摆”中保存方案后，可在这里做 A/B 比较（#10）。"))
            } else {
                List(doc.scenarios) { s in Text(s.name) }
            }
        }
        .navigationTitle(AppTab.plans.title)
    }
}
