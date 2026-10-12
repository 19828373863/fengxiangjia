import Foundation
import StoreKit
import FXJCore

/// #19 StoreKit 2 适配：非消耗型“完整版”。本地测试使用仓库内 `FengXiangJia.storekit`（scheme 已配置）；
/// 沙盒/正式需在 App Store Connect 创建同 ID 商品（需开发者账号，未验证）。
struct StoreKitClient: StoreClient {
    let productID: String

    init(productID: String = ProductID.fullVersion) { self.productID = productID }

    private func product() async throws -> Product {
        do {
            guard let p = try await Product.products(for: [productID]).first else { throw StoreClientError.productUnavailable }
            return p
        } catch let e as StoreClientError { throw e } catch { throw StoreClientError.network }
    }

    func displayPrice() async throws -> String { try await product().displayPrice }

    func purchase() async throws -> PurchaseOutcome {
        let p = try await product()
        let r: Product.PurchaseResult
        do { r = try await p.purchase() } catch { return .failed(error.localizedDescription) }
        switch r {
        case .success(let v):
            switch v {
            case .verified(let t): await t.finish(); return .purchased
            case .unverified: return .unverified
            }
        case .userCancelled: return .cancelled
        case .pending: return .pending   // 家长批准等
        @unknown default: return .failed("unknown")
        }
    }

    func currentEntitlement() async -> Bool {
        for await r in Transaction.currentEntitlements {
            if case .verified(let t) = r, t.productID == productID, t.revocationDate == nil { return true }
        }
        return false
    }

    func restore() async throws -> Bool {
        do { try await AppStore.sync() } catch { throw StoreClientError.network }
        return await currentEntitlement()
    }

    /// 监听交易更新（他处购买、退款、家长批准完成）。
    static func observeUpdates(_ handler: @escaping @Sendable () async -> Void) -> Task<Void, Never> {
        Task.detached {
            for await r in Transaction.updates {
                if case .verified(let t) = r { await t.finish() }
                await handler()
            }
        }
    }
}

enum EntitlementCacheStore {
    static let key = "fxj.entitlement.v1"
    static func load(_ d: UserDefaults = .standard) -> EntitlementCache {
        d.data(forKey: key).flatMap { try? JSONDecoder().decode(EntitlementCache.self, from: $0) } ?? .init()
    }
    static func save(_ c: EntitlementCache, _ d: UserDefaults = .standard) {
        if let data = try? JSONEncoder().encode(c) { d.set(data, forKey: key) }
    }
}
