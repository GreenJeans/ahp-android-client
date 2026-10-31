package dev.ahpclient.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import dev.ahpclient.app.StoreBillingDriver
import dev.ahpclient.app.StoreLaunchResult
import dev.ahpclient.app.StorePurchase
import dev.ahpclient.app.StorePurchaseState
import dev.ahpclient.app.StoreQueryResult
import dev.ahpclient.security.AttachedStack
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Google Play Billing implementation of [StoreBillingDriver].
 *
 * Uses [AttachedStack] to obtain the foreground [Activity] when launching the purchase sheet, and maps Play Billing
 * responses onto platform-neutral [StoreQueryResult] and [StoreLaunchResult] values for [dev.ahpclient.app.StoreBillingGateway].
 */
class PlayBillingDriver(
    context: Context,
    private val onAsyncPurchaseUpdate: (List<StorePurchase>) -> Unit = {},
) : StoreBillingDriver {

    private val appContext = context.applicationContext
    private val screens = AttachedStack<Activity>()
    private val connectionMutex = Mutex()
    private val launchMutex = Mutex()

    @Volatile
    private var pendingLaunch: CompletableDeferred<StoreLaunchResult>? = null

    private val purchasesUpdatedListener = PurchasesUpdatedListener { billingResult, purchases ->
        val mapped = purchases.orEmpty().map(::toStorePurchase)
        val result = when (billingResult.responseCode) {
            BillingResponseCode.OK -> StoreLaunchResult.Purchased(mapped)
            BillingResponseCode.USER_CANCELED -> StoreLaunchResult.Cancelled
            BillingResponseCode.ITEM_ALREADY_OWNED -> StoreLaunchResult.AlreadyOwned
            else -> StoreLaunchResult.Error(describeBillingError("Google Play purchase could not complete", billingResult))
        }
        val active = pendingLaunch
        if (active != null && active.complete(result)) {
            return@PurchasesUpdatedListener
        }
        if (billingResult.responseCode == BillingResponseCode.OK && mapped.isNotEmpty()) {
            onAsyncPurchaseUpdate(mapped)
        }
    }

    private val client: BillingClient by lazy {
        BillingClient.newBuilder(appContext)
            .setListener(purchasesUpdatedListener)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder()
                    .enableOneTimeProducts()
                    .build(),
            )
            .build()
    }

    /** Call when an activity comes on screen so the Google Play purchase sheet has a host window. */
    fun attach(activity: Activity) = screens.attach(activity)

    /** Call when the activity leaves the screen. */
    fun detach(activity: Activity) = screens.detach(activity)

    override suspend fun queryPurchases(): StoreQueryResult {
        val connected = ensureConnected()
        if (connected != null) return StoreQueryResult.Error(connected)

        return suspendCancellableCoroutine { continuation ->
            val params = QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
            client.queryPurchasesAsync(params) { billingResult, purchases ->
                if (!continuation.isActive) return@queryPurchasesAsync
                if (billingResult.responseCode == BillingResponseCode.OK) {
                    continuation.resume(StoreQueryResult.Success(purchases.map(::toStorePurchase)))
                } else {
                    continuation.resume(
                        StoreQueryResult.Error(describeBillingError("Could not query Google Play purchases", billingResult)),
                    )
                }
            }
        }
    }

    override suspend fun acknowledgePurchase(purchaseToken: String): Boolean {
        if (ensureConnected() != null) return false
        return suspendCancellableCoroutine { continuation ->
            val params = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchaseToken)
                .build()
            client.acknowledgePurchase(params) { billingResult ->
                if (continuation.isActive) {
                    continuation.resume(billingResult.responseCode == BillingResponseCode.OK)
                }
            }
        }
    }

    override suspend fun launchPurchaseFlow(productId: String): StoreLaunchResult = launchMutex.withLock {
        val activity = screens.top()
            ?: return StoreLaunchResult.Error("Open the app on screen to start a Google Play purchase.")
        val connected = ensureConnected()
        if (connected != null) return StoreLaunchResult.Error(connected)

        val productDetails = queryProductDetails(productId)
            ?: return StoreLaunchResult.Error(
                "Pro unlock ('$productId') is not yet active in Google Play for this build.",
            )

        val deferred = CompletableDeferred<StoreLaunchResult>()
        pendingLaunch = deferred
        try {
            val flowParams = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(
                    listOf(
                        BillingFlowParams.ProductDetailsParams.newBuilder()
                            .setProductDetails(productDetails)
                            .build(),
                    ),
                )
                .build()

            val launchResult = withContext(Dispatchers.Main) {
                client.launchBillingFlow(activity, flowParams)
            }
            if (launchResult.responseCode != BillingResponseCode.OK) {
                return when (launchResult.responseCode) {
                    BillingResponseCode.USER_CANCELED -> StoreLaunchResult.Cancelled
                    BillingResponseCode.ITEM_ALREADY_OWNED -> StoreLaunchResult.AlreadyOwned
                    else -> StoreLaunchResult.Error(describeBillingError("Could not open Google Play purchase sheet", launchResult))
                }
            }
            return deferred.await()
        } finally {
            if (pendingLaunch === deferred) {
                pendingLaunch = null
            }
        }
    }

    private suspend fun queryProductDetails(productId: String) = suspendCancellableCoroutine { continuation ->
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(productId)
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(listOf(product))
            .build()
        client.queryProductDetailsAsync(params) { billingResult, queryProductDetailsResult ->
            if (!continuation.isActive) return@queryProductDetailsAsync
            if (billingResult.responseCode == BillingResponseCode.OK) {
                continuation.resume(queryProductDetailsResult.productDetailsList.firstOrNull { it.productId == productId })
            } else {
                continuation.resume(null)
            }
        }
    }

    /** Connects [client] if needed. Returns `null` when ready, or an explanation string when Google Play Billing is unavailable. */
    private suspend fun ensureConnected(): String? = connectionMutex.withLock {
        if (client.isReady) return null
        return suspendCancellableCoroutine { continuation ->
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(billingResult: BillingResult) {
                    if (!continuation.isActive) return
                    if (billingResult.responseCode == BillingResponseCode.OK) {
                        continuation.resume(null)
                    } else {
                        continuation.resume(describeBillingError("Google Play Billing is unavailable on this device", billingResult))
                    }
                }

                override fun onBillingServiceDisconnected() {
                    if (continuation.isActive) {
                        continuation.resume("Connection to Google Play Billing was interrupted. Try again.")
                    }
                }
            })
        }
    }

    private fun toStorePurchase(purchase: Purchase): StorePurchase {
        val state = when (purchase.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> StorePurchaseState.PURCHASED
            Purchase.PurchaseState.PENDING -> StorePurchaseState.PENDING
            else -> StorePurchaseState.UNSPECIFIED
        }
        return StorePurchase(
            products = purchase.products,
            state = state,
            isAcknowledged = purchase.isAcknowledged,
            purchaseToken = purchase.purchaseToken,
        )
    }

    private fun describeBillingError(prefix: String, result: BillingResult): String {
        val detail = result.debugMessage.takeIf { it.isNotBlank() } ?: "code ${result.responseCode}"
        return "$prefix ($detail)."
    }
}
