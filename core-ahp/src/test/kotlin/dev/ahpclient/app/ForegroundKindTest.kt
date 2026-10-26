package dev.ahpclient.app

import kotlin.test.Test
import kotlin.test.assertEquals

class ForegroundKindTest {
    @Test
    fun `before Android 10 the service has no type`() {
        assertEquals(ForegroundKind.NONE, ForegroundKind.forSdk(25))
        assertEquals(ForegroundKind.NONE, ForegroundKind.forSdk(26))
        assertEquals(ForegroundKind.NONE, ForegroundKind.forSdk(28))
    }

    @Test
    fun `Android 10 to 13 keep data sync, which has no time limit there`() {
        assertEquals(ForegroundKind.DATA_SYNC, ForegroundKind.forSdk(29))
        assertEquals(ForegroundKind.DATA_SYNC, ForegroundKind.forSdk(33))
    }

    @Test
    fun `Android 14 and newer run as special use to avoid the six hour data-sync cut`() {
        assertEquals(ForegroundKind.SPECIAL_USE, ForegroundKind.forSdk(34))
        assertEquals(ForegroundKind.SPECIAL_USE, ForegroundKind.forSdk(35))
    }
}
