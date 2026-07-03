package com.mrvltechnologies.attribr

import android.content.Context

/**
 * Stores the current [ConsentState] in `SharedPreferences("attribr")` so it
 * survives process death and app relaunches.
 *
 * The consent decision persists across cold starts — once a user grants
 * consent, the SDK does not re-prompt them on the next launch.
 */
internal class ConsentManager(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("attribr", Context.MODE_PRIVATE)

    var state: ConsentState
        get() = ConsentState.fromRaw(prefs.getString(KEY_CONSENT, null))
        set(value) {
            prefs.edit().putString(KEY_CONSENT, value.rawValue).apply()
        }

    val isGranted: Boolean
        get() = state == ConsentState.GRANTED

    private companion object {
        const val KEY_CONSENT = "attribr_consent_state"
    }
}
