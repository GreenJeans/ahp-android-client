@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.ahpclient.security

import dev.ahpclient.state.ApprovalCheck
import dev.ahpclient.state.ApprovalGate
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAErrorAppCancel
import platform.LocalAuthentication.LAErrorSystemCancel
import platform.LocalAuthentication.LAErrorUserCancel
import platform.LocalAuthentication.LAErrorUserFallback
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthentication

/**
 * iOS `LocalAuthentication` (`LAContext`) implementation of [ApprovalGate] using Face ID / Touch ID with device passcode fallback
 * ([LAPolicyDeviceOwnerAuthentication]), matching Android's `BiometricApprovalGate` (`BIOMETRIC_STRONG or DEVICE_CREDENTIAL`).
 */
class IosApprovalGate : ApprovalGate {
    override suspend fun check(reason: String): ApprovalCheck = suspendCancellableCoroutine { continuation ->
        val context = LAContext()
        val canEvaluate = context.canEvaluatePolicy(LAPolicyDeviceOwnerAuthentication, error = null)
        if (!canEvaluate) {
            if (continuation.isActive) continuation.resume(ApprovalCheck.Unavailable)
            return@suspendCancellableCoroutine
        }
        val promptReason = reason.ifBlank { "Approve agent action" }
        context.evaluatePolicy(
            policy = LAPolicyDeviceOwnerAuthentication,
            localizedReason = promptReason,
        ) { success, error ->
            if (!continuation.isActive) return@evaluatePolicy
            if (success) {
                continuation.resume(ApprovalCheck.Approved)
            } else {
                val code = error?.code
                val check = if (
                    code == LAErrorUserCancel ||
                    code == LAErrorSystemCancel ||
                    code == LAErrorAppCancel ||
                    code == LAErrorUserFallback
                ) {
                    ApprovalCheck.Dismissed
                } else {
                    ApprovalCheck.Unavailable
                }
                continuation.resume(check)
            }
        }
        continuation.invokeOnCancellation {
            context.invalidate()
        }
    }
}
