import SwiftUI
import FXJCore

/// #16 楼层与每扇外窗的窗外遮挡设置。
struct ObstructionSettingsView: View {
    let model: AppModel

    var body: some View {
        Form {
            if let home = model.document?.home {
                Section {
                    Stepper(value: Binding(get: { home.floorLevel ?? 0 }, set: { model.setFloor($0 <= 0 ? nil : $0) }), in: 0...80) {
                        Text(home.floorLevel.map { "所在楼层：\($0) 层" } ?? "所在楼层：未填写")
                    }
                } footer: { Text("低楼层通常风速较小；未填写时按中间值估计。") }
                Section {
                    let items = ObstructionModel.exteriorOpenings(home)
                    if items.isEmpty {
                        Text("还没有通向室外的门窗。请先在户型编辑器里在外墙上添加窗。").font(.caption)
                    }
                    ForEach(items) { item in
                        Picker(selection: Binding(get: { item.opening.exteriorObstruction ?? .unknown },
                                                  set: { model.setObstruction(openingId: item.opening.id, level: $0) })) {
                            ForEach(ObstructionLevel.allCases, id: \.self) { Text(ObstructionText.name($0)).tag($0) }
                        } label: {
                            VStack(alignment: .leading) {
                                Text("\(item.room.name) · \(item.opening.kind == .window ? "窗" : "门")")
                                if let b = item.bearing { Text("朝\(Bearing.name(b))").font(.caption).foregroundStyle(.secondary) }
                            }
                        }
                    }
                } header: { Text("窗外遮挡") } footer: {
                    Text("简化估计：开阔 100%、部分遮挡/未知 60%、紧邻楼房 35%、天井 25% 的区域风速，再按楼层修正。仅用于相对比较。")
                }
            }
        }
        .navigationTitle("楼层与遮挡")
    }
}
