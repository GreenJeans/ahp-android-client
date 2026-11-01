package dev.ahpclient.ui

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LinksTest {
    @Test
    fun shareHandsTheFullAddressToTheSystemShareSheetAsPlainText() {
        val url = "https://example.com/docs?page=1&q=a#top"

        val chooser = shareIntent(url)

        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        assertEquals(url, send.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun webAndDeviceFlowLinksAndSystemShareSheetLaunchWithoutQueriesDeclaration() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val newTaskContext = object : android.content.ContextWrapper(context) {
            override fun startActivity(intent: Intent?) {
                super.startActivity(intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }

            override fun startActivity(intent: Intent?, options: android.os.Bundle?) {
                super.startActivity(intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options)
            }
        }
        assertTrue("openInBrowser launches Custom Tab without <queries>", openInBrowser(newTaskContext, "https://github.com/login/device"))
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        Thread.sleep(300)

        assertTrue("openLink launches Custom Tab for https URL", openLink(newTaskContext, "https://example.com/docs"))
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        Thread.sleep(300)

        assertTrue("shareLink launches system ChooserActivity", shareLink(newTaskContext, "https://example.com/docs?page=1&q=a#top"))
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
    }
}
