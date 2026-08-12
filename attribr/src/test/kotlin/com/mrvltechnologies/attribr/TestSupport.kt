// SPDX-License-Identifier: MIT
//
// Shared plain-JVM test doubles (no Robolectric):
//   • FakeSharedPrefs — in-memory SharedPreferences, string surface only
//     (same pattern as InstallInstanceTest's private FakePrefs, shared here
//     for the DeviceIdentifier / EventQueue migration tests).
//   • RecordingLogger — AttribrLogger that never touches android.util.Log
//     (unmocked in plain JVM tests) and records error lines for assertions.

package com.mrvltechnologies.attribr

import android.content.SharedPreferences
import java.io.File

internal class FakeSharedPrefs : SharedPreferences {
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
}

/** Logger that swallows output (android.util.Log is unmocked on plain JVM) and records errors. */
internal class RecordingLogger : AttribrLogger(debugLogging = false) {
    val errors = mutableListOf<String>()
    val debugs = mutableListOf<String>()

    override fun info(message: String) {}
    override fun debug(message: String) { debugs.add(message) }
    override fun error(message: String, throwable: Throwable?) { errors.add(message) }
}

internal fun tempStateFile(prefix: String): File =
    File.createTempFile(prefix, ".properties").apply { deleteOnExit() }
