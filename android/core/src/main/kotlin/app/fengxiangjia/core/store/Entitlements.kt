package app.fengxiangjia.core.store

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/**
 * #19 一次性买断“完整版”（与 iOS `Entitlements.swift` 同规则，平台无关）。
 * Android 端由 Google Play Billing 适配（app 层 `PlayBillingClient`），**暂定**，见 #51。
 */
object ProductID {
    /** 占位，需与 Play Console 商品 ID 一致（非消耗型 in-app product）。 */
    const val FULL_VERSION = "fengxiangjia_full"
}

enum class PaidFeature { multiRoom, autoRecommendation, liveWeather, roomScan, unlimitedScenarios }

/** 免费版：1 个房间、最多 3 个方案；手绘、试摆、气流、A/B、导出、手动风向免费（与 iOS 一致）。 */
object FreeLimits { const val ROOMS = 1; const val SCENARIOS = 3 }

sealed interface PurchaseOutcome {
    data object Purchased : PurchaseOutcome; data object Cancelled : PurchaseOutcome; data object Pending : PurchaseOutcome
    data class Failed(val reason: String) : PurchaseOutcome; data object Unverified : PurchaseOutcome
}

sealed class StoreClientError(msg: String) : Exception(msg) {
    class ProductUnavailable : StoreClientError("productUnavailable"); class Network : StoreClientError("network")
    class NotAllowed : StoreClientError("notAllowed"); class Unknown(r: String) : StoreClientError(r)
}

interface StoreClient {
    suspend fun displayPrice(): String
    suspend fun purchase(): PurchaseOutcome
    /** 已验证权益；null = 无法联系商店。 */
    suspend fun currentEntitlement(): Boolean?
    suspend fun restore(): Boolean
}

@Serializable
data class EntitlementCache(val unlocked: Boolean = false, val verifiedAtEpochSeconds: Long? = null) {
    companion object { const val OFFLINE_GRACE_SECONDS = 30L * 24 * 3600 }
}

data class EntitlementState(val cache: EntitlementCache = EntitlementCache()) {
    val isUnlocked get() = cache.unlocked
    fun allows(@Suppress("UNUSED_PARAMETER") f: PaidFeature) = isUnlocked
    fun canAddRoom(currentCount: Int) = isUnlocked || currentCount < FreeLimits.ROOMS
    fun canSaveScenario(currentCount: Int) = isUnlocked || currentCount < FreeLimits.SCENARIOS
    /** `verified == null` 表示离线：保留缓存。 */
    fun applyVerified(verified: Boolean?, now: Long) = if (verified == null) this else EntitlementState(EntitlementCache(verified, now))
    /** 只有 Purchased 才解锁；其他结果不改变已有权益。 */
    fun apply(o: PurchaseOutcome, now: Long) = if (o == PurchaseOutcome.Purchased) EntitlementState(EntitlementCache(true, now)) else this
    fun needsRecheck(now: Long) = cache.verifiedAtEpochSeconds?.let { now - it > EntitlementCache.OFFLINE_GRACE_SECONDS } ?: true
}

class EntitlementManager(private val client: StoreClient, cache: EntitlementCache) {
    enum class Message(val text: String?) {
        none(null), unlocked("已解锁完整版"), cancelled("已取消购买"), pending("付款处理中，完成后自动解锁"),
        failed("购买未完成，请稍后重试"), restoredNothing("没有找到可恢复的购买"), restored("已恢复购买"), offline("网络不可用，请稍后重试")
    }
    private val mutex = Mutex()
    var state = EntitlementState(cache); private set

    suspend fun refresh(now: Long): EntitlementState = mutex.withLock { state = state.applyVerified(client.currentEntitlement(), now); state }

    suspend fun purchase(now: Long): Pair<EntitlementState, Message> = mutex.withLock {
        try {
            val o = client.purchase(); state = state.apply(o, now)
            state to when (o) { PurchaseOutcome.Purchased -> Message.unlocked; PurchaseOutcome.Cancelled -> Message.cancelled; PurchaseOutcome.Pending -> Message.pending; else -> Message.failed }
        } catch (e: StoreClientError.Network) { state to Message.offline } catch (e: Exception) { state to Message.failed }
    }

    suspend fun restore(now: Long): Pair<EntitlementState, Message> = mutex.withLock {
        try { val ok = client.restore(); state = state.applyVerified(ok, now); state to if (ok) Message.restored else Message.restoredNothing }
        catch (e: StoreClientError.Network) { state to Message.offline } catch (e: Exception) { state to Message.failed }
    }
}

class MockStoreClient(var owned: Boolean = false, var nextOutcome: PurchaseOutcome = PurchaseOutcome.Purchased, var failNetwork: Boolean = false) : StoreClient {
    override suspend fun displayPrice() = "¥12"
    override suspend fun purchase(): PurchaseOutcome { if (failNetwork) throw StoreClientError.Network(); if (nextOutcome == PurchaseOutcome.Purchased) owned = true; return nextOutcome }
    override suspend fun currentEntitlement(): Boolean? = if (failNetwork) null else owned
    override suspend fun restore(): Boolean { if (failNetwork) throw StoreClientError.Network(); return owned }
}
