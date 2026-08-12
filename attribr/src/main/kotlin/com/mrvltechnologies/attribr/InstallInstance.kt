// SPDX-License-Identifier: MIT
//
// InstallInstance — Sprint 13, storage moved to DeviceStateStore in the
// Play Referrer gap fix.
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
// Storage note (the reason this moved off SharedPreferences): Android Auto
// Backup restores SharedPreferences onto fresh installs, so a restored
// device inherited the previous install's UUID and a real reinstall looked
// like a continuously-installed app. The UUID now lives in
// `noBackupFilesDir` (via DeviceStateStore), which the OS never backs up.
// An existing prefs-stored UUID is migrated on first access — once — so
// current installs keep their identity, and the prefs copy is removed so
// future backups no longer carry it.
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

    /** Storage key for the raw UUID — same key in prefs (legacy) and DeviceStateStore. */
    internal const val STORAGE_KEY: String = "attribr_install_instance_id"

    /**
     * Reset the stored UUID. Called from `Attribr.deleteAllData` so the
     * user's GDPR-style deletion request is honoured on-device too.
     */
    @JvmStatic
    public fun reset(context: Context) {
        DeviceStateStore(context).remove(STORAGE_KEY)
        // Also clear any legacy prefs copy so it can't be re-migrated.
        prefs(context).edit().remove(STORAGE_KEY).apply()
    }

    /**
     * Return the SHA-256 hex digest of the app-local UUID, generating and
     * persisting the UUID on first call. Deterministic across launches
     * until the app is uninstalled or `reset()` is called.
     */
    @JvmStatic
    public fun hashHex(context: Context): String {
        return hashHexWithStore(DeviceStateStore(context), prefs(context))
    }

    // ── Test-visible internals (store-scoped, no Context) ────────────────

    internal fun hashHexWithStore(store: DeviceStateStore, legacyPrefs: SharedPreferences? = null): String =
        sha256Hex(loadOrCreateRaw(store, legacyPrefs))

    internal fun loadOrCreateRaw(store: DeviceStateStore, legacyPrefs: SharedPreferences? = null): String {
        val existing = store.getString(STORAGE_KEY)
        if (existing != null && isValidUuid(existing)) {
            return existing
        }

        // One-time migration: a pre-existing prefs UUID keeps its identity so
        // current installs aren't all reclassified as reinstall_candidate the
        // day this SDK update ships. The prefs copy is then removed so future
        // Auto Backups no longer carry it onto other installs.
        val legacy = legacyPrefs?.getString(STORAGE_KEY, null)
        if (legacy != null && isValidUuid(legacy)) {
            store.putString(STORAGE_KEY, legacy)
            legacyPrefs.edit().remove(STORAGE_KEY).apply()
            return legacy
        }

        val fresh = UUID.randomUUID().toString()
        store.putString(STORAGE_KEY, fresh)
        return fresh
    }

    /** Guard against a corrupted stored entry. */
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
