package dev.ahpclient.ui

/**
 * Opens the system's chooser for files to attach to a message. What the person picks comes back through the callbacks given to
 * `rememberAttachmentPicker`, which each platform provides: files read and bounded by [dev.ahpclient.state.Attachments], or the
 * reason one could not be read. A platform with no chooser yet gets no picker, and the screen shows no attach button.
 */
fun interface AttachmentPicker {
    fun open()
}
