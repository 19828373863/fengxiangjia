import XCTest
@testable import FXJCore

final class StoreTests: XCTestCase {
    let t0 = Date(timeIntervalSince1970: 1_800_000_000)

    func testFreeLimits() {
        let s = EntitlementState()
        XCTAssertTrue(s.canAddRoom(currentCount: 0)); XCTAssertFalse(s.canAddRoom(currentCount: 1))
        XCTAssertTrue(s.canSaveScenario(currentCount: 2)); XCTAssertFalse(s.canSaveScenario(currentCount: 3))
        XCTAssertFalse(PaidFeature.allCases.contains { s.allows($0) })
        let p = EntitlementState(cache: .init(unlocked: true, verifiedAt: t0))
        XCTAssertTrue(p.canAddRoom(currentCount: 99) && p.canSaveScenario(currentCount: 99))
        XCTAssertTrue(PaidFeature.allCases.allSatisfy { p.allows($0) })
    }

    func testPurchaseSuccessUnlocks() async {
        let m = EntitlementManager(client: MockStoreClient(), cache: .init())
        let (s, msg) = await m.purchase(now: t0)
        XCTAssertTrue(s.isUnlocked); XCTAssertEqual(msg, .unlocked); XCTAssertEqual(s.cache.verifiedAt, t0)
    }

    func testCancelPendingFailDoNotUnlock() async {
        for (o, expect) in [(PurchaseOutcome.cancelled, EntitlementManager.Message.cancelled), (.pending, .pending),
                            (.failed("x"), .failed), (.unverified, .failed)] {
            let m = EntitlementManager(client: MockStoreClient(nextOutcome: o), cache: .init())
            let (s, msg) = await m.purchase(now: t0)
            XCTAssertFalse(s.isUnlocked); XCTAssertEqual(msg, expect)
        }
    }

    func testRestore() async {
        let owned = EntitlementManager(client: MockStoreClient(owned: true), cache: .init())
        let (s1, m1) = await owned.restore(now: t0)
        XCTAssertTrue(s1.isUnlocked); XCTAssertEqual(m1, .restored)
        let none = EntitlementManager(client: MockStoreClient(owned: false), cache: .init())
        let (s2, m2) = await none.restore(now: t0)
        XCTAssertFalse(s2.isUnlocked); XCTAssertEqual(m2, .restoredNothing)
    }

    func testOfflineKeepsCachedEntitlement() async {
        let cache = EntitlementCache(unlocked: true, verifiedAt: t0)
        let m = EntitlementManager(client: MockStoreClient(owned: true, failNetwork: true), cache: cache)
        let (s, msg) = await m.restore(now: t0.addingTimeInterval(3600))
        XCTAssertTrue(s.isUnlocked); XCTAssertEqual(msg, .offline)
        var st = EntitlementState(cache: cache)
        st.apply(verified: nil, now: t0.addingTimeInterval(1e7))
        XCTAssertTrue(st.isUnlocked, "离线不撤销已验证权益")
        XCTAssertTrue(st.needsRecheck(now: t0.addingTimeInterval(EntitlementCache.offlineGrace + 1)))
        XCTAssertFalse(st.needsRecheck(now: t0.addingTimeInterval(60)))
    }

    func testRefundRevokesOnRefresh() async {
        // 退款后 currentEntitlements 不再包含该交易 → 刷新撤销缓存
        let m = EntitlementManager(client: MockStoreClient(owned: false), cache: .init(unlocked: true, verifiedAt: t0))
        let s = await m.refresh(now: t0.addingTimeInterval(10))
        XCTAssertFalse(s.isUnlocked)
        let d = try! JSONEncoder().encode(s.cache)
        XCTAssertEqual(try! JSONDecoder().decode(EntitlementCache.self, from: d), s.cache)
    }
}
