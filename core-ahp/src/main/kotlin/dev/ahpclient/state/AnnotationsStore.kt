package dev.ahpclient.state

import com.microsoft.agenthostprotocol.annotationsReducer
import com.microsoft.agenthostprotocol.generated.AnnotationsState
import com.microsoft.agenthostprotocol.generated.SnapshotState

/** A live, reducer-backed annotations channel. */
typealias AnnotationsStore = ChannelStore<AnnotationsState>

internal fun annotationsStore(uri: String): AnnotationsStore =
    ChannelStore(uri, { (it as? SnapshotState.Annotations)?.value }, ::annotationsReducer)
