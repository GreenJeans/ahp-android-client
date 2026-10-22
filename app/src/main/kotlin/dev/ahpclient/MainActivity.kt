package dev.ahpclient

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.microsoft.agenthostprotocol.generated.SUPPORTED_PROTOCOL_VERSIONS

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Placeholder()
                }
            }
        }
    }
}

// Scaffold screen: proves :core-ahp links into the app. Replaced by the sign-in flow in Step 1.9.
@Composable
private fun Placeholder() {
    Column(
        modifier = Modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("AHP Client", style = MaterialTheme.typography.headlineMedium)
        Text("Read-only monitor (Phase 1)", style = MaterialTheme.typography.bodyLarge)
        Text("Protocol versions: ${SUPPORTED_PROTOCOL_VERSIONS.joinToString()}")
    }
}
