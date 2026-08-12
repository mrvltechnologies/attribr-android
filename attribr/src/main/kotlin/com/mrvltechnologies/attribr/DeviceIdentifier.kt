package com.mrvltechnologies.attribr

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest
import java.util.UUID

/**
 * Produces a stable, privacy-preserving device hash for backend correlation.
 *
 * ## Privacy design (Sprint 13C — non-negotiable)
 * This implementation intentionally does **NOT** use any of:
 *  - Google Advertising ID (GAID / AAID)
 *  - `Settings.Secure.ANDROID_ID`
 *  - Device serial (`Build.SERIAL` / `Build.getSerial()`)
 *  - IMEI / MEID / MSISDN
 *  - MAC address (Wi-Fi or Bluetooth)
 *  - Hardware fingerprint (`Build.FINGERPRINT`, model, manufacturer combos)
 *
 * Instead, a per-install UUID is generated on first launch, persisted in
 * [DeviceStateStore] (`noBackupFilesDir`), and returned as SHA-256 hex on
 * every call.
 *
 * ## Storage (Auto Backup fix)
 * The UUID used to live in `SharedPreferences("attribr")`, which Android
 * Auto Backup restores onto fresh installs and NEW DEVICES — a restored
 * phone silently inherited the old phone's `device_hash`, corrupting
 * per-device attribution. The UUID now lives in `noBackupFilesDir` via
 * [DeviceStateStore], which the OS never backs up. An existing prefs-stored
 * UUID is migrated on first access — once — so current installs keep their
 * identity, and the prefs copy is removed so future backups no longer
 * carry it. (Same pattern as [InstallInstance].)
 *
 * ## Reinstall semantics
 * `noBackupFilesDir` is wiped on uninstall, so the hash is stable within
 * an install lifetime and rotates across reinstalls — mirroring the iOS
 * SDK's IDFV-based hash which rotates when the last app from the vendor is
 * uninstalled. Sprint 12's `reinstall_candidate` classification on the
 * backend depends on this rotation behaviour.
 */
internal class DeviceIdentifier internal constructor(
    private val store: DeviceStateStore,
    private val legacyPrefs: SharedPreferences?,
) {

    constructor(context: Context) : this(
        DeviceStateStore(context),
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
    )

    /** Returns the 64-character lowercase SHA-256 hex of the persisted UUID. */
    fun getHash(): String = sha256Hex(loadOrCreateRaw())

    // ── Test-visible internals ────────────────────────────────────────────

    internal fun loadOrCreateRaw(): String {
        // Store value wins — once established, a legacy prefs value (which
        // may be a restored copy from a different device) is never trusted.
        val existing = store.getString(KEY_DEVICE_UUID)
        if (existing != null && InstallInstance.isValidUuid(existing)) {
            return existing
        }

        // One-time migration: a pre-existing prefs UUID keeps its identity so
        // current installs don't all rotate device_hash the day this SDK
        // update ships. The prefs copy is then removed so future Auto
        // Backups no longer carry it onto other installs/devices.
        val prefs = legacyPrefs
        val legacy = prefs?.getString(KEY_DEVICE_UUID, null)
        if (prefs != null && legacy != null && InstallInstance.isValidUuid(legacy)) {
            store.putString(KEY_DEVICE_UUID, legacy)
            prefs.edit().remove(KEY_DEVICE_UUID).apply()
            return legacy
        }

        val fresh = UUID.randomUUID().toString()
        store.putString(KEY_DEVICE_UUID, fresh)
        return fresh
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    internal companion object {
        /** SharedPreferences file name (legacy location, must match Attribr.kt's `attribr` prefs). */
        const val PREFS_NAME = "attribr"

        /** Storage key — same key in prefs (legacy) and DeviceStateStore. */
        const val KEY_DEVICE_UUID = "attribr_device_uuid"

        private val HEX = "0123456789abcdef".toCharArray()
    }
}
