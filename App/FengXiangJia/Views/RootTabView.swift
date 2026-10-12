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
    @State private var selection: AppTab = .home

    var body: some View {
        TabView(selection: $selection) {
            ForEach(AppTab.allCases) { tab in
                NavigationStack {
                    PlaceholderView(tab: tab)
                        .navigationTitle(tab.title)
                }
                .tabItem { Label(tab.title, systemImage: tab.systemImage) }
                .tag(tab)
            }
        }
    }
}

struct PlaceholderView: View {
    let tab: AppTab
    var body: some View {
        ContentUnavailableView(
            tab.title,
            systemImage: tab.systemImage,
            description: Text("占位页面，功能开发中（FXJCore \(FXJCore.version)）")
        )
    }
}

#Preview { RootTabView() }
