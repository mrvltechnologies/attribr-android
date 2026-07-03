package com.mrvltechnologies.attribr

import android.util.Log

/**
 * Lightweight logger for the SDK. Emits to `logcat` under the `Attribr` tag.
 *
 * `debug` messages are silently dropped unless [debugLogging] is enabled — this
 * keeps release-mode consumer apps free of internal SDK chatter while still
 * surfacing errors and one-time info lines.
 */
internal class AttribrLogger(private val debugLogging: Boolean) {

    fun info(message: String) {
        Log.i(TAG, message)
    }

    fun debug(message: String) {
        if (debugLogging) Log.d(TAG, message)
    }

    fun error(message: String, throwable: Throwable? = null) {
        if (throwable != null) Log.e(TAG, message, throwable) else Log.e(TAG, message)
    }

    private companion object {
        const val TAG = "Attribr"
    }
}
