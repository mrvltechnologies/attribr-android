package com.mrvltechnologies.attribr

/**
 * Channel type for an attribution event. Mirrors the Swift SDK's
 * `AttributionSource` enum verbatim.
 */
public enum class AttributionSource(public val value: String) {
    RIPPL("rippl"),
    UTM("utm"),
    CUSTOM("custom"),
}
