@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.ahpclient

import com.microsoft.agenthostprotocol.generated.Implementation
import dev.ahpclient.ai.CloudQuickReplyController
import dev.ahpclient.ai.QuickReplyController
import dev.ahpclient.app.AppController
import dev.ahpclient.app.BillingGateway
import dev.ahpclient.app.BillingResult
import dev.ahpclient.app.DirectSessionFactory
import dev.ahpclient.app.FinishedAlerts
import dev.ahpclient.app.HostSessionFactory
import dev.ahpclient.app.InteractionConsent
import dev.ahpclient.app.MonitorCoordinator
import dev.ahpclient.app.OnScreen
import dev.ahpclient.app.ProEntitlement
import dev.ahpclient.app.StoreBillingGateway
import dev.ahpclient.auth.GitHubDeviceAuth
import dev.ahpclient.billing.IosBillingDriver
import dev.ahpclient.data.KeyValueStore
import dev.ahpclient.data.PrefsDirectHostStore
import dev.ahpclient.data.PrefsEntitlementSettings
import dev.ahpclient.data.PrefsFinishedWatch
import dev.ahpclient.data.PrefsInteractionSettings
import dev.ahpclient.data.PrefsLastHostStore
import dev.ahpclient.data.PrefsMonitorSettings
import dev.ahpclient.data.UiSettings
import dev.ahpclient.http.IosHttpTransport
import dev.ahpclient.monitor.IosMonitorService
import dev.ahpclient.monitor.IosNetworkMonitor
import dev.ahpclient.monitor.IosNotificationCategories
import dev.ahpclient.monitor.IosNotificationFinishedSink
import dev.ahpclient.monitor.IosNotificationInputNeededSink
import dev.ahpclient.monitor.IosNotificationMonitorProblemSink
import dev.ahpclient.platform.AtomicBoolean
import dev.ahpclient.security.DataStoreBlobStore
import dev.ahpclient.security.DirectHostVault
import dev.ahpclient.security.IosApprovalGate
import dev.ahpclient.security.IosFileBlobStore
import dev.ahpclient.security.KeychainSecureBox
import dev.ahpclient.security.SessionListCache
import dev.ahpclient.security.TokenVault
import dev.ahpclient.state.ConnState
import dev.ahpclient.state.DirectHostConnector
import dev.ahpclient.state.HostSession
import dev.ahpclient.state.InteractResult
import dev.ahpclient.state.TunnelHostConnector
import dev.ahpclient.tunnel.DevTunnelsClient
import dev.ahpclient.tunnel.IosSocketOpener
import dev.ahpclient.tunnel.MethodLoggingSocketOpener
import dev.ahpclient.tunnel.SocketOpener
import dev.ahpclient.tunnel.TunnelGateway
import dev.ahpclient.tunnel.describeFailure
import dev.ahpclient.ui.IosPlatformUiActions
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUserDomainMask

class NSUserDefaultsKeyValueStore(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
) : KeyValueStore {
    override fun getFloat(key: String, defaultValue: Float): Float =
        if (defaults.objectForKey(key) != null) defaults.floatForKey(key) else defaultValue

    override fun putFloat(key: String, value: Float) {
        defaults.setFloat(value, forKey = key)
    }

    override fun getString(key: String, defaultValue: String?): String? =
        defaults.stringForKey(key) ?: defaultValue

    override fun putString(key: String, value: String) {
        defaults.setObject(value, forKey = key)
    }

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        if (defaults.objectForKey(key) != null) defaults.boolForKey(key) else defaultValue

    override fun putBoolean(key: String, value: Boolean) {
        defaults.setBool(value, forKey = key)
    }

    override fun contains(key: String): Boolean = defaults.objectForKey(key) != null

    override fun remove(key: String) {
        defaults.removeObjectForKey(key)
    }
}

/**
 * iOS dependency container mirroring Android's `AppContainer`.
 *
 * In Milestone 1 dev builds (`debuggable = true`), [ProEntitlement] defaults to unlocked (`defaultUnlocked = true`)
 * and exposes the Settings debug switch so all Pro features (multi-host, session creation/management, custom agents,
 * slash commands, inline diffs, terminals, and quick replies) can be tested immediately on iPad.
 */
