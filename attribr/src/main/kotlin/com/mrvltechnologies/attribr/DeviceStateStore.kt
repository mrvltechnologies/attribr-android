// SPDX-License-Identifier: MIT
//
// DeviceStateStore — Play Referrer gap fix.
//
// A tiny key-value store backed by a Properties file in
// `context.noBackupFilesDir`. Android's Auto Backup (allowBackup="true",
// the default) backs up and RESTORES SharedPreferences onto fresh installs
// and new devices — which silently contaminated two pieces of SDK state
// that must be strictly per-install-instance:
//
//   • `attribr_referrer_checked` — a restored "true" meant a genuinely new
//     install never queried the Play Install Referrer API at all, which is
//     the largest contributor to the ~90% missing-referrer rate observed
//     in production (25 of 31 Hawk Android installs never even attempted
//     a referrer fetch despite 2-16 recorded launches each).
//   • `attribr_install_instance_id` — a restored UUID makes a reinstall
//     look like a continuously-installed app, breaking the backend's
//     reinstall_candidate classification.
//
// `noBackupFilesDir` is excluded from Auto Backup by the OS itself, so no
// host-app manifest/backup-rules change is required — the fix ships
// entirely inside the SDK.
//
// Plain JVM implementation (java.io + java.util.Properties only) so it is
// unit-testable without Robolectric, matching the SDK's existing test
// conventions.

package com.mrvltechnologies.attribr

import android.content.Context
import java.io.File
import java.util.Properties

internal class DeviceStateStore(private val file: File) {

    constructor(context: Context) : this(
        File(context.applicationContext.noBackupFilesDir, FILE_NAME)
    )

    private val lock = Any()

    fun getString(key: String): String? = synchronized(lock) { load().getProperty(key) }

    fun getBoolean(key: String): Boolean = synchronized(lock) {
        load().getProperty(key) == "true"
    }

    fun putString(key: String, value: String) {
        synchronized(lock) {
            val props = load()
            props.setProperty(key, value)
            persist(props)
        }
    }

    fun putBoolean(key: String, value: Boolean) = putString(key, value.toString())

    fun remove(key: String) {
        synchronized(lock) {
            val props = load()
            props.remove(key)
            persist(props)
        }
    }

    /** Wipe all SDK device state — used by GDPR-style deleteAllData. */
    fun clear() {
        synchronized(lock) {
            runCatching { file.delete() }
        }
    }

    // ── Persistence ───────────────────────────────────────────────────────

    private fun load(): Properties {
        val props = Properties()
        if (file.exists()) {
            runCatching { file.inputStream().use { props.load(it) } }
        }
        return props
    }

    private fun persist(props: Properties) {
        runCatching {
            file.parentFile?.mkdirs()
            file.outputStream().use { props.store(it, null) }
        }
    }

    internal companion object {
        const val FILE_NAME = "attribr_device_state.properties"
    }
}
