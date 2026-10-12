package app.fengxiangjia.android.store

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.*
import app.fengxiangjia.core.store.ProductID
import app.fengxiangjia.core.store.PurchaseOutcome
import app.fengxiangjia.core.store.StoreClient
import app.fengxiangjia.core.store.StoreClientError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * #65 Google Play 结算（一次性、非消耗型商品，**暂定**；未在真机/Play 测试轨道验证）。
 * 商品 ID 为占位 [ProductID.FULL_VERSION]，需在 Play Console 创建后才能查询到。
 * 只在 Purchased 且已确认（acknowledge）后视为解锁；Pending 不解锁。服务器端校验未实现（见 #51 风险）。
 */
class PlayStoreClient(context: Context, private val activity: () -> Activity?) : StoreClient {
    private var pendingPurchase: CompletableDeferred<PurchaseOutcome>? = null
    private val client = BillingClient.newBuilder(context.applicationContext)
        .setListener { r, purchases -> onPurchases(r, purchases) }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()

    private suspend fun ready() {
        if (client.isReady) return
        val r = suspendCancellableCoroutine { c ->
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(r: BillingResult) { if (c.isActive) c.resume(r) }
                override fun onBillingServiceDisconnected() {}
            })
        }
        if (r.responseCode != BillingClient.BillingResponseCode.OK) throw mapError(r)
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
        val res = client.queryProductDetails(params)
        if (res.billingResult.responseCode != BillingClient.BillingResponseCode.OK) throw mapError(res.billingResult)
        return res.productDetailsList?.firstOrNull() ?: throw StoreClientError.ProductUnavailable()
    }

    override suspend fun displayPrice(): String = product().oneTimePurchaseOfferDetails?.formattedPrice ?: "—"

    override suspend fun purchase(): PurchaseOutcome {
        val p = product()
        val act = activity() ?: return PurchaseOutcome.Failed("no activity")
        val d = CompletableDeferred<PurchaseOutcome>(); pendingPurchase = d
        val flow = BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(p).build())).build()
        val r = client.launchBillingFlow(act, flow)
        if (r.responseCode != BillingClient.BillingResponseCode.OK) { pendingPurchase = null; return PurchaseOutcome.Failed(r.debugMessage) }
        return d.await()
    }

    private fun onPurchases(r: BillingResult, purchases: List<Purchase>?) {
        val d = pendingPurchase ?: return; pendingPurchase = null
        val outcome = when (r.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                val mine = purchases.orEmpty().firstOrNull { ProductID.FULL_VERSION in it.products }
                when (mine?.purchaseState) {
                    Purchase.PurchaseState.PURCHASED -> { acknowledge(mine); PurchaseOutcome.Purchased }
                    Purchase.PurchaseState.PENDING -> PurchaseOutcome.Pending
                    else -> PurchaseOutcome.Unverified
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> PurchaseOutcome.Cancelled
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> PurchaseOutcome.Purchased
            else -> PurchaseOutcome.Failed(r.debugMessage)
        }
        d.complete(outcome)
    }

    private fun acknowledge(p: Purchase) {
        if (p.isAcknowledged) return
        client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build()) { }
    }

    /** null = 无法联系 Play（离线），调用方保留缓存权益。 */
    override suspend fun currentEntitlement(): Boolean? = try {
        ready()
        val res = client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build())
        if (res.billingResult.responseCode != BillingClient.BillingResponseCode.OK) null
        else res.purchasesList.filter { ProductID.FULL_VERSION in it.products && it.purchaseState == Purchase.PurchaseState.PURCHASED }
            .onEach(::acknowledge).isNotEmpty()
    } catch (e: StoreClientError) { null }

    override suspend fun restore(): Boolean = currentEntitlement() ?: throw StoreClientError.Network()
}
