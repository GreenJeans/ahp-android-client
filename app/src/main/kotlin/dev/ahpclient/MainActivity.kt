package dev.ahpclient

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.ahpclient.ui.AppRoot
import dev.ahpclient.ui.copyToClipboard
import dev.ahpclient.ui.openInBrowser

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as AhpApp).container
        container.start()
        setContent {
            AppRoot(
                controller = container.controller,
                onOpenGitHub = { url ->
                    if (!openInBrowser(this, url)) Toast.makeText(this, "No browser found. Open the address on another device.", Toast.LENGTH_LONG).show()
                },
                onCopyCode = { code -> copyToClipboard(this, "GitHub code", code) },
            )
        }
    }
}
