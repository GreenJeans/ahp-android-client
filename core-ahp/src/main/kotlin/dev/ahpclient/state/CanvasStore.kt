package dev.ahpclient.state

import com.microsoft.agenthostprotocol.canvasReducer
import com.microsoft.agenthostprotocol.generated.CanvasState
import com.microsoft.agenthostprotocol.generated.SnapshotState

/** A live, reducer-backed canvas channel. */
typealias CanvasStore = ChannelStore<CanvasState>

internal fun canvasStore(uri: String): CanvasStore =
    ChannelStore(uri, { (it as? SnapshotState.Canvas)?.value }, ::canvasReducer)
