package dev.ahpclient.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ahpclient.state.FinishedWatch
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The saved choice of sessions to be alerted about, against a real DataStore file. */
@RunWith(AndroidJUnit4::class)
class PrefsFinishedWatchInstrumentedTest {
    private val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "finished-watch-test.preferences_pb")
    private val job = Job()

    private fun open() = PrefsFinishedWatch(PrefsLastHostStore.dataStoreFor(file, CoroutineScope(job + Dispatchers.IO)))

    @Before
    fun clean() {
        file.delete()
    }

    @After
    fun close() = runBlocking {
        job.cancelAndJoin()
        file.delete()
        Unit
    }

    @Test
    fun nothingIsChosenUntilTheUserChooses() = runBlocking {
        assertEquals(FinishedWatch(), open().watch.first())
    }

    @Test
    fun allCanBeTurnedOnAndOff() = runBlocking {
        val prefs = open()

        prefs.setAll(true)
        assertEquals(FinishedWatch(all = true), prefs.watch.first())
        prefs.setAll(false)
        assertEquals(FinishedWatch(), prefs.watch.first())
    }

    @Test
    fun sessionsAreAddedAndRemovedOneByOneAndAddingTwiceKeepsOne() = runBlocking {
        val prefs = open()

        prefs.setSession("ahp-session:/a", true)
        prefs.setSession("ahp-session:/b", true)
        prefs.setSession("ahp-session:/a", true)
        assertEquals(setOf("ahp-session:/a", "ahp-session:/b"), prefs.watch.first().sessions)

        prefs.setSession("ahp-session:/a", false)
        assertEquals(setOf("ahp-session:/b"), prefs.watch.first().sessions)
        prefs.setSession("ahp-session:/never", false)
        assertEquals(setOf("ahp-session:/b"), prefs.watch.first().sessions)
    }

    @Test
    fun turningAllOnOrOffLeavesThePickedSessionsAlone() = runBlocking {
        val prefs = open()
        prefs.setSession("ahp-session:/a", true)

        prefs.setAll(true)
        prefs.setAll(false)

        assertEquals(FinishedWatch(all = false, sessions = setOf("ahp-session:/a")), prefs.watch.first())
    }

    @Test
    fun aBlankOrOverlongAddressIsNotKept() = runBlocking {
        val prefs = open()

        prefs.setSession(" ", true)
        prefs.setSession("", true)
        prefs.setSession("x".repeat(PrefsFinishedWatch.MAX_URI + 1), true)

        assertEquals(emptySet<String>(), prefs.watch.first().sessions)
    }

    @Test
    fun thereIsALimitToHowManyAreKeptButOneAlreadyKeptCanStillBeRemoved() = runBlocking {
        val prefs = open()
        repeat(PrefsFinishedWatch.MAX_SESSIONS + 5) { prefs.setSession("ahp-session:/$it", true) }

        assertEquals(PrefsFinishedWatch.MAX_SESSIONS, prefs.watch.first().sessions.size)
        prefs.setSession("ahp-session:/0", false)
        assertEquals(PrefsFinishedWatch.MAX_SESSIONS - 1, prefs.watch.first().sessions.size)
    }

    @Test
    fun theChoiceSurvivesReopeningTheFile() = runBlocking {
        val first = Job()
        val store = PrefsFinishedWatch(PrefsLastHostStore.dataStoreFor(file, CoroutineScope(first + Dispatchers.IO)))
        store.setAll(true)
        store.setSession("ahp-session:/a", true)
        first.cancelAndJoin()

        assertEquals(FinishedWatch(all = true, sessions = setOf("ahp-session:/a")), open().watch.first())
    }

    @Test
    fun aDamagedFileReadsAsNothingChosen() = runBlocking {
        file.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))

        assertEquals(FinishedWatch(), open().watch.first())
    }
}
