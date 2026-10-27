@file:OptIn(ExperimentalForInheritanceCoroutinesApi::class, ExperimentalCoroutinesApi::class)

package dev.ahpclient.app

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest

/**
 * A flag that follows whichever source is current: [read] gives its value now, and [sources] says which flag is current as that changes.
 * It is a [StateFlow] whose [value] is never behind (it reads the current source on every call, with no scope to wait for), so the
 * permission switches read the same right after a host is switched as after any other change.
 */
internal class ActiveFlag(
    private val read: () -> Boolean,
    private val sources: Flow<StateFlow<Boolean>>,
) : StateFlow<Boolean> {
    override val value: Boolean get() = read()

    override val replayCache: List<Boolean> get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<Boolean>): Nothing {
        sources.distinctUntilChanged().flatMapLatest { it }.distinctUntilChanged().collect(collector)
        awaitCancellation()
    }
}
