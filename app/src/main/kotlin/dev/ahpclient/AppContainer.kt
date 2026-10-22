package dev.ahpclient

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import dev.ahpclient.app.AppController
import dev.ahpclient.app.HostSessionFactory
import dev.ahpclient.auth.GitHubDeviceAuth
import dev.ahpclient.data.PrefsLastHostStore
import dev.ahpclient.security.AndroidTokenVault
import dev.ahpclient.state.HostSession
import dev.ahpclient.state.TunnelHostConnector
import dev.ahpclient.tunnel.DevTunnelsClient
import dev.ahpclient.tunnel.OkHttpSocketOpener
import dev.ahpclient.tunnel.TunnelGateway
import com.microsoft.agenthostprotocol.generated.Implementation
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    val controller: AppController by lazy {
        val devTunnels = DevTunnelsClient(http, userAgent = "ahp-android-client/$versionName")
        val gateway = TunnelGateway(OkHttpSocketOpener(http))
        val clientInfo = Implementation(name = "ahp-android-client", version = versionName)
        AppController(
            auth = GitHubDeviceAuth(http),
            vault = AndroidTokenVault.create(appContext, ioScope),
            devTunnels = devTunnels,
            lastHost = PrefsLastHostStore(PrefsLastHostStore.dataStoreFor(appContext.preferencesDataStoreFile("ahp_prefs"), ioScope)),
            sessions = HostSessionFactory { tunnel, accessToken ->
                HostSession(TunnelHostConnector(devTunnels, gateway, tunnel, accessToken), appScope, clientInfo)
            },
            scope = appScope,
        )
    }

    /** Safe to call from every Activity start: only the first call begins restoring the saved sign-in. */
    fun start() {
        if (started.compareAndSet(false, true)) controller.start()
    }
}
