package dev.ahpclient.auth

import kotlin.jvm.JvmInline

/** Holds a credential so it cannot leak through logging or string templates. */
@JvmInline
value class Secret(private val value: String) {
    fun reveal(): String = value

    override fun toString(): String = "***"
}
