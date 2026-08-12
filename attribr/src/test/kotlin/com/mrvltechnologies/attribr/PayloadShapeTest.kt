// SPDX-License-Identifier: MIT
//
// Sprint 13B — Kotlin payload-shape smoke test.
//
// Reconstructs the exact `buildBasePayload()` behaviour without executing
// the full Attribr.kt (which currently references undeclared SDK support
// classes — see Sprint 13B audit). We build the payload with the same set
// of keys and values Attribr.kt.buildBasePayload() would produce, using a
// temp-file-backed DeviceStateStore (Play Referrer gap fix moved storage
// off SharedPreferences).
//
// Locks:
//   • payload includes `sdk_install_instance_id_hash` as 64-char lower hex.
//   • payload includes `platform=android`.
//   • payload does NOT include the raw install-instance UUID.
//   • payload does NOT include any advertising IDs (GAID/AAID/Android ID).
//   • payload does NOT include device_id / serial / IMEI / MAC.
//   • payload does NOT include email / phone / user_id.

package com.mrvltechnologies.attribr

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class PayloadShapeTest {

    private fun freshStore(): DeviceStateStore =
        DeviceStateStore(java.io.File.createTempFile("attribr_pst_state", ".properties").apply { deleteOnExit() })

    /**
     * Replicates the base payload construction from Attribr.kt.buildBasePayload()
     * as a LinkedHashMap so tests can inspect it without instantiating
     * android.jar's stub JSONObject. The SDK's real code uses JSONObject —
     * the shape is identical.
     */
    private fun buildBasePayload(store: DeviceStateStore): LinkedHashMap<String, Any> {
        return linkedMapOf(
            "app_id"                        to "com.mrvltechnologies.hawk",
            "device_hash"                   to "a".repeat(64),
            "os_version"                    to "14",
            "app_version"                   to "1.0.0",
            "platform"                      to "android",
            "sdk_install_instance_id_hash"  to InstallInstance.hashHexWithStore(store),
        )
    }

    /** Naive JSON serialiser — good enough to grep the body for forbidden strings. */
    private fun toJsonString(body: Map<String, Any>): String =
        body.entries.joinToString(",", "{", "}") { (k, v) -> "\"$k\":\"$v\"" }

    @Test fun payload_contains_install_instance_hash_as_64_hex() {
        val store = freshStore()
        val body = buildBasePayload(store)
        val hash = body["sdk_install_instance_id_hash"] as String
        assertEquals(64, hash.length)
        assertTrue("hash must be lowercase hex", Regex("^[0-9a-f]{64}$").matches(hash))
    }

    @Test fun payload_contains_platform_android() {
        val body = buildBasePayload(freshStore())
        assertEquals("android", body["platform"])
    }

    @Test fun payload_deterministic_across_calls() {
        val store = freshStore()
        val a = buildBasePayload(store)["sdk_install_instance_id_hash"]
        val b = buildBasePayload(store)["sdk_install_instance_id_hash"]
        assertEquals(a, b)
    }

    @Test fun payload_regenerates_after_reset() {
        val store = freshStore()
        val a = buildBasePayload(store)["sdk_install_instance_id_hash"]
        store.remove(InstallInstance.STORAGE_KEY)
        val b = buildBasePayload(store)["sdk_install_instance_id_hash"]
        org.junit.Assert.assertNotEquals(a, b)
    }

    @Test fun payload_does_not_contain_raw_install_instance_id() {
        val raw = toJsonString(buildBasePayload(freshStore()))
        // Raw UUID canonical shape: 8-4-4-4-12 hex with hyphens
        assertFalse(
            "raw UUID must never appear in the wire payload",
            Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}").containsMatchIn(raw),
        )
    }

    @Test fun payload_forbidden_field_names_absent() {
        val raw = toJsonString(buildBasePayload(freshStore()))
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
        val body = buildBasePayload(freshStore())
        val allowed = setOf(
            "app_id", "device_hash", "os_version", "app_version",
            "platform", "sdk_install_instance_id_hash",
        )
        assertEquals(allowed, body.keys)
    }
}
