package com.mrvltechnologies.attribr

/**
 * Immutable configuration for the Attribr SDK.
 *
 * Pass to [Attribr.initialize] to override defaults. Mirrors the Swift SDK's
 * `AttribrConfiguration` struct.
 *
 * @property baseURL         Base URL of the Attribr Edge Functions.
 * @property maxQueueSize    Max events buffered offline before dropping oldest. Default 500.
 * @property requestTimeout  Per-request timeout in milliseconds. Default 15 000.
 * @property debugLogging    When true, [AttribrLogger] emits `debug`/`info`/`error` to logcat.
 * @property maxRetries      Reserved for future exponential-backoff retry. Default 3.
 */
public data class AttribrConfiguration(
    val baseURL: String = "https://pblzmoxcwpqywuyubdim.supabase.co/functions/v1",
    val maxQueueSize: Int = 500,
    val requestTimeout: Long = 15_000L,
    val debugLogging: Boolean = false,
    val maxRetries: Int = 3,
)
