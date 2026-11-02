package dev.ahpclient.security

import dev.ahpclient.platform.WeakReference
import dev.ahpclient.platform.platformSynchronized

/**
 * The screens that are on show right now, newest last, so something that needs a screen (the device check) can ask the one
 * on top and fall back to the one below when it goes away. Held weakly: a screen that has been dropped without saying so is
 * simply skipped, never kept alive.
 */
class AttachedStack<T : Any> {
    private val items = ArrayList<WeakReference<T>>()

    /** Puts [item] on top. One that is already there moves to the top rather than appearing twice. */
    fun attach(item: T) {
        platformSynchronized(items) {
            items.removeAll { it.get() == null || it.get() === item }
            items += WeakReference(item)
        }
    }

    /** Takes [item] out wherever it is, so whatever was below it is on top again. Leaving one that is not there changes nothing. */
    fun detach(item: T) {
        platformSynchronized(items) { items.removeAll { it.get() == null || it.get() === item } }
    }

    /** The newest item that is still alive, or null when there is none. */
    fun top(): T? = platformSynchronized(items) { items.asReversed().firstNotNullOfOrNull { it.get() } }
}
