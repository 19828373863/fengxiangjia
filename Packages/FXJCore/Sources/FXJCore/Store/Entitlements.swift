import Foundation

/// #19 一次性内购（非消耗型）“完整版”。纯逻辑：免费/付费能力划分、购买结果映射、权益缓存。
/// StoreKit 2 适配在 App 层（StoreKitClient），本层可在 Linux 测试。
public enum ProductID {
    /// 占位，需与 App Store Connect 一致（待正式 Bundle ID 确定）。
    public static let fullVersion = "com.example.fengxiangjia.app.full"
}

public enum PaidFeature: String, CaseIterable, Codable, Sendable {
    case multiRoom, autoRecommendation, liveWeather, roomScan, unlimitedScenarios
}

public enum FreeLimits {
    /// 免费版：1 个房间、最多 3 个方案；手绘、试摆、气流、A/B 比较、导出、手动风向均免费。
    public static let rooms = 1
    public static let scenarios = 3
}

public enum PurchaseOutcome: Equatable, Sendable {
    case purchased, cancelled, pending, failed(String), unverified
}

public enum StoreClientError: Error, Equatable, Sendable { case productUnavailable, network, notAllowed, unknown(String) }

/// 商店抽象：真实实现为 StoreKit 2；测试/无账号时用 MockStoreClient 或 .storekit 配置文件。
public protocol StoreClient: Sendable {
    func displayPrice() async throws -> String
    func purchase() async throws -> PurchaseOutcome
    /// 当前已验证的权益（Transaction.currentEntitlements）。
    func currentEntitlement() async -> Bool
    /// 恢复购买（AppStore.sync）后返回权益。
    func restore() async throws -> Bool
}

public struct EntitlementCache: Codable, Equatable, Sendable {
    public var unlocked: Bool
    public var verifiedAt: Date?
    public init(unlocked: Bool = false, verifiedAt: Date? = nil) { self.unlocked = unlocked; self.verifiedAt = verifiedAt }
    /// 离线时沿用已验证权益的期限（之后仍沿用，但在下次联网时重新核对）；未验证过则不解锁。
    public static let offlineGrace: TimeInterval = 30 * 24 * 3600
}

public struct EntitlementState: Equatable, Sendable {
    public var cache: EntitlementCache
    public init(cache: EntitlementCache = .init()) { self.cache = cache }

    public var isUnlocked: Bool { cache.unlocked }

    public func allows(_ f: PaidFeature) -> Bool { isUnlocked }
    public func canAddRoom(currentCount: Int) -> Bool { isUnlocked || currentCount < FreeLimits.rooms }
    public func canSaveScenario(currentCount: Int) -> Bool { isUnlocked || currentCount < FreeLimits.scenarios }

    /// 商店核对结果写入缓存。`verified == nil` 表示无法联系商店（离线）：保留缓存。
    public mutating func apply(verified: Bool?, now: Date) {
        guard let v = verified else { return }
        cache = EntitlementCache(unlocked: v, verifiedAt: now)
    }

    /// 购买结果：只有 purchased 才解锁；取消/待批准/失败/未验证不改变已有权益。
    public mutating func apply(_ outcome: PurchaseOutcome, now: Date) {
        if outcome == .purchased { cache = EntitlementCache(unlocked: true, verifiedAt: now) }
    }

    public func needsRecheck(now: Date) -> Bool {
        guard let t = cache.verifiedAt else { return true }
        return now.timeIntervalSince(t) > EntitlementCache.offlineGrace
    }
}

/// 存储协调：串联商店与缓存；所有错误转为用户可读状态，不抛出到界面。
public actor EntitlementManager {
    public enum Message: Equatable, Sendable { case none, unlocked, cancelled, pending, failed, restoredNothing, restored, offline }
    private let client: StoreClient
    public private(set) var state: EntitlementState

    public init(client: StoreClient, cache: EntitlementCache) {
        self.client = client; self.state = EntitlementState(cache: cache)
    }

    public func refresh(now: Date = Date()) async -> EntitlementState {
        state.apply(verified: await client.currentEntitlement(), now: now)
        return state
    }

    public func purchase(now: Date = Date()) async -> (EntitlementState, Message) {
        do {
            let o = try await client.purchase()
            state.apply(o, now: now)
            switch o {
            case .purchased: return (state, .unlocked)
            case .cancelled: return (state, .cancelled)
            case .pending: return (state, .pending)
            case .failed, .unverified: return (state, .failed)
            }
        } catch StoreClientError.network { return (state, .offline) } catch { return (state, .failed) }
    }

    public func restore(now: Date = Date()) async -> (EntitlementState, Message) {
        do {
            let ok = try await client.restore()
            state.apply(verified: ok, now: now)
            return (state, ok ? .restored : .restoredNothing)
        } catch StoreClientError.network { return (state, .offline) } catch { return (state, .failed) }
    }
}

/// 测试/预览用商店。
public final class MockStoreClient: StoreClient, @unchecked Sendable {
    private let lock = NSLock()
    private var owned: Bool
    public var nextOutcome: PurchaseOutcome
    public var failNetwork: Bool
    public init(owned: Bool = false, nextOutcome: PurchaseOutcome = .purchased, failNetwork: Bool = false) {
        self.owned = owned; self.nextOutcome = nextOutcome; self.failNetwork = failNetwork
    }
    private func locked<T>(_ f: () -> T) -> T { lock.lock(); defer { lock.unlock() }; return f() }
    public func displayPrice() async throws -> String { "¥12" }
    public func purchase() async throws -> PurchaseOutcome {
        if failNetwork { throw StoreClientError.network }
        return locked { if nextOutcome == .purchased { owned = true }; return nextOutcome }
    }
    public func currentEntitlement() async -> Bool { locked { owned } }
    public func restore() async throws -> Bool {
        if failNetwork { throw StoreClientError.network }
        return locked { owned }
    }
}
