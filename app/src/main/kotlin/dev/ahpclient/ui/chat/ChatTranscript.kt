package dev.ahpclient.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import dev.ahpclient.state.ChatStore
import dev.ahpclient.state.Transcript
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * The store's chat as display rows, built off the main thread: a long chat has thousands of parts, and the store publishes
 * up to twenty times a second while the agent streams. If building falls behind, the newest state wins and the ones in
 * between are skipped. Null until the host's snapshot arrives.
 */
@Composable
fun rememberTranscript(store: ChatStore): State<Transcript?> {
    val transcript = remember(store) { mutableStateOf<Transcript?>(null) }
    LaunchedEffect(store) {
        store.state.map { chat -> chat?.let(Transcript::from) }.flowOn(Dispatchers.Default).conflate().collect { transcript.value = it }
    }
    return transcript
}
