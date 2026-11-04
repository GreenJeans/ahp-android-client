package dev.ahpclient.data

import dev.ahpclient.state.WrapView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The wrap choice per kind of view (U6): what each starts as, and that a choice outlives the app being closed. */
class UiSettingsLineWrapTest {
    /** Stands in for the device's preferences: a new [UiSettings] over the same store is the app opened again. */
    private class MemoryStore : KeyValueStore {
        val values = mutableMapOf<String, Any>()
        override fun getFloat(key: String, defaultValue: Float) = values[key] as? Float ?: defaultValue
        override fun putFloat(key: String, value: Float) { values[key] = value }
        override fun getString(key: String, defaultValue: String?) = values[key] as? String ?: defaultValue
        override fun putString(key: String, value: String) { values[key] = value }
        override fun getBoolean(key: String, defaultValue: Boolean) = values[key] as? Boolean ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { values[key] = value }
        override fun contains(key: String) = key in values
        override fun remove(key: String) { values.remove(key) }
    }

    @Test
    fun `code, tool output and files keep lines whole at first, the terminal and diffs wrap`() {
        val wrap = UiSettings(MemoryStore()).lineWrap.value

        assertEquals(WrapView.entries.toSet(), wrap.keys)
        assertFalse(wrap.getValue(WrapView.CODE))
        assertFalse(wrap.getValue(WrapView.TOOL_OUTPUT))
        assertFalse(wrap.getValue(WrapView.FILE))
        assertTrue(wrap.getValue(WrapView.TERMINAL))
        assertTrue(wrap.getValue(WrapView.DIFF))
    }

    @Test
    fun `a choice changes only its own kind of view and is there when the app is opened again`() {
        val store = MemoryStore()
        val first = UiSettings(store)

        first.setLineWrap(WrapView.CODE, true)
        first.setLineWrap(WrapView.TERMINAL, false)

        assertTrue(first.lineWrap.value.getValue(WrapView.CODE))
        assertFalse(first.lineWrap.value.getValue(WrapView.TERMINAL))
        assertFalse(first.lineWrap.value.getValue(WrapView.TOOL_OUTPUT))
        assertTrue(first.lineWrap.value.getValue(WrapView.DIFF))

        val again = UiSettings(store).lineWrap.value
        assertTrue(again.getValue(WrapView.CODE))
        assertFalse(again.getValue(WrapView.TERMINAL))
        assertFalse(again.getValue(WrapView.TOOL_OUTPUT))
        assertFalse(again.getValue(WrapView.FILE))
        assertTrue(again.getValue(WrapView.DIFF))
    }

    @Test
    fun `turning a choice back restores what was there, and it is saved under a name of its own`() {
        val store = MemoryStore()
        val settings = UiSettings(store)

        settings.setLineWrap(WrapView.DIFF, false)
        settings.setLineWrap(WrapView.DIFF, true)

        assertTrue(UiSettings(store).lineWrap.value.getValue(WrapView.DIFF))
        // The saved names are fixed, so a later rename of the kinds cannot lose a choice already made.
        assertEquals(setOf("wrap_diff"), store.values.keys)
    }
}
