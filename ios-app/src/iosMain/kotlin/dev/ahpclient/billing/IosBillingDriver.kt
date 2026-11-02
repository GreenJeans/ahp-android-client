package dev.ahpclient.billing

import dev.ahpclient.app.BillingCodes
import dev.ahpclient.app.PurchaseRecord
import dev.ahpclient.app.PurchasesQueryResult
import dev.ahpclient.app.StoreBillingDriver
import dev.ahpclient.app.StoreStatus

/**
 * StoreKit 2 bridge interface that the iOS Swift host (`StoreKitBridge.swift`) registers at launch.
 *
 * In Milestone 1 dev builds (`isDebuggable = true`), [dev.ahpclient.app.ProEntitlement] defaults to unlocked
 * and exposes the Settings debug switch so all Pro features can be tested without App Store Connect setup.
 * In Milestone 2 App Store builds, `StoreKitBridge.swift` drives StoreKit 2 (`Product.products(for: ["ahp_pro_unlock"])`
 * and `Transaction.currentEntitlements`) through this driver.
 */
interface StoreKitBridge {
    fun queryEntitlements(productId: String, callback: (code: Int, message: String?, purchased: Boolean, pending: Boolean, token: String) -> Unit)
    fun purchase(productId: String, callback: (code: Int, message: String?, purchased: Boolean, pending: Boolean, token: String) -> Unit)
}

class IosBillingDriver(
    private val onPurchasesUpdated: (List<PurchaseRecord>) -> Unit = {},
) : StoreBillingDriver {

    var bridge: StoreKitBridge? = null

    fun notifyTransactionUpdate(productId: String, purchased: Boolean, pending: Boolean, transactionId: String) {
        if (!purchased && !pending) {
            onPurchasesUpdated(emptyList())
        } else {
            onPurchasesUpdated(
                listOf(
                    PurchaseRecord(
                        products = listOf(productId),
                        purchaseToken = transactionId,
                        state = if (pending) PurchaseRecord.State.PENDING else PurchaseRecord.State.PURCHASED,
                        isAcknowledged = true,
                    ),
                ),
            )
        }
    }

    override suspend fun ensureConnected(): StoreStatus =
        if (bridge != null) {
            StoreStatus(BillingCodes.OK)
        } else {
            StoreStatus(BillingCodes.BILLING_UNAVAILABLE, "App Store billing is not configured in this build.")
        }

    override suspend fun isProductAvailable(productId: String): StoreStatus = ensureConnected()

    override suspend fun launchPurchase(productId: String): StoreStatus {
        val sk = bridge ?: return StoreStatus(BillingCodes.BILLING_UNAVAILABLE, "App Store billing is not configured in this build.")
        var status = StoreStatus(BillingCodes.ERROR, "Purchase did not complete.")
        kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
            sk.purchase(productId) { code, message, purchased, pending, token ->
                if (code == BillingCodes.OK && (purchased || pending)) {
                    notifyTransactionUpdate(productId, purchased, pending, token)
                }
                status = StoreStatus(code, message)
                if (cont.isActive) cont.resumeWith(Result.success(Unit))
            }
        }
        return status
    }

    override suspend fun queryOwnedPurchases(): PurchasesQueryResult {
        val sk = bridge ?: return PurchasesQueryResult(
            status = StoreStatus(BillingCodes.BILLING_UNAVAILABLE, "App Store billing is not configured in this build."),
            purchases = emptyList(),
        )
        var result: PurchasesQueryResult? = null
        kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
            sk.queryEntitlements("ahp_pro_unlock") { code, message, purchased, pending, token ->
                val list = if (purchased || pending) {
                    listOf(
                        PurchaseRecord(
                            products = listOf("ahp_pro_unlock"),
                            purchaseToken = token,
                            state = if (pending) PurchaseRecord.State.PENDING else PurchaseRecord.State.PURCHASED,
                            isAcknowledged = true,
                        ),
                    )
                } else {
                    emptyList()
                }
                result = PurchasesQueryResult(StoreStatus(code, message), list)
                if (cont.isActive) cont.resumeWith(Result.success(Unit))
            }
        }
        return result ?: PurchasesQueryResult(StoreStatus(BillingCodes.ERROR), emptyList())
    }

    override suspend fun acknowledgePurchase(purchaseToken: String): StoreStatus =
        StoreStatus(BillingCodes.OK)
}
