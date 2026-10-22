package dev.ahpclient

import android.app.Application

class AhpApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
