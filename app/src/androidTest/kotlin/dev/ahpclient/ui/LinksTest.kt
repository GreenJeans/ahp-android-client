package dev.ahpclient.ui

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
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
}