class IosAppContainer(
    val debuggable: Boolean = true,
) {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val started = AtomicBoolean(false)

    private val http = IosHttpTransport()
    private val versionName: String =
        (NSBundle.mainBundle.infoDictionary?.get("CFBundleShortVersionString") as? String) ?: "0.1.0"

    private val prefs by lazy {
        PrefsLastHostStore.dataStoreForPath(appSupportFilePath("ahp_prefs.preferences_pb"), ioScope)
    }

    val ui: UiSettings by lazy {
        IosPlatformUiActions.isDebuggable = debuggable
        UiSettings(NSUserDefaultsKeyValueStore())
    }

    private val googleAiVault by lazy {
        DirectHostVault(
            KeychainSecureBox("ahp-ios-client.google-ai-key.v1"),
            IosFileBlobStore(IosFileBlobStore.noBackupFilePath("google_ai_key.bin")),
        )
    }

    val quickReplyProvider: QuickReplyController by lazy {
        CloudQuickReplyController(
            scope = appScope,
            transport = http,
            ui = ui,
            apiKeyVault = googleAiVault,
        )
    }

    val approvalGate = IosApprovalGate()

    val pro: ProEntitlement by lazy {
        ProEntitlement(
            settings = PrefsEntitlementSettings(prefs, defaultUnlocked = debuggable),
            alwaysUnlocked = false,
            initialUnlocked = debuggable,
        )
    }

    val billingDriver: IosBillingDriver by lazy {
        IosBillingDriver { purchases ->
            appScope.launch {
                if (storeBilling.evaluatePurchases(purchases) is BillingResult.Unlocked) {
                    controller.setProUnlocked(true)
                }
            }
        }
    }

    private val storeBilling: StoreBillingGateway by lazy { StoreBillingGateway(billingDriver) }

    private val billingGateway: BillingGateway by lazy {
        if (debuggable) {
            object : BillingGateway {
                override suspend fun purchasePro(): BillingResult = BillingResult.Unlocked
                override suspend fun restorePurchases(): BillingResult = BillingResult.Unlocked
            }
        } else {
            storeBilling
        }
    }

    private val tokenVault by lazy {
        val path = appSupportFilePath("ahp_tokens.preferences_pb")
        TokenVault(KeychainSecureBox(), DataStoreBlobStore(DataStoreBlobStore.dataStoreForPath(path, ioScope)))
    }

    private val directVault by lazy {
        val path = appSupportFilePath("ahp_direct_tokens.preferences_pb")
        DirectHostVault(
            KeychainSecureBox("ahp-ios-client.direct-hosts-key.v1"),
            DataStoreBlobStore(DataStoreBlobStore.dataStoreForPath(path, ioScope)),
        )
    }

    private val sessionList by lazy {
        SessionListCache(
            KeychainSecureBox(),
            IosFileBlobStore(IosFileBlobStore.noBackupFilePath("session_list.bin")),
        )
    }

    private val monitorSettings by lazy { PrefsMonitorSettings(prefs) }
    private val monitorProblems by lazy { IosNotificationMonitorProblemSink() }

    private val interaction by lazy {
        InteractionConsent(
            PrefsInteractionSettings(prefs),
            supported = true,
            managementSettings = PrefsInteractionSettings(prefs, PrefsInteractionSettings.MANAGEMENT_KEY),
            entitled = { pro.unlocked.value },
        )
    }

    private val network by lazy { IosNetworkMonitor() }
    val monitorService by lazy { IosMonitorService() }

    private val directOpener by lazy { withWireLog(IosSocketOpener()) }

    val controller: AppController by lazy {
        val devTunnels = DevTunnelsClient(http, userAgent = "ahp-ios-client/$versionName")
        val gateway = TunnelGateway(withWireLog(IosSocketOpener()))
        val clientInfo = Implementation(name = "ahp-ios-client", version = versionName)
        AppController(
            auth = GitHubDeviceAuth(http),
            vault = tokenVault,
            devTunnels = devTunnels,
            lastHost = PrefsLastHostStore(prefs),
            sessions = HostSessionFactory { tunnel, accessToken ->
                HostSession(
                    TunnelHostConnector(devTunnels, gateway, tunnel, accessToken, policy = interaction::policy),
                    appScope,
                    clientInfo,
                    network = network.state,
                    cache = sessionList,
                    cacheKey = tunnel.tunnelId,
                    note = { if (debuggable) iosLog(CONN_TAG, it) },
                ).also { if (debuggable) logConnection(it) }
            },
            scope = appScope,
            interaction = interaction,
            approvalGate = approvalGate,
            note = { if (debuggable) iosLog(AUTH_TAG, it) },
            autoConnect = { pro.unlocked.value && monitorSettings.enabled.first() },
            afterSignOut = { monitorSettings.setEnabled(false) },
            sessionCache = sessionList,
            directStore = PrefsDirectHostStore(prefs),
            directVault = directVault,
            directSessions = DirectSessionFactory { host, token, consent ->
                HostSession(
                    DirectHostConnector(directOpener, host, token, policy = consent::policy),
                    appScope,
                    clientInfo,
                    network = network.state,
                    cache = sessionList,
                    cacheKey = host.id,
                    note = { if (debuggable) iosLog(CONN_TAG, it) },
                ).also { if (debuggable) logConnection(it) }
            },
            directConsent = { host ->
                InteractionConsent(
                    PrefsInteractionSettings(prefs, PrefsInteractionSettings.interactionKeyFor(host.id)),
                    supported = true,
                    managementSettings = PrefsInteractionSettings(prefs, PrefsInteractionSettings.managementKeyFor(host.id)),
                    entitled = { pro.unlocked.value },
                )
            },
            pro = pro,
            billing = billingGateway,
        )
    }

    val monitor: MonitorCoordinator by lazy {
        MonitorCoordinator(
            state = controller.state,
            settings = monitorSettings,
            service = monitorService,
            sink = IosNotificationInputNeededSink(canDecide = { controller.interactionAllowed.value }),
            scope = appScope,
            finished = FinishedAlerts(
                PrefsFinishedWatch(prefs),
                IosNotificationFinishedSink(),
                viewing = { OnScreen.session(inForeground, controller.state.value) },
            ),
            problems = monitorProblems,
            entitled = pro.unlocked,
        )
    }

    @Volatile
    var inForeground: Boolean = true

    fun setWatchAll(on: Boolean) {
        appScope.launch { monitor.setWatchAll(on) }
    }

    fun setWatched(resource: String, on: Boolean) {
        appScope.launch { monitor.setWatched(resource, on) }
    }

    fun setMonitoring(on: Boolean) {
        if (on) {
            IosNotificationCategories.requestAuthorizationIfNeeded()
        }
        appScope.launch { monitor.setEnabled(on) }
    }

    fun decideFromNotification(chatUri: String, toolCallId: String, approve: Boolean, optionId: String?, done: (InteractResult) -> Unit) {
        start()
        appScope.launch {
            val result = try {
                controller.decideToolCall(chatUri, toolCallId, approve, optionId, background = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                InteractResult.NotSent(e)
            }
            withContext(Dispatchers.Main) { done(result) }
        }
    }

    fun answerFromNotification(chatUri: String, requestId: String, text: String, done: (InteractResult) -> Unit) {
        start()
        appScope.launch {
            val result = try {
                controller.answerQuestion(chatUri, requestId, text, background = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                InteractResult.NotSent(e)
            }
            withContext(Dispatchers.Main) { done(result) }
        }
    }

    fun start() {
        if (started.compareAndSet(false, true)) {
            IosNotificationCategories.registerCategories()
            controller.start()
            monitor.start()
            if (!debuggable) {
                appScope.launch {
                    controller.reconcileProEntitlement()
                }
            }
        }
    }

    private fun withWireLog(opener: SocketOpener): SocketOpener =
        if (debuggable) MethodLoggingSocketOpener(opener) { iosLog(WIRE_TAG, it) } else opener

    private fun logConnection(session: HostSession) {
        appScope.launch {
            session.connection.transformWhile { state -> emit(state); state != ConnState.Stopped }.collect { state ->
                val msg = when (state) {
                    is ConnState.Reconnecting -> "reconnecting, attempt ${state.attempt}: ${state.cause?.let(::describeFailure) ?: "no cause"}"
                    is ConnState.Failed -> "failed: ${describeFailure(state.cause)}"
                    else -> state::class.simpleName ?: "unknown"
                }
                iosLog(CONN_TAG, msg)
            }
        }
    }

    private fun iosLog(tag: String, message: String) {
        println("[$tag] $message")
    }

    private fun appSupportFilePath(fileName: String): String {
        val paths = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true)
        val baseDir = (paths.firstOrNull() as? String) ?: ""
        val manager = NSFileManager.defaultManager
        if (!manager.fileExistsAtPath(baseDir)) {
            manager.createDirectoryAtPath(baseDir, withIntermediateDirectories = true, attributes = null, error = null)
        }
        return "$baseDir/$fileName"
    }

    companion object {
        const val WIRE_TAG = "AhpWire"
        const val CONN_TAG = "AhpConn"
        const val AUTH_TAG = "AhpAuth"

        val shared: IosAppContainer by lazy { IosAppContainer(debuggable = true) }
    }
}
