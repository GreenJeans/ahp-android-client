package dev.ahpclient.data

import android.content.Context
import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ahpclient.state.GoogleAiConfig
import dev.ahpclient.state.QuickReplyMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The display settings, against real preferences. */
@RunWith(AndroidJUnit4::class)
class UiSettingsInstrumentedTest {
    private val prefs = InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("ahp_ui_test", Context.MODE_PRIVATE)

    @Before
    fun clean() = prefs.edit(commit = true) { clear() }

    @After
    fun close() = prefs.edit(commit = true) { clear() }

    @Test
    fun theBrowserALinkOpenedInBeforeIsForgottenOnFirstLoadAndNothingElseIs() {
        prefs.edit(commit = true) {
            putString("browser", "org.mozilla.firefox")
            putString("theme", "DARK")
        }

        UiSettings(prefs)

        assertFalse(prefs.contains("browser"))
        assertEquals("DARK", prefs.getString("theme", null))
    }

    @Test
    fun quickReplyModeDefaultsToAutoAndPersistsChanges() {
        val first = UiSettings(prefs)
        assertEquals(QuickReplyMode.AUTO, first.quickReplyMode.value)

        first.setQuickReplyMode(QuickReplyMode.ON_DEMAND)
        assertEquals(QuickReplyMode.ON_DEMAND, first.quickReplyMode.value)

        val second = UiSettings(prefs)
        assertEquals(QuickReplyMode.ON_DEMAND, second.quickReplyMode.value)
    }

    @Test
    fun googleAiConfigPersistsModelSystemPromptAndKeyFlag() {
        val first = UiSettings(prefs)
        assertFalse(first.googleAiConfig.value.isActive)

        val updated = GoogleAiConfig(
            enabled = true,
            modelName = "gemini-2.5-pro",
            systemPrompt = "Custom prompt",
            hasApiKey = true,
        )
        first.setGoogleAiConfig(updated)
        assertEquals(updated, first.googleAiConfig.value)

        val second = UiSettings(prefs)
        assertEquals(updated, second.googleAiConfig.value)
    }
}
