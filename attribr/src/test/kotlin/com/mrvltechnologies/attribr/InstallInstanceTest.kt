// SPDX-License-Identifier: MIT
//
// Sprint 13 — InstallInstance JVM unit tests, updated for the Play Referrer
// gap fix: storage moved from SharedPreferences (contaminated by Android
// Auto Backup restore) to DeviceStateStore (noBackupFilesDir-backed
// Properties file — pure java.io, so these tests run on plain JVM with a
// temp file, no Robolectric).
//
// Contract locked here:
//   • First access generates and persists a fresh UUID.
//   • Repeat access returns the same hash.
//   • A legacy prefs UUID migrates once (identity preserved) and the prefs
//     copy is removed so future backups no longer carry it.
//   • Corrupted store entry → regenerate.
//   • Hash is 64-char lowercase hex (SHA-256).
//
// Run with: ./gradlew :attribr:testDebugUnitTest

package com.mrvltechnologies.attribr

import android.content.SharedPreferences
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.io.File
import kotlin.text.Regex

class InstallInstanceTest {

    // ── In-memory SharedPreferences fake (for legacy-migration tests) ─────
    private class FakePrefs : SharedPreferences {
        val store = mutableMapOf<String, String?>()

        override fun getString(key: String, defValue: String?): String? = store[key] ?: defValue
        override fun contains(key: String): Boolean = store.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor(store)

        // Unused surface — throw for anything the SDK doesn't touch.
        override fun getAll(): MutableMap<String, *> = throw NotImplementedError()
        override fun getStringSet(k: String, d: MutableSet<String>?) = throw NotImplementedError()
        override fun getInt(k: String, d: Int) = throw NotImplementedError()
        override fun getLong(k: String, d: Long) = throw NotImplementedError()
        override fun getFloat(k: String, d: Float) = throw NotImplementedError()
        override fun getBoolean(k: String, d: Boolean) = throw NotImplementedError()
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    private class FakeEditor(private val store: MutableMap<String, String?>) : SharedPreferences.Editor {
        override fun putString(k: String, v: String?): SharedPreferences.Editor { store[k] = v; return this }
        override fun remove(k: String): SharedPreferences.Editor { store.remove(k); return this }
        override fun clear(): SharedPreferences.Editor { store.clear(); return this }
        override fun commit(): Boolean = true
        override fun apply() {}
        override fun putStringSet(k: String, v: MutableSet<String>?) = throw NotImplementedError()
        override fun putInt(k: String, v: Int) = throw NotImplementedError()
        override fun putLong(k: String, v: Long) = throw NotImplementedError()
        override fun putFloat(k: String, v: Float) = throw NotImplementedError()
        override fun putBoolean(k: String, v: Boolean) = throw NotImplementedError()
    }

    private fun freshStore(): DeviceStateStore =
        DeviceStateStore(File.createTempFile("attribr_test_state", ".properties").apply { deleteOnExit() })

    // ── Tests ────────────────────────────────────────────────────────────

    @Test fun firstAccess_generatesAndPersistsUuid() {
        val s = freshStore()
        val raw = InstallInstance.loadOrCreateRaw(s)
        assertTrue("UUID should validate", InstallInstance.isValidUuid(raw))
        assertEquals(raw, s.getString(InstallInstance.STORAGE_KEY))
    }

    @Test fun secondAccess_returnsSameValue() {
        val s = freshStore()
        val a = InstallInstance.hashHexWithStore(s)
        val b = InstallInstance.hashHexWithStore(s)
        assertEquals(a, b)
    }

    @Test fun removedKey_generatesFreshUuid() {
        val s = freshStore()
        val a = InstallInstance.hashHexWithStore(s)
        s.remove(InstallInstance.STORAGE_KEY)
        val b = InstallInstance.hashHexWithStore(s)
        assertNotEquals("removal must produce a fresh install-instance", a, b)
    }

    @Test fun legacyPrefsUuid_migratesOnceAndClearsPrefsCopy() {
        val s = freshStore()
        val prefs = FakePrefs()
        val legacyUuid = "6ba7b810-9dad-11d1-80b4-00c04fd430c8"
        prefs.store[InstallInstance.STORAGE_KEY] = legacyUuid

        val raw = InstallInstance.loadOrCreateRaw(s, prefs)

        // Identity preserved — an existing install keeps its instance id.
        assertEquals(legacyUuid, raw)
        assertEquals(legacyUuid, s.getString(InstallInstance.STORAGE_KEY))
        // The prefs copy is gone so Auto Backup can no longer carry it onto
        // a different (restored) install.
        assertNull(prefs.getString(InstallInstance.STORAGE_KEY, null))
    }

    @Test fun storeValue_winsOverLegacyPrefs() {
        val s = freshStore()
        val prefs = FakePrefs()
        prefs.store[InstallInstance.STORAGE_KEY] = "6ba7b810-9dad-11d1-80b4-00c04fd430c8"
        val fresh = InstallInstance.loadOrCreateRaw(s) // establish store value first, no prefs

        val raw = InstallInstance.loadOrCreateRaw(s, prefs)
        assertEquals("an established store value must never be replaced by a legacy prefs value", fresh, raw)
    }

    @Test fun hash_is_64_char_lowercase_hex() {
        val s = freshStore()
        val hash = InstallInstance.hashHexWithStore(s)
        assertEquals(64, hash.length)
        assertTrue("hash is lowercase hex", Regex("^[0-9a-f]{64}$").matches(hash))
    }

    @Test fun corruptedStore_regenerates() {
        val s = freshStore()
        s.putString(InstallInstance.STORAGE_KEY, "not-a-uuid")
        val raw = InstallInstance.loadOrCreateRaw(s)
        assertTrue(InstallInstance.isValidUuid(raw))
        assertNotEquals("not-a-uuid", raw)
    }

    @Test fun sha256_of_known_input_matches_vector() {
        // Sanity check the SHA-256 implementation against a known vector.
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            InstallInstance.sha256Hex("hello"),
        )
    }
}
