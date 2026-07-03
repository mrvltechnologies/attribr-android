// SPDX-License-Identifier: MIT
//
// InstallInstance — Sprint 13.
//
// Generates and persists a random, app-local UUID that Attribr uses to
// distinguish `returning_launch` from `reinstall_candidate` events.
//
// The RAW UUID never leaves the device. Only its SHA-256 hex digest is sent
// on the wire, in the `sdk_install_instance_id_hash` field. When the user
// uninstalls the app, Android wipes the app's private storage, so a
// reinstall produces a fresh UUID → the backend correctly classifies the
// next `trackLaunch()` as `reinstall_candidate` for that (app_id,
// device_hash) pair.
//
// This value is NOT:
//   • an advertising identifier (GAID / AAID)
//   • a platform-provided device identifier (Settings.Secure.ANDROID_ID)
//   • hardware fingerprint (serial, MAC, Build.FINGERPRINT)
//   • a user identifier
//
// It is a per-install-instance random value, useful only for Attribr's
// own new/returning/reinstall_candidate classification.

package com.mrvltechnologies.attribr

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest
import java.util.UUID

public object InstallInstance {

    /** SharedPreferences file name (must match Attribr.kt's `attribr` prefs). */
    internal const val PREFS_NAME: String = "attribr"

    /** Preference key for the raw UUID. */
    internal const val STORAGE_KEY: String = "attribr_install_instance_id"

    /**
     * Reset the stored UUID. Called from `Attribr.deleteAllData` so the
     * user's GDPR-style deletion request is honoured on-device too.
     */
    @JvmStatic
    public fun reset(context: Context) {
        resetWithPrefs(prefs(context))
    }

    /**
     * Return the SHA-256 hex digest of the app-local UUID, generating and
     * persisting the UUID on first call. Deterministic across launches
     * until the app is uninstalled or `reset()` is called.
     */
    @JvmStatic
    public fun hashHex(context: Context): String {
        return hashHexWithPrefs(prefs(context))
    }

    // ── Test-visible internals (SharedPreferences-scoped, no Context) ────

    internal fun hashHexWithPrefs(p: SharedPreferences): String =
        sha256Hex(loadOrCreateRaw(p))

    internal fun resetWithPrefs(p: SharedPreferences) {
        p.edit().remove(STORAGE_KEY).apply()
    }

    internal fun loadOrCreateRaw(p: SharedPreferences): String {
        val existing = p.getString(STORAGE_KEY, null)
        if (existing != null && isValidUuid(existing)) {
            return existing
        }
        val fresh = UUID.randomUUID().toString()
        p.edit().putString(STORAGE_KEY, fresh).apply()
        return fresh
    }

    /** Guard against a corrupted prefs entry. */
    internal fun isValidUuid(s: String): Boolean {
        return try {
            UUID.fromString(s).toString() == s
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    internal fun sha256Hex(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    private fun prefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
