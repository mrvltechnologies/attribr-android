// SPDX-License-Identifier: MIT
//
// Auto Backup fix — DeviceIdentifier JVM unit tests.
//
// The per-device UUID moved from SharedPreferences("attribr") (restored by
// Android Auto Backup onto NEW devices, so a restored phone inherited the
// old phone's device_hash) to DeviceStateStore (noBackupFilesDir). Contract
// locked here, mirroring InstallInstance's migration semantics:
//   • First access generates and persists a fresh UUID in the store.
//   • Hash is stable across instances over the same store file.
//   • A legacy prefs UUID migrates once (hash identity preserved) and the
//     prefs copy is removed so future backups no longer carry it.
//   • An established store value wins over any legacy prefs value.
//   • Corrupted store entry → regenerate.
//   • Hash is 64-char lowercase hex (SHA-256).
//
// Run with: ./gradlew :attribr:testDebugUnitTest

package com.mrvltechnologies.attribr

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.io.File

class DeviceIdentifierTest {

    private fun freshStore(): DeviceStateStore =
        DeviceStateStore(tempStateFile("attribr_di_test"))

    @Test fun firstAccess_generatesAndPersistsUuidInStore() {
        val store = freshStore()
        val di = DeviceIdentifier(store, null)
        di.getHash()
        val raw = store.getString(DeviceIdentifier.KEY_DEVICE_UUID)
        assertTrue("store must hold a valid UUID", raw != null && InstallInstance.isValidUuid(raw))
    }

    @Test fun hash_stableAcrossInstances_overSameStoreFile() {
        val file = tempStateFile("attribr_di_stable")
        val a = DeviceIdentifier(DeviceStateStore(file), null).getHash()
        val b = DeviceIdentifier(DeviceStateStore(file), null).getHash()
        assertEquals(a, b)
    }

    @Test fun hash_is_64_char_lowercase_hex() {
        val hash = DeviceIdentifier(freshStore(), null).getHash()
        assertEquals(64, hash.length)
        assertTrue("hash is lowercase hex", Regex("^[0-9a-f]{64}$").matches(hash))
    }

    @Test fun legacyPrefsUuid_migratesOnce_preservingHash_andClearsPrefsCopy() {
        val store = freshStore()
        val prefs = FakeSharedPrefs()
        val legacyUuid = "6ba7b810-9dad-11d1-80b4-00c04fd430c8"
        prefs.store[DeviceIdentifier.KEY_DEVICE_UUID] = legacyUuid

        val hash = DeviceIdentifier(store, prefs).getHash()

        // Identity preserved — an existing install keeps its device_hash.
        assertEquals(InstallInstance.sha256Hex(legacyUuid), hash)
        assertEquals(legacyUuid, store.getString(DeviceIdentifier.KEY_DEVICE_UUID))
        // The prefs copy is gone so Auto Backup can no longer carry it onto
        // a different (restored) device.
        assertNull(prefs.getString(DeviceIdentifier.KEY_DEVICE_UUID, null))
    }

    @Test fun storeValue_winsOverLegacyPrefs() {
        val store = freshStore()
        // Establish a store-native identity first (no prefs in sight).
        val established = DeviceIdentifier(store, null).getHash()

        // A (restored) legacy prefs value must never replace it.
        val prefs = FakeSharedPrefs()
        prefs.store[DeviceIdentifier.KEY_DEVICE_UUID] = "6ba7b810-9dad-11d1-80b4-00c04fd430c8"
        val hash = DeviceIdentifier(store, prefs).getHash()

        assertEquals("an established store value must never be replaced by a legacy prefs value", established, hash)
    }

    @Test fun corruptedStoreValue_regenerates() {
        val store = freshStore()
        store.putString(DeviceIdentifier.KEY_DEVICE_UUID, "not-a-uuid")
        DeviceIdentifier(store, null).getHash()
        val raw = store.getString(DeviceIdentifier.KEY_DEVICE_UUID)
        assertTrue(raw != null && InstallInstance.isValidUuid(raw))
        assertNotEquals("not-a-uuid", raw)
    }

    @Test fun invalidLegacyPrefsValue_isIgnored_freshUuidGenerated() {
        val store = freshStore()
        val prefs = FakeSharedPrefs()
        prefs.store[DeviceIdentifier.KEY_DEVICE_UUID] = "garbage"
        val hash = DeviceIdentifier(store, prefs).getHash()
        assertNotEquals(InstallInstance.sha256Hex("garbage"), hash)
        val raw = store.getString(DeviceIdentifier.KEY_DEVICE_UUID)
        assertTrue(raw != null && InstallInstance.isValidUuid(raw))
    }
}
