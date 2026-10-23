package dev.ahpclient.security

import android.app.KeyguardManager
import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ahpclient.MainActivity
import dev.ahpclient.state.ApprovalRequest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The device check in front of tool approvals, on a real Android. The refusals that need nobody at the keyboard always
 * run. The two that need a person to type the PIN or press Back run only on a device that has a screen lock, and are
 * driven from outside (`adb shell input`) on an emulator; they are skipped everywhere else. Never run these on a phone
 * that holds a real sign-in: the runner script refuses physical devices.
 */
@RunWith(AndroidJUnit4::class)
class ApprovalGateInstrumentedTest {
    private val request = ApprovalRequest("Run in Terminal", "Run the unit tests")

    private fun deviceIsSecure(): Boolean =
        ApplicationProvider.getApplicationContext<Context>().getSystemService(KeyguardManager::class.java).isDeviceSecure

    private fun <T> withAttachedGate(block: (BiometricApprovalGate) -> T): T {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val gate = BiometricApprovalGate()
            scenario.onActivity { gate.attach(it) }
            return block(gate)
        }
    }

    private fun confirm(gate: BiometricApprovalGate): Boolean = runBlocking { withTimeout(45_000) { gate.confirm(request) } }

    @Test
    fun refusesWhenNoActivityIsAttached() {
        assertFalse(runBlocking { BiometricApprovalGate().confirm(request) })
    }

    @Test
    fun refusesOnceTheActivityIsDetached() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val gate = BiometricApprovalGate()
            scenario.onActivity {
                gate.attach(it)
                gate.detach(it)
            }
            assertFalse(runBlocking { gate.confirm(request) })
        }
    }

    @Test
    fun refusesWhenTheDeviceHasNoScreenLock() {
        assumeFalse("needs a device with no screen lock", deviceIsSecure())

        assertFalse(withAttachedGate(::confirm))
    }

    @Test
    fun approvesWhenTheScreenLockIsPassed() {
        assumeTrue("needs a screen lock and someone to enter it", deviceIsSecure())

        assertTrue(withAttachedGate(::confirm))
    }

    @Test
    fun refusesWhenThePromptIsCancelled() {
        assumeTrue("needs a screen lock and someone to press Back", deviceIsSecure())

        assertFalse(withAttachedGate(::confirm))
    }

    @Test
    fun theChoiceOfWhatMaySatisfyTheCheckIsPinnedPerAndroidVersion() {
        // The authenticator choice is the whole policy; pinned so a change to it is a visible decision.
        val old = BIOMETRIC_WEAK or DEVICE_CREDENTIAL
        val modern = BIOMETRIC_STRONG or DEVICE_CREDENTIAL
        assertTrue(BiometricApprovalGate.authenticatorsFor(26) == old)
        assertTrue(BiometricApprovalGate.authenticatorsFor(29) == old)
        assertTrue(BiometricApprovalGate.authenticatorsFor(30) == modern)
        assertTrue(BiometricApprovalGate.authenticatorsFor(36) == modern)
    }
}
