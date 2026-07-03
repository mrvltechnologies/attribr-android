// SPDX-License-Identifier: MIT
//
// Sprint 13 — InstallInstance JVM unit tests.
//
// Uses a hand-rolled in-memory `SharedPreferences` fake so this test can
// run under a plain JUnit target — no Robolectric, no Android SDK.
//
// Contract locked here:
//   • First access generates and persists a fresh UUID.
//   • Repeat access returns the same hash.
//   • reset() generates a fresh UUID next time.
//   • Corrupted prefs → regenerate.
//   • Hash is 64-char lowercase hex (SHA-256).
//
// Compiling this file DOES require the Android SDK on the classpath (for the
// `SharedPreferences` interface). Run with:
//   ./gradlew :attribr:testDebugUnitTest
// (or ./gradlew :attribr:test if the module has a plain JVM test task).

package com.mrvltechnologies.attribr

import android.content.SharedPreferences
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import kotlin.text.Regex

class InstallInstanceTest {

    // ── In-memory SharedPreferences fake ──────────────────────────────────
    private class FakePrefs : SharedPreferences {
        private val store = mutableMapOf<String, String?>()

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

    private fun freshPrefs(): SharedPreferences = FakePrefs()

    // ── Tests ────────────────────────────────────────────────────────────

    @Test fun firstAccess_generatesAndPersistsUuid() {
        val p = freshPrefs()
        val raw = InstallInstance.loadOrCreateRaw(p)
        assertTrue("UUID should validate", InstallInstance.isValidUuid(raw))
        assertEquals(raw, p.getString(InstallInstance.STORAGE_KEY, null))
    }

    @Test fun secondAccess_returnsSameValue() {
        val p = freshPrefs()
        val a = InstallInstance.hashHexWithPrefs(p)
        val b = InstallInstance.hashHexWithPrefs(p)
        assertEquals(a, b)
    }

    @Test fun reset_generatesFreshUuid() {
        val p = freshPrefs()
        val a = InstallInstance.hashHexWithPrefs(p)
        InstallInstance.resetWithPrefs(p)
        val b = InstallInstance.hashHexWithPrefs(p)
        assertNotEquals("reset() must produce a fresh install-instance", a, b)
    }

    @Test fun hash_is_64_char_lowercase_hex() {
        val p = freshPrefs()
        val hash = InstallInstance.hashHexWithPrefs(p)
        assertEquals(64, hash.length)
        assertTrue("hash is lowercase hex", Regex("^[0-9a-f]{64}$").matches(hash))
    }

    @Test fun corruptedPrefs_regenerate() {
        val p = freshPrefs()
        p.edit().putString(InstallInstance.STORAGE_KEY, "not-a-uuid").apply()
        val raw = InstallInstance.loadOrCreateRaw(p)
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
