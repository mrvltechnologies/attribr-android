package com.mrvltechnologies.attribr

/**
 * GDPR / consent state as understood by the SDK.
 *
 * The SDK sends **no data** until [GRANTED] is set via [Attribr.setConsent].
 * [UNKNOWN] is the initial default before the host app has resolved its
 * consent banner; [REVOKED] represents an explicit opt-out.
 */
public enum class ConsentState(public val rawValue: String) {
    UNKNOWN("unknown"),
    GRANTED("granted"),
    REVOKED("revoked"),
    ;

    internal companion object {
        fun fromRaw(raw: String?): ConsentState =
            values().firstOrNull { it.rawValue == raw } ?: UNKNOWN
    }
}
