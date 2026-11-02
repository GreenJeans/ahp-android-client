@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.ahpclient.security

import dev.ahpclient.state.ApprovalGate
import dev.ahpclient.state.ApprovalRequest
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthentication

/**
 * iOS `LocalAuthentication` (`LAContext`) implementation of [ApprovalGate] using Face ID / Touch ID with device passcode fallback
 * ([LAPolicyDeviceOwnerAuthentication]), matching Android's `BiometricApprovalGate` (`BIOMETRIC_STRONG or DEVICE_CREDENTIAL`).
 */
class IosApprovalGate : ApprovalGate {
    override suspend fun confirm(request: ApprovalRequest): Boolean = suspendCancellableCoroutine { continuation ->
        val context = LAContext()
        val canEvaluate = context.canEvaluatePolicy(LAPolicyDeviceOwnerAuthentication, error = null)
        if (!canEvaluate) {
            if (continuation.isActive) continuation.resume(false)
            return@suspendCancellableCoroutine
        }
        val promptReason = listOfNotNull(
            request.title.ifBlank { null },
            request.toolName.ifBlank { null },
            request.description,
        ).joinToString(" — ").ifBlank { "Approve agent action" }
        context.evaluatePolicy(
            policy = LAPolicyDeviceOwnerAuthentication,
            localizedReason = promptReason,
        ) { success, _ ->
            if (continuation.isActive) {
                continuation.resume(success)
            }
        }
        continuation.invokeOnCancellation {
            context.invalidate()
        }
    }
}
