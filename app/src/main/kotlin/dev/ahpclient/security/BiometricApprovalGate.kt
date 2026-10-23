package dev.ahpclient.security

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dev.ahpclient.state.ApprovalGate
import dev.ahpclient.state.ApprovalRequest
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * The device check in front of every tool approval: the phone's own fingerprint, face or screen-lock prompt, naming the
 * tool about to be allowed. True only when the person has just passed it. Everything else is false: cancelled, no screen
 * lock set, no strong biometric, the app not on screen, or any error. A prompt cannot be shown without an activity, so
 * an approval that arrives while the app is in the background is refused rather than waiting to be tapped later. (An
 * approval started from a notification gets its activity from `ApproveActivity`, which attaches itself for the prompt.)
 *
 * Strong biometrics or the screen lock, from Android 11 only. Before that the library cannot offer the combination, and
 * the write tier is not available at all (see `InteractionConsent.supported`), so this gate refuses there instead of
 * accepting something weaker. No cryptographic key is bound to the approval: the check proves a person is present, and
 * nothing else vouches for what the host then does.
 */
class BiometricApprovalGate : ApprovalGate {
    private val screens = AttachedStack<FragmentActivity>()

    /** Call when the activity is on screen. The newest one is the one the prompt is shown over. */
    fun attach(activity: FragmentActivity) = screens.attach(activity)

    /** Call when it is not, so a prompt is never shown over an app that is not there. The one under it, if any, is used again. */
    fun detach(activity: FragmentActivity) = screens.detach(activity)

    override suspend fun confirm(request: ApprovalRequest): Boolean {
        // Before Android 11 the check cannot run (and consent cannot be turned on); refuse rather than ask for something weaker.
        if (!isSupported(Build.VERSION.SDK_INT)) return false
        val activity = screens.top() ?: return false
        // Prompts belong to the main thread, and checking first means a phone with no screen lock is told no, not shown nothing.
        return withContext(Dispatchers.Main) {
            if (BiometricManager.from(activity).canAuthenticate(AUTHENTICATORS) != BiometricManager.BIOMETRIC_SUCCESS) {
                false
            } else {
                prompt(activity, request)
            }
        }
    }

    private suspend fun prompt(activity: FragmentActivity, request: ApprovalRequest): Boolean =
        suspendCancellableCoroutine { continuation ->
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (continuation.isActive) continuation.resume(true)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (continuation.isActive) continuation.resume(false)
                }
            }
            val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
            // The tool's name and summary come from the host: plain text, and cut short.
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Approve this tool call?")
                .setSubtitle(request.toolName.take(MAX_TITLE))
                .setDescription(request.summary?.take(MAX_SUMMARY))
                .setAllowedAuthenticators(AUTHENTICATORS)
                .setConfirmationRequired(true)
                .build()
            continuation.invokeOnCancellation { prompt.cancelAuthentication() }
            prompt.authenticate(info)
        }

    companion object {
        private const val MAX_TITLE = 80
        private const val MAX_SUMMARY = 200

        /** Strong biometrics or the screen lock. */
        internal const val AUTHENTICATORS = BIOMETRIC_STRONG or DEVICE_CREDENTIAL

        /** Android 11 is the first version where the library supports that combination, so it is the first where the check can run at all. */
        internal fun isSupported(sdk: Int): Boolean = sdk >= Build.VERSION_CODES.R
    }
}
