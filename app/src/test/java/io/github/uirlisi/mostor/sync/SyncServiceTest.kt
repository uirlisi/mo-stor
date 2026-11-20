package io.github.uirlisi.mostor.sync

import org.junit.Assert.assertNotEquals
import org.junit.Test

/** The tests run on the dev build: its Bluetooth service must never be prod's. */
class SyncServiceTest {
    @Test
    fun `the dev build has its own sync service, not the family's`() {
        assertNotEquals("12ca01f1-1d10-494e-8e7f-2b2e6240d3ff", SyncService.ID.toString())
        assertNotEquals("12ca01f1-1d10-494e-8e7f-2b2e6240d3ff", BleTransport.SERVICE.toString())
    }
}
