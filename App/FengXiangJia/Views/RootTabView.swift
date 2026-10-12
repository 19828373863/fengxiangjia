import SwiftUI
import FXJCore

/// 应用主导航的三个页签。
enum AppTab: String, CaseIterable, Identifiable {
    case home, tryout, plans
    var id: String { rawValue }

    var title: String {
        switch self {
        case .home: return "我家"
        case .tryout: return "试摆"
        case .plans: return "方案"
        }
    }

    var systemImage: String {
        switch self {
        case .home: return "house"
        case .tryout: return "fan"
        case .plans: return "square.split.2x1"
        }
    }
}

struct RootTabView: View {
    let model: AppModel
    @State private var selection: AppTab = .home

    var body: some View {
        TabView(selection: $selection) {
            NavigationStack { HomeTabView(model: model, selection: $selection) }
                .tabItem { Label(AppTab.home.title, systemImage: AppTab.home.systemImage) }
                .tag(AppTab.home)
            NavigationStack { TryoutTabView(model: model, selection: $selection) }
                .tabItem { Label(AppTab.tryout.title, systemImage: AppTab.tryout.systemImage) }
                .tag(AppTab.tryout)
            NavigationStack { PlansTabView(model: model) }
                .tabItem { Label(AppTab.plans.title, systemImage: AppTab.plans.systemImage) }
                .tag(AppTab.plans)
        }
        .task { if case .loading = model.state { model.load() } }
    }
}

/// 通用状态视图：加载 / 空 / 错误。
struct LoadStateView<Content: View>: View {
    let state: HomeLoadState
    let onSample: () -> Void
    let onRetry: () -> Void
    @ViewBuilder let content: (HomeDocument) -> Content

    var body: some View {
        switch state {
        case .loading:
            ProgressView("正在读取本地数据…")
        case .empty:
            ContentUnavailableView {
                Label("还没有房间", systemImage: "square.dashed")
            } description: {
                Text("先打开示例房间体验拖动风扇，无需注册登录。")
            } actions: {
                Button("打开示例房间", action: onSample).buttonStyle(.borderedProminent)
            }
        case .failed(let message):
            ContentUnavailableView {
                Label("读取失败", systemImage: "exclamationmark.triangle")
            } description: {
                Text(message)
            } actions: {
                Button("重试", action: onRetry)
                Button("改用示例房间", action: onSample)
            }
        case .loaded(let doc):
            content(doc)
        }
    }
}

#Preview { RootTabView(model: AppModel()) }
