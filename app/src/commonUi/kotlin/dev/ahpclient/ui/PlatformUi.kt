package dev.ahpclient.ui

interface PlatformUiActions {
    val isDebuggable: Boolean
    fun showToast(message: String)
    fun copyToClipboard(label: String, text: String)
    fun shareText(text: String, title: String)
    fun openLink(raw: String): Boolean
    fun shareLink(url: String): Boolean
    fun leaveApp()
}
