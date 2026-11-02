package dev.ahpclient.billing

import dev.ahpclient.app.BillingGateway
import dev.ahpclient.app.StoreBillingDriver
import dev.ahpclient.app.StoreLaunchResult
import dev.ahpclient.app.StorePurchase
import dev.ahpclient.app.StorePurchaseState
import dev.ahpclient.app.StoreQueryResult
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Bridge record passed from Swift's `StoreKitBridgeImpl` into Kotlin.
 */
data class StorePurchaseRecord(
    val products: List<String>,
    val state: StorePurchaseState,
    val acknowledged: Boolean,
    val token: String,
) {
    fun toStorePurchase(): StorePurchase = StorePurchase(
        products = products,
        state = state,
        isAcknowledged = acknowledged,
        purchaseToken = token,
    )
}

/**
 * StoreKit 2 bridge interface that the iOS Swift host (`StoreKitBridgeImpl.swift`) registers at launch.
 *
 * In Milestone 1 dev builds (`isDebuggable = true`), [dev.ahpclient.app.ProEntitlement] defaults to unlocked
 * and exposes the Settings debug switch so all Pro features can be tested without App Store Connect setup.
 * In Milestone 2 App Store builds, `StoreKitBridgeImpl.swift` drives StoreKit 2 (`Product.products(for: ["ahp_pro_unlock"])`
 * and `Transaction.currentEntitlements`) through this driver.
 */
interface StoreKitBridge {
    fun queryEntitlements(productId: String, callback: (records: List<StorePurchaseRecord>?, errorMessage: String?) -> Unit)
    fun purchase(productId: String, callback: (code: Int, records: List<StorePurchaseRecord>?, errorMessage: String?) -> Unit)
}

class IosBillingDriver(
    private val onPurchasesUpdated: (List<StorePurchase>) -> Unit = {},
) : StoreBillingDriver {

    var bridge: StoreKitBridge? = null

    fun notifyTransactionUpdate(purchases: List<StorePurchaseRecord>) {
        onPurchasesUpdated(purchases.map { it.toStorePurchase() })
    }

    override suspend fun queryPurchases(): StoreQueryResult {
        val sk = bridge ?: return StoreQueryResult.Error("App Store billing is not configured in this build.")
        return suspendCancellableCoroutine { cont ->
            sk.queryEntitlements(BillingGateway.PRODUCT_ID) { records, errorMessage ->
                val res = if (errorMessage != null) {
                    StoreQueryResult.Error(errorMessage)
                } else {
                    StoreQueryResult.Success(records.orEmpty().map { it.toStorePurchase() })
                }
                if (cont.isActive) cont.resumeWith(Result.success(res))
            }
        }
    }

    override suspend fun acknowledgePurchase(purchaseToken: String): Boolean = true

    override suspend fun launchPurchaseFlow(productId: String): StoreLaunchResult {
        val sk = bridge ?: return StoreLaunchResult.Error("App Store billing is not configured in this build.")
        return suspendCancellableCoroutine { cont ->
            sk.purchase(productId) { code, records, errorMessage ->
                val purchases = records.orEmpty().map { it.toStorePurchase() }
                if (code == 0 && purchases.isNotEmpty()) {
                    onPurchasesUpdated(purchases)
                }
                val res = when (code) {
                    0 -> StoreLaunchResult.Purchased(purchases)
                    1 -> StoreLaunchResult.Cancelled
                    else -> StoreLaunchResult.Error(errorMessage ?: "Purchase could not be completed.")
                }
                if (cont.isActive) cont.resumeWith(Result.success(res))
            }
        }
    }
}
