package dev.ahpclient.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ahpclient.ui.theme.AhpTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The switch that lets the app act for the user: off by default, on only after a dialog that says what it allows. */
@RunWith(AndroidJUnit4::class)
class InteractionToggleTest {
    @get:Rule
    val rule = createComposeRule()

    private val changes = mutableListOf<Boolean>()

    private fun show(initial: Boolean) {
        var allowed by mutableStateOf(initial)
        rule.setContent {
            AhpTheme { InteractionToggle(allowed = allowed, onChange = { changes += it; allowed = it }) }
        }
    }

    @Test
    fun itIsOffAndSaysTheAppOnlyWatches() {
        show(initial = false)

        rule.onNodeWithTag("interaction_toggle").assertIsOff()
        rule.onNodeWithTag("interaction_hint").assertTextContains("only watches", substring = true)
        rule.onNodeWithTag("interaction_confirm").assertDoesNotExist()
    }

    @Test
    fun turningItOnAsksFirstAndChangesNothingUntilConfirmed() {
        show(initial = false)

        rule.onNodeWithTag("interaction_toggle").performClick()

        rule.onNodeWithTag("interaction_dialog_text").assertTextContains("fingerprint, face or screen lock", substring = true)
        rule.onNodeWithTag("interaction_dialog_text").assertTextContains("Signing out turns it off", substring = true)
        assertEquals(emptyList<Boolean>(), changes)
        rule.onNodeWithTag("interaction_toggle").assertIsOff()
    }

    @Test
    fun cancellingTheDialogLeavesItOff() {
        show(initial = false)

        rule.onNodeWithTag("interaction_toggle").performClick()
        rule.onNodeWithTag("interaction_cancel").performClick()

        assertEquals(emptyList<Boolean>(), changes)
        rule.onNodeWithTag("interaction_toggle").assertIsOff()
    }

    @Test
    fun confirmingTurnsItOnAndTheHintSaysWhatThatMeans() {
        show(initial = false)

        rule.onNodeWithTag("interaction_toggle").performClick()
        rule.onNodeWithTag("interaction_confirm").assertIsDisplayed().performClick()

        assertEquals(listOf(true), changes)
        rule.onNodeWithTag("interaction_toggle").assertIsOn()
        rule.onNodeWithTag("interaction_hint").assertTextContains("approve tool calls", substring = true)
    }

    @Test
    fun turningItOffNeedsNoQuestion() {
        show(initial = true)

        rule.onNodeWithTag("interaction_toggle").assertIsOn().performClick()

        assertEquals(listOf(false), changes)
        rule.onNodeWithTag("interaction_toggle").assertIsOff()
        rule.onNodeWithTag("interaction_confirm").assertDoesNotExist()
    }
}
