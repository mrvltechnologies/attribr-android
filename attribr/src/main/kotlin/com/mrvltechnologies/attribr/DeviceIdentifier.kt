package com.mrvltechnologies.attribr

import android.content.Context
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
 * `SharedPreferences("attribr")`, and returned as SHA-256 hex on every call.
 *
 * ## Reinstall semantics
 * `SharedPreferences` is wiped on uninstall, so the hash is stable within
 * an install lifetime and rotates across reinstalls — mirroring the iOS
 * SDK's IDFV-based hash which rotates when the last app from the vendor is
 * uninstalled. Sprint 12's `reinstall_candidate` classification on the
 * backend depends on this rotation behaviour.
 */
internal class DeviceIdentifier(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("attribr", Context.MODE_PRIVATE)

    /** Returns the 64-character lowercase SHA-256 hex of the persisted UUID. */
    fun getHash(): String {
        val raw = prefs.getString(KEY_DEVICE_UUID, null) ?: run {
            val fresh = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_UUID, fresh).apply()
            fresh
        }
        return sha256Hex(raw)
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

    private companion object {
        const val KEY_DEVICE_UUID = "attribr_device_uuid"
        val HEX = "0123456789abcdef".toCharArray()
    }
}
