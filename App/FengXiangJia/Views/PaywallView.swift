import SwiftUI
import FXJCore

/// 完整版购买/恢复页（#19）。一次性买断，无订阅、无广告、无账号。
struct PaywallView: View {
    let model: AppModel
    @State private var price: String?
    @State private var busy = false

    var body: some View {
        List {
            Section {
                Label(model.entitlement.isUnlocked ? "已解锁完整版" : "免费版", systemImage: model.entitlement.isUnlocked ? "checkmark.seal.fill" : "seal")
                    .font(.headline)
            }
            Section("免费即可使用") {
                Text("手绘 1 个房间、试摆风扇、气流示意、A/B 比较、导出、手动风向，最多保存 \(FreeLimits.scenarios) 个方案")
            }
            Section("完整版解锁") {
                ForEach(PaidFeature.allCases, id: \.self) { Label(Self.name($0), systemImage: "plus.circle") }
            }
            if !model.entitlement.isUnlocked {
                Section {
                    Button(busy ? "处理中…" : "购买完整版\(price.map { "（\($0)）" } ?? "")") {
                        Task { busy = true; await model.purchaseFullVersion(); busy = false }
                    }
                    .disabled(busy)
                }
            }
            Section {
                Button("恢复购买") { Task { busy = true; await model.restorePurchases(); busy = false } }.disabled(busy)
            } footer: {
                if let m = model.storeMessage { Text(Self.message(m)) }
            }
        }
        .navigationTitle("完整版")
        .task { price = await model.fullVersionPrice() }
    }

    static func name(_ f: PaidFeature) -> String {
        switch f {
        case .multiRoom: return "多房间连通"
        case .autoRecommendation: return "自动推荐风扇位置"
        case .liveWeather: return "当地天气自动风向"
        case .roomScan: return "摄像头扫描房间（LiDAR 机型）"
        case .unlimitedScenarios: return "不限数量的方案"
        }
    }

    static func message(_ m: EntitlementManager.Message) -> String {
        switch m {
        case .none: return ""
        case .unlocked: return "感谢购买，已解锁全部功能。"
        case .cancelled: return "已取消，未扣费。"
        case .pending: return "购买待批准（如家长批准），批准后自动解锁。"
        case .failed: return "购买未完成，请稍后重试。未扣费不会解锁。"
        case .restoredNothing: return "此 Apple 账号下没有找到可恢复的购买。"
        case .restored: return "已恢复购买。"
        case .offline: return "无法连接 App Store，已保留本机记录，请联网后重试。"
        }
    }
}

/// 付费功能占位：未解锁时显示说明与购买入口。
struct LockedFeatureView: View {
    let model: AppModel
    let feature: PaidFeature
    var body: some View {
        ContentUnavailableView {
            Label("\(PaywallView.name(feature))属于完整版", systemImage: "lock")
        } description: {
            Text("一次性购买，永久解锁。免费功能不受影响。")
        } actions: {
            NavigationLink("了解完整版") { PaywallView(model: model) }.buttonStyle(.borderedProminent)
        }
    }
}
