package dev.ahpclient.app

import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * The saved record of whether the user has unlocked the paid Pro tier (acting for the user, looking after sessions,
 * background monitoring & notifications, and concurrent multi-host connections).
 */
interface EntitlementSettings {
    val unlocked: Flow<Boolean>

    /** @throws IOException when the entitlement state cannot be saved. */
    suspend fun setUnlocked(unlocked: Boolean)
}

/**
 * Keeps the entitlement state in memory for this process. Defaults to [initial] = true so that existing unit tests and
 * personal/debug builds have the full feature set unless explicitly testing the Free tier.
 */
class InMemoryEntitlementSettings(initial: Boolean = true) : EntitlementSettings {
    private val value = MutableStateFlow(initial)
    override val unlocked: Flow<Boolean> = value

    override suspend fun setUnlocked(unlocked: Boolean) {
        value.value = unlocked
    }
}

/**
 * Controls whether Pro features are unlocked:
 * - **Free tier ([unlocked] == false):** Read-only monitor (`CapabilityPolicy.READ`) on one host at a time, with live
 *   streaming transcripts, diffs, and terminals while the app is open.
 * - **Pro tier ([unlocked] == true):** Acting for the user (`INTERACT`), looking after sessions (`MANAGE`), background
 *   monitoring & notifications (`MonitorService`, inline reply, Quick Settings tile), and concurrent multi-host connections.
 *
 * When [alwaysUnlocked] is true (used in debug builds), [unlocked] stays true regardless of what is stored on disk.
 * When [alwaysUnlocked] is false (release builds), an unreadable store fails closed (`false`) until the billing client
 * or local store confirms the unlock.
 */
class ProEntitlement(
    private val settings: EntitlementSettings = InMemoryEntitlementSettings(true),
    val alwaysUnlocked: Boolean = false,
    initialUnlocked: Boolean = (settings as? InMemoryEntitlementSettings)?.let { readInitial(it) } ?: false,
) {
    private val _unlocked = MutableStateFlow(alwaysUnlocked || initialUnlocked)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    /** Reads the saved entitlement state. Fails closed (`false`) on [IOException] unless [alwaysUnlocked] is true. */
    suspend fun load() {
        if (alwaysUnlocked) {
            _unlocked.value = true
            return
        }
        _unlocked.value = try {
            settings.unlocked.first()
        } catch (_: IOException) {
            false
        }
    }

    /**
     * Grants or revokes the Pro entitlement. Revoking (`false`) takes effect in memory at once before disk write (unless
     * [alwaysUnlocked] is true); granting (`true`) takes effect once saved.
     *
     * @return true when the state was saved (or when [alwaysUnlocked] is true).
     */
    suspend fun setUnlocked(on: Boolean): Boolean {
        if (alwaysUnlocked) {
            _unlocked.value = true
            return true
        }
        if (!on) {
            _unlocked.value = false
        }
        return try {
            settings.setUnlocked(on)
            if (on) _unlocked.value = true
            true
        } catch (_: IOException) {
            false
        }
    }

    private companion object {
        fun readInitial(settings: InMemoryEntitlementSettings): Boolean =
            (settings.unlocked as? StateFlow<Boolean>)?.value ?: true
    }
}

/** Result of an in-app purchase or restore request for the Pro unlock ([BillingGateway.PRODUCT_ID]). */
sealed interface BillingResult {
    /** The Pro entitlement was purchased or restored and is active. */
    data object Unlocked : BillingResult

    /** The user dismissed the purchase flow without completing it. */
    data object Cancelled : BillingResult

    /** No existing Pro purchase was found on restore. */
    data object NotFound : BillingResult

    /** The billing store is not yet configured or unreachable, with a plain-text explanation. */
    data class Unavailable(val message: String) : BillingResult
}

/**
 * Platform billing gateway for the one-time Pro unlock ([PRODUCT_ID]).
 *
 * Implemented by Google Play Billing in `:app` once the Play Console product is live, and by [UnavailableBillingGateway]
 * during pre-release or in environments without Play Services.
 */
interface BillingGateway {
    suspend fun purchasePro(): BillingResult
    suspend fun restorePurchases(): BillingResult

    companion object {
        const val PRODUCT_ID = "ahp_pro_unlock"
    }
}

/** Default pre-release billing gateway before Google Play Billing is connected to a live Play Console listing. */
object UnavailableBillingGateway : BillingGateway {
    const val DEFAULT_MESSAGE = "In-app purchases are not yet active in this pre-release build."

