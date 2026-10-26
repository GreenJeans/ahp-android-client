package dev.ahpclient

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner

class AhpApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // The process, not one activity: the approval screen in front counts as in sight, and a rotation, which stops one activity
        // and starts the next, is not a stop (the process reports it a moment late). The container is only built when an event arrives.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> container.controller.setAppVisible(true)
                    Lifecycle.Event.ON_STOP -> container.controller.setAppVisible(false)
                    else -> Unit
                }
            },
        )
    }
}
