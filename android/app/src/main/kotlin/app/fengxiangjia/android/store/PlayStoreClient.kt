package app.fengxiangjia.android.store

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.*
import app.fengxiangjia.core.store.ProductID
import app.fengxiangjia.core.store.PurchaseOutcome
import app.fengxiangjia.core.store.StoreClient
import app.fengxiangjia.core.store.StoreClientError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

/**
 * #65 Google Play 结算（一次性、非消耗型商品，**暂定**；未在真机/Play 测试轨道验证）。
 * 商品 ID 为占位 [ProductID.FULL_VERSION]，需在 Play Console 创建后才能查询到。
 * 只在 Purchased 且已确认（acknowledge）后视为解锁；Pending 不解锁。服务器端校验未实现（见 #51 风险）。
 */
class PlayStoreClient(context: Context, private val activity: () -> Activity?) : StoreClient, AutoCloseable {
    private val appContext = context.applicationContext
    private val connectionLock = Mutex()
    private var closed = false
    private var pendingPurchase: CompletableDeferred<PurchaseOutcome>? = null
    private var client = makeClient()

    private fun makeClient() = BillingClient.newBuilder(appContext)
        .setListener { r, purchases -> onPurchases(r, purchases) }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()

    private suspend fun ready() {
        if (closed) throw StoreClientError.Network()
        try {
            withTimeout(SERVICE_TIMEOUT_MS) {
                connectionLock.withLock {
                    if (closed) throw StoreClientError.Network()
                    if (client.isReady) return@withLock
                    val r = suspendCancellableCoroutine { c ->
                        client.startConnection(object : BillingClientStateListener {
                            override fun onBillingSetupFinished(r: BillingResult) { if (c.isActive) c.resume(r) }
                            override fun onBillingServiceDisconnected() {
                                if (c.isActive) c.resume(BillingResult.newBuilder()
                                    .setResponseCode(BillingClient.BillingResponseCode.SERVICE_DISCONNECTED).build())
                            }
                        })
                    }
                    if (r.responseCode != BillingClient.BillingResponseCode.OK) throw mapError(r)
                }
            }
        } catch (e: TimeoutCancellationException) {
            // A stuck connection is disposable. The next explicit retry gets a fresh client.
            client.endConnection()
            if (!closed) client = makeClient()
            throw StoreClientError.Network()
        }
    }

    private fun mapError(r: BillingResult): StoreClientError = when (r.responseCode) {
        BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE, BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
        BillingClient.BillingResponseCode.NETWORK_ERROR, BillingClient.BillingResponseCode.BILLING_UNAVAILABLE -> StoreClientError.Network()
        BillingClient.BillingResponseCode.ITEM_UNAVAILABLE -> StoreClientError.ProductUnavailable()
        BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED -> StoreClientError.NotAllowed()
        else -> StoreClientError.Unknown(r.debugMessage)
    }

    private suspend fun product(): ProductDetails {
        ready()
        val params = QueryProductDetailsParams.newBuilder().setProductList(listOf(
            QueryProductDetailsParams.Product.newBuilder().setProductId(ProductID.FULL_VERSION).setProductType(BillingClient.ProductType.INAPP).build())).build()
        val res = bounded { client.queryProductDetails(params) }
        if (res.billingResult.responseCode != BillingClient.BillingResponseCode.OK) throw mapError(res.billingResult)
        return res.productDetailsList?.firstOrNull() ?: throw StoreClientError.ProductUnavailable()
    }

    override suspend fun displayPrice(): String = product().oneTimePurchaseOfferDetails?.formattedPrice
        ?.takeIf { it.isNotBlank() } ?: throw StoreClientError.ProductUnavailable()

    override suspend fun purchase(): PurchaseOutcome {
        val p = product()
        val act = activity() ?: return PurchaseOutcome.Failed("no activity")
        if (pendingPurchase != null) return PurchaseOutcome.Failed("purchase already running")
        val d = CompletableDeferred<PurchaseOutcome>(); pendingPurchase = d
        val flow = BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(p).build())).build()
        return try {
            val r = client.launchBillingFlow(act, flow)
            if (r.responseCode != BillingClient.BillingResponseCode.OK) return PurchaseOutcome.Failed(r.debugMessage)
            withTimeout(PURCHASE_TIMEOUT_MS) { d.await() }
        } catch (e: TimeoutCancellationException) {
            PurchaseOutcome.Failed("purchase response timed out")
        } finally {
            if (pendingPurchase === d) pendingPurchase = null
        }
    }

    private fun onPurchases(r: BillingResult, purchases: List<Purchase>?) {
        val d = pendingPurchase ?: return
        val outcome = when (r.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                val mine = purchases.orEmpty().firstOrNull { ProductID.FULL_VERSION in it.products }
                when (mine?.purchaseState) {
                    Purchase.PurchaseState.PURCHASED -> {
                        if (!mine.isAcknowledged) {
                            client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(mine.purchaseToken).build()) { ack ->
                                if (pendingPurchase === d) d.complete(if (ack.responseCode == BillingClient.BillingResponseCode.OK)
                                    PurchaseOutcome.Purchased else PurchaseOutcome.Unverified)
                            }
                            return
                        }
                        PurchaseOutcome.Purchased
                    }
                    Purchase.PurchaseState.PENDING -> PurchaseOutcome.Pending
                    else -> PurchaseOutcome.Unverified
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> PurchaseOutcome.Cancelled
            // An "already owned" response is not itself verified entitlement. Restore queries it.
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> PurchaseOutcome.Unverified
            else -> PurchaseOutcome.Failed(r.debugMessage)
        }
        d.complete(outcome)
    }

    private suspend fun acknowledged(p: Purchase): Boolean {
        if (p.isAcknowledged) return true
        val r = bounded {
            suspendCancellableCoroutine { c ->
                client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build()) { r ->
                    if (c.isActive) c.resume(r)
                }
            }
        }
        return r.responseCode == BillingClient.BillingResponseCode.OK
    }

    /** null = 无法联系 Play（离线），调用方保留缓存权益。 */
    override suspend fun currentEntitlement(): Boolean? = try {
        ready()
        val res = bounded { client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()) }
        if (res.billingResult.responseCode != BillingClient.BillingResponseCode.OK) null
        else {
            val owned = res.purchasesList.filter { ProductID.FULL_VERSION in it.products && it.purchaseState == Purchase.PurchaseState.PURCHASED }
            if (owned.isEmpty()) false
            else if (owned.any { acknowledged(it) }) true else null
        }
    } catch (e: StoreClientError) { null }

    override suspend fun restore(): Boolean = currentEntitlement() ?: throw StoreClientError.Network()

    private suspend fun <T> bounded(block: suspend () -> T): T = try {
        withTimeout(SERVICE_TIMEOUT_MS) { block() }
    } catch (e: TimeoutCancellationException) { throw StoreClientError.Network() }

    override fun close() {
        closed = true
        pendingPurchase?.cancel()
        pendingPurchase = null
        client.endConnection()
    }

    private companion object {
        const val SERVICE_TIMEOUT_MS = 8_000L
        const val PURCHASE_TIMEOUT_MS = 120_000L
    }
}
