package com.mrvltechnologies.attribr

/**
 * Classifies queued events by the semantic they represent. Used only for
 * logging + local persistence — the wire payload's endpoint determines how
 * the backend routes the event.
 */
internal enum class EventKind(val value: String) {
    TRACK("track"),
    ATTRIBUTE("attribute"),
    EVENT("event"),
    REVENUE("revenue"),
    AD_REVENUE("ad_revenue"),
    SUBSCRIPTION_EVENT("subscription_event"),
    REFERRER("referrer"),
    PUSH_TOKEN("push_token"),
    DEEP_LINK("deep_link"),
    ;

    companion object {
        fun fromRaw(raw: String?): EventKind =
            values().firstOrNull { it.value == raw } ?: EVENT
    }
}
