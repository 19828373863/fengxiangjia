import SwiftUI
import FXJCore

@main
struct FengXiangJiaApp: App {
    @State private var model = AppModel()
    var body: some Scene {
        WindowGroup {
            RootTabView(model: model)
                .task {
                    // #19：启动时核对权益，并监听退款/他处购买/家长批准
                    await model.refreshEntitlement()
                    let m = model
                    _ = StoreKitClient.observeUpdates { await m.refreshEntitlement() }
                }
        }
    }
}
