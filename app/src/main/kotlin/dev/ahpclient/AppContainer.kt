package dev.ahpclient

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Log
import androidx.datastore.preferences.preferencesDataStoreFile
import dev.ahpclient.app.AppController
import dev.ahpclient.app.FinishedAlerts
import dev.ahpclient.app.HostSessionFactory
import dev.ahpclient.app.InteractionConsent
import dev.ahpclient.app.MonitorCoordinator
import dev.ahpclient.app.OnScreen
import dev.ahpclient.auth.GitHubDeviceAuth
import dev.ahpclient.data.PrefsFinishedWatch
import dev.ahpclient.data.PrefsInteractionSettings
import dev.ahpclient.data.PrefsLastHostStore
import dev.ahpclient.data.UiSettings
import dev.ahpclient.data.PrefsMonitorSettings
import dev.ahpclient.monitor.AndroidMonitorService
import dev.ahpclient.monitor.NotificationFinishedSink
import dev.ahpclient.monitor.NotificationInputNeededSink
import dev.ahpclient.security.AndroidTokenVault
import dev.ahpclient.security.BiometricApprovalGate
import dev.ahpclient.state.ConnState
import dev.ahpclient.state.HostSession
import dev.ahpclient.state.InteractResult
import dev.ahpclient.state.TunnelHostConnector
import dev.ahpclient.tunnel.DevTunnelsClient
import dev.ahpclient.tunnel.MethodLoggingSocketOpener
import dev.ahpclient.tunnel.OkHttpSocketOpener
import dev.ahpclient.tunnel.SocketOpener
import dev.ahpclient.tunnel.TunnelGateway
import dev.ahpclient.tunnel.describeFailure
import com.microsoft.agenthostprotocol.generated.Implementation
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * Builds the app's long-lived objects once per process, by plain constructor injection. DataStore allows only one
 * instance per file, so the vault and the preferences are created here and nowhere else.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)

    private val http = OkHttpClient.Builder().pingInterval(30, TimeUnit.SECONDS).build()
    private val versionName: String = appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: "0"
    private val prefs by lazy { PrefsLastHostStore.dataStoreFor(appContext.preferencesDataStoreFile("ahp_prefs"), ioScope) }

    /** How the app looks, and the browser links open in. */
    val ui: UiSettings by lazy { UiSettings(appContext.getSharedPreferences("ahp_ui", Context.MODE_PRIVATE)) }

    /** The phone's own prompt in front of every tool approval. Attached to the activity while it is on screen. */
    val approvalGate = BiometricApprovalGate()

    val controller: AppController by lazy {
        val devTunnels = DevTunnelsClient(http, userAgent = "ahp-android-client/$versionName")
        val gateway = TunnelGateway(withWireLog(OkHttpSocketOpener(http)))
        val clientInfo = Implementation(name = "ahp-android-client", version = versionName)
        val debuggable = appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        AppController(
            auth = GitHubDeviceAuth(http),
            vault = AndroidTokenVault.create(appContext, ioScope),
            devTunnels = devTunnels,
            lastHost = PrefsLastHostStore(prefs),
            sessions = HostSessionFactory { tunnel, accessToken ->
                HostSession(TunnelHostConnector(devTunnels, gateway, tunnel, accessToken, policy = interaction::policy), appScope, clientInfo)
                    .also { if (debuggable) logConnection(it) }
            },
            scope = appScope,
            interaction = interaction,
            approvalGate = approvalGate,
            // Debug builds only: says that a sign-in was renewed or refused, never anything of the token.
            note = { if (debuggable) Log.i(AUTH_TAG, it) },
        )
    }

    /** Off until the user turns it on, and off again at sign-out. Decides the tier of every new connection. */
    private val interaction by lazy {
        // The device check in front of approvals needs Android 11 (R); before that the tier can never be INTERACT.
        InteractionConsent(
            PrefsInteractionSettings(prefs),
            supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
            managementSettings = PrefsInteractionSettings(prefs, PrefsInteractionSettings.MANAGEMENT_KEY),
        )
    }

    private val monitorService by lazy { AndroidMonitorService(appContext) }

    /** Decides when the foreground service and the "needs input" notifications run. Off until the user turns it on. */
    val monitor: MonitorCoordinator by lazy {
        MonitorCoordinator(
            state = controller.state,
            settings = PrefsMonitorSettings(prefs),
            service = monitorService,
            // Buttons on a notification are offered only while the user has let the app act for them.
            sink = NotificationInputNeededSink(appContext, canDecide = { controller.interactionAllowed.value }),
            scope = appScope,
            // The session on screen is never alerted about, but only while the app is in front: pressing Home with it open is not looking.
            finished = FinishedAlerts(PrefsFinishedWatch(prefs), NotificationFinishedSink(appContext), viewing = { OnScreen.session(inForeground, controller.state.value) }),
        )
    }

    /** Set by the activity as it comes to the front and leaves it. */
    @Volatile
    var inForeground = false

    fun setWatchAll(on: Boolean) {
        appScope.launch { monitor.setWatchAll(on) }
    }

    fun setWatched(resource: String, on: Boolean) {
        appScope.launch { monitor.setWatched(resource, on) }
    }

    /**
     * Decides one waiting tool call for a notification button, off the main thread, and reports back on it. The decision
     * itself is [AppController.decideToolCall]: nothing here adds a way around its checks.
     */
    fun decideFromNotification(chatUri: String, toolCallId: String, approve: Boolean, optionId: String?, done: (InteractResult) -> Unit) {
        appScope.launch {
            val result = try {
                controller.decideToolCall(chatUri, toolCallId, approve, optionId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                InteractResult.NotSent(e)
            }
            withContext(Dispatchers.Main) { done(result) }
        }
    }

    /** Starts the service again if monitoring is on, which posts its notification afresh. Safe to repeat. */
    fun refreshMonitorNotification() {
        if (monitor.status.value != null) monitorService.start()
    }

    fun setMonitoring(on: Boolean) {
        appScope.launch { monitor.setEnabled(on) }
    }

    /**
     * Debug builds only: one logcat line per outbound frame under the tag `AhpWire`, holding the method name and nothing
     * else, so a run on a real device can show that only read-tier calls were sent. Release builds log nothing.
     */
    private fun withWireLog(opener: SocketOpener): SocketOpener {
        val debuggable = appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        return if (debuggable) MethodLoggingSocketOpener(opener) { Log.i(WIRE_TAG, it) } else opener
    }

    /**
     * Debug builds only: one logcat line each time the connection changes state, under the tag `AhpConn`, with why it was lost
     * (the classes of the causes and a close code, see [describeFailure]). A reconnect loop is otherwise silent.
     */
    private fun logConnection(session: HostSession) {
        appScope.launch {
            session.connection.transformWhile { state -> emit(state); state != ConnState.Stopped }.collect { state ->
                Log.i(CONN_TAG, when (state) {
                    is ConnState.Reconnecting -> "reconnecting, attempt ${state.attempt}: ${state.cause?.let(::describeFailure) ?: "no cause"}"
                    is ConnState.Failed -> "failed: ${describeFailure(state.cause)}"
                    else -> state.javaClass.simpleName
                })
            }
        }
    }

    /** Safe to call from every Activity start: only the first call begins restoring the saved sign-in. */
    fun start() {
        if (started.compareAndSet(false, true)) {
            controller.start()
            monitor.start()
        }
    }

    companion object {
        const val WIRE_TAG = "AhpWire"
        const val CONN_TAG = "AhpConn"
        const val AUTH_TAG = "AhpAuth"
    }
}
