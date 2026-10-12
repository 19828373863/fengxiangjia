import SwiftUI

@main
struct FengXiangJiaApp: App {
    @State private var model = AppModel()
    var body: some Scene {
        WindowGroup {
            RootTabView(model: model)
        }
    }
}
