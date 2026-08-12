// SPDX-License-Identifier: MIT
//
// Play Referrer gap fix — DeviceStateStore JVM unit tests.
//
// Pure java.io Properties-file store, so these run on plain JVM with temp
// files — no Robolectric. Contract locked here:
//   • String and boolean round-trips persist across store instances
//     (simulating separate app launches reading the same file).
//   • remove() and clear() behave as expected.
//   • A missing or unreadable file is treated as empty, never a crash.

package com.mrvltechnologies.attribr

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.io.File

class DeviceStateStoreTest {

    private fun tempFile(): File =
        File.createTempFile("attribr_dss_test", ".properties").apply { deleteOnExit() }

    @Test fun stringRoundTrip_persistsAcrossInstances() {
        val file = tempFile()
        DeviceStateStore(file).putString("k", "v1")
        // A brand-new instance over the same file — simulates a fresh launch.
        assertEquals("v1", DeviceStateStore(file).getString("k"))
    }

    @Test fun booleanRoundTrip() {
        val file = tempFile()
        val store = DeviceStateStore(file)
        assertFalse("unset key reads false", store.getBoolean("flag"))
        store.putBoolean("flag", true)
        assertTrue(store.getBoolean("flag"))
        assertTrue("persists across instances", DeviceStateStore(file).getBoolean("flag"))
    }

    @Test fun remove_deletesOnlyThatKey() {
        val file = tempFile()
        val store = DeviceStateStore(file)
        store.putString("keep", "yes")
        store.putString("drop", "no")
        store.remove("drop")
        assertNull(store.getString("drop"))
        assertEquals("yes", store.getString("keep"))
    }

    @Test fun clear_wipesEverything() {
        val file = tempFile()
        val store = DeviceStateStore(file)
        store.putString("a", "1")
        store.putBoolean("b", true)
        store.clear()
        assertNull(store.getString("a"))
        assertFalse(store.getBoolean("b"))
    }

    @Test fun missingFile_readsAsEmpty() {
        val file = tempFile()
        file.delete()
        val store = DeviceStateStore(file)
        assertNull(store.getString("anything"))
        assertFalse(store.getBoolean("anything"))
    }

    @Test fun corruptFileContent_doesNotCrash() {
        val file = tempFile()
        // Properties.load is extremely permissive, but lock in "never throws"
        // for arbitrary bytes anyway.
        file.writeBytes(byteArrayOf(0x00, 0x01, 0x7F, 0x00))
        val store = DeviceStateStore(file)
        store.putString("k", "v") // must also be writable afterwards
        assertEquals("v", DeviceStateStore(file).getString("k"))
    }
}
