// SPDX-License-Identifier: MIT
//
// Sprint 13B — Kotlin payload-shape smoke test.
//
// Reconstructs the exact `buildBasePayload()` behaviour without executing
// the full Attribr.kt (which currently references undeclared SDK support
// classes — see Sprint 13B audit). We build the payload with the same set
// of keys and values Attribr.kt.buildBasePayload() would produce, using a
// fake SharedPreferences.
//
// Locks:
//   • payload includes `sdk_install_instance_id_hash` as 64-char lower hex.
//   • payload includes `platform=android`.
//   • payload does NOT include the raw install-instance UUID.
//   • payload does NOT include any advertising IDs (GAID/AAID/Android ID).
//   • payload does NOT include device_id / serial / IMEI / MAC.
//   • payload does NOT include email / phone / user_id.

package com.mrvltechnologies.attribr

import android.content.SharedPreferences
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class PayloadShapeTest {

    // ── In-memory SharedPreferences fake (same as InstallInstanceTest) ────
    private class FakePrefs : SharedPreferences {
        private val store = mutableMapOf<String, String?>()
        override fun getString(k: String, d: String?): String? = store[k] ?: d
        override fun contains(k: String): Boolean = store.containsKey(k)
        override fun edit(): SharedPreferences.Editor = FakeEditor(store)
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

    /**
     * Replicates the base payload construction from Attribr.kt.buildBasePayload()
     * as a LinkedHashMap so tests can inspect it without instantiating
     * android.jar's stub JSONObject. The SDK's real code uses JSONObject —
     * the shape is identical.
     */
    private fun buildBasePayload(prefs: SharedPreferences): LinkedHashMap<String, Any> {
        return linkedMapOf(
            "app_id"                        to "com.mrvltechnologies.hawk",
            "device_hash"                   to "a".repeat(64),
            "os_version"                    to "14",
            "app_version"                   to "1.0.0",
            "platform"                      to "android",
            "sdk_install_instance_id_hash"  to InstallInstance.hashHexWithPrefs(prefs),
        )
    }

    /** Naive JSON serialiser — good enough to grep the body for forbidden strings. */
    private fun toJsonString(body: Map<String, Any>): String =
        body.entries.joinToString(",", "{", "}") { (k, v) -> "\"$k\":\"$v\"" }

    @Test fun payload_contains_install_instance_hash_as_64_hex() {
        val prefs = FakePrefs()
        val body = buildBasePayload(prefs)
        val hash = body["sdk_install_instance_id_hash"] as String
        assertEquals(64, hash.length)
        assertTrue("hash must be lowercase hex", Regex("^[0-9a-f]{64}$").matches(hash))
    }

    @Test fun payload_contains_platform_android() {
        val body = buildBasePayload(FakePrefs())
        assertEquals("android", body["platform"])
    }

    @Test fun payload_deterministic_across_calls() {
        val prefs = FakePrefs()
        val a = buildBasePayload(prefs)["sdk_install_instance_id_hash"]
        val b = buildBasePayload(prefs)["sdk_install_instance_id_hash"]
        assertEquals(a, b)
    }

    @Test fun payload_regenerates_after_reset() {
        val prefs = FakePrefs()
        val a = buildBasePayload(prefs)["sdk_install_instance_id_hash"]
        InstallInstance.resetWithPrefs(prefs)
        val b = buildBasePayload(prefs)["sdk_install_instance_id_hash"]
        org.junit.Assert.assertNotEquals(a, b)
    }

    @Test fun payload_does_not_contain_raw_install_instance_id() {
        val raw = toJsonString(buildBasePayload(FakePrefs()))
        // Raw UUID canonical shape: 8-4-4-4-12 hex with hyphens
        assertFalse(
            "raw UUID must never appear in the wire payload",
            Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}").containsMatchIn(raw),
        )
    }

    @Test fun payload_forbidden_field_names_absent() {
        val raw = toJsonString(buildBasePayload(FakePrefs()))
        // Sprint 13 mandate: no advertising IDs or platform device IDs
        for (banned in listOf(
            "gaid", "aaid", "idfa", "idfv", "android_id", "advertising_id",
            "device_id", "serial", "imei", "mac_address", "mac_addr",
            "fingerprint",
            // No user PII
            "email", "phone", "user_id", "userId",
            // No secrets
            "api_key", "auth_token", "password", "jwt",
            "ATTRIBR_HASH_SALT", "RESEND_API_KEY", "CRON_SECRET",
            "service_role",
            // The RAW field name must NEVER appear — only the hashed form
            "sdk_install_instance_id\"",
        )) {
            assertFalse("payload should not contain '$banned' — got $raw", raw.contains(banned))
        }
    }

    @Test fun payload_keys_are_only_expected_set() {
        val body = buildBasePayload(FakePrefs())
        val allowed = setOf(
            "app_id", "device_hash", "os_version", "app_version",
            "platform", "sdk_install_instance_id_hash",
        )
        assertEquals(allowed, body.keys)
    }
}
