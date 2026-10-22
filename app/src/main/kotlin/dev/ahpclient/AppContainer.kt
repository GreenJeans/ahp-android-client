package dev.ahpclient

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.datastore.preferences.preferencesDataStoreFile
import dev.ahpclient.app.AppController
import dev.ahpclient.app.HostSessionFactory
import dev.ahpclient.app.MonitorCoordinator
import dev.ahpclient.auth.GitHubDeviceAuth
import dev.ahpclient.data.PrefsLastHostStore
import dev.ahpclient.data.PrefsMonitorSettings
import dev.ahpclient.monitor.AndroidMonitorService
import dev.ahpclient.monitor.NotificationInputNeededSink
import dev.ahpclient.security.AndroidTokenVault
import dev.ahpclient.state.HostSession
import dev.ahpclient.state.TunnelHostConnector
import dev.ahpclient.tunnel.DevTunnelsClient
import dev.ahpclient.tunnel.MethodLoggingSocketOpener
import dev.ahpclient.tunnel.OkHttpSocketOpener
import dev.ahpclient.tunnel.SocketOpener
import dev.ahpclient.tunnel.TunnelGateway
import com.microsoft.agenthostprotocol.generated.Implementation
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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

    val controller: AppController by lazy {
        val devTunnels = DevTunnelsClient(http, userAgent = "ahp-android-client/$versionName")
        val gateway = TunnelGateway(withWireLog(OkHttpSocketOpener(http)))
        val clientInfo = Implementation(name = "ahp-android-client", version = versionName)
        AppController(
            auth = GitHubDeviceAuth(http),
            vault = AndroidTokenVault.create(appContext, ioScope),
            devTunnels = devTunnels,
            lastHost = PrefsLastHostStore(prefs),
            sessions = HostSessionFactory { tunnel, accessToken ->
                HostSession(TunnelHostConnector(devTunnels, gateway, tunnel, accessToken), appScope, clientInfo)
            },
            scope = appScope,
        )
    }

    /** Decides when the foreground service and the "needs input" notifications run. Off until the user turns it on. */
    val monitor: MonitorCoordinator by lazy {
        MonitorCoordinator(
            state = controller.state,
            settings = PrefsMonitorSettings(prefs),
            service = AndroidMonitorService(appContext),
            sink = NotificationInputNeededSink(appContext),
            scope = appScope,
        )
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

    /** Safe to call from every Activity start: only the first call begins restoring the saved sign-in. */
    fun start() {
        if (started.compareAndSet(false, true)) {
            controller.start()
            monitor.start()
        }
    }

    companion object {
        const val WIRE_TAG = "AhpWire"
    }
}