    override suspend fun purchasePro(): BillingResult = BillingResult.Unavailable(DEFAULT_MESSAGE)
    override suspend fun restorePurchases(): BillingResult = BillingResult.Unavailable(DEFAULT_MESSAGE)
}

/** State of a store purchase record for [BillingGateway.PRODUCT_ID]. */
enum class StorePurchaseState {
    PURCHASED,
    PENDING,
    UNSPECIFIED,
}

/** Platform-neutral representation of an in-app purchase record returned by the store SDK. */
data class StorePurchase(
    val products: List<String>,
    val state: StorePurchaseState,
    val isAcknowledged: Boolean,
    val purchaseToken: String,
)

/** Result of querying owned/pending in-app purchases from the store SDK. */
sealed interface StoreQueryResult {
    data class Success(val purchases: List<StorePurchase>) : StoreQueryResult
    data class Error(val message: String) : StoreQueryResult
}

/** Result of launching the store purchase sheet on the foreground screen. */
sealed interface StoreLaunchResult {
    data class Purchased(val purchases: List<StorePurchase>) : StoreLaunchResult
    data object Cancelled : StoreLaunchResult
    data object AlreadyOwned : StoreLaunchResult
    data class Error(val message: String) : StoreLaunchResult
}

/**
 * Low-level store operations backed by Google Play Billing (`BillingClient`) on Android.
 * Kept platform-independent so [StoreBillingGateway] can be tested in pure JVM unit tests.
 */
interface StoreBillingDriver {
    /** Queries currently owned or pending in-app purchases from the store. */
    suspend fun queryPurchases(): StoreQueryResult

    /** Acknowledges a completed purchase by its [purchaseToken] so Google Play does not auto-refund it after 3 days. */
    suspend fun acknowledgePurchase(purchaseToken: String): Boolean

    /** Launches the store purchase sheet on the foreground screen and returns the resulting purchase update. */
    suspend fun launchPurchaseFlow(productId: String): StoreLaunchResult
}

/**
 * Coordinates purchasing and restoring [BillingGateway.PRODUCT_ID] against a [StoreBillingDriver], ensuring every
 * completed purchase is acknowledged with the store before reporting [BillingResult.Unlocked].
 */
class StoreBillingGateway(
    private val driver: StoreBillingDriver,
    private val productId: String = BillingGateway.PRODUCT_ID,
) : BillingGateway {

    override suspend fun purchasePro(): BillingResult {
        // Check existing purchases first so a user who already owns Pro on another device unlocks immediately without launching a sheet.
        val existing = restorePurchases()
        if (existing is BillingResult.Unlocked) return BillingResult.Unlocked

        return when (val launched = driver.launchPurchaseFlow(productId)) {
            StoreLaunchResult.Cancelled -> BillingResult.Cancelled
            StoreLaunchResult.AlreadyOwned -> {
                val restored = restorePurchases()
                if (restored is BillingResult.NotFound) {
                    BillingResult.Unavailable("Already owned in Google Play, but purchase record could not be loaded.")
                } else {
                    restored
                }
            }
            is StoreLaunchResult.Error -> BillingResult.Unavailable(launched.message)
            is StoreLaunchResult.Purchased -> evaluatePurchases(launched.purchases, onMissing = {
                BillingResult.Unavailable("Purchase did not include Pro unlock.")
            })
        }
    }

    override suspend fun restorePurchases(): BillingResult {
        return when (val queried = driver.queryPurchases()) {
            is StoreQueryResult.Error -> BillingResult.Unavailable(queried.message)
            is StoreQueryResult.Success -> evaluatePurchases(queried.purchases, onMissing = { BillingResult.NotFound })
        }
    }

    /** Evaluates store purchases for [productId], acknowledging any unacknowledged [StorePurchaseState.PURCHASED] record first. */
    suspend fun evaluatePurchases(
        purchases: List<StorePurchase>,
        onMissing: () -> BillingResult = { BillingResult.NotFound },
    ): BillingResult {
        val matching = purchases.filter { productId in it.products }
        val purchased = matching.firstOrNull { it.state == StorePurchaseState.PURCHASED }
        if (purchased != null) {
            if (!purchased.isAcknowledged) {
                val ok = driver.acknowledgePurchase(purchased.purchaseToken)
                if (!ok) {
                    return BillingResult.Unavailable("Could not acknowledge the purchase with Google Play. Try Restore purchase.")
                }
            }
            return BillingResult.Unlocked
        }
        if (matching.any { it.state == StorePurchaseState.PENDING }) {
            return BillingResult.Unavailable("Purchase is pending confirmation in Google Play.")
        }
        return onMissing()
    }
}
