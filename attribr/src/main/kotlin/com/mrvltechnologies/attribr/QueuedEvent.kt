package com.mrvltechnologies.attribr

/**
 * A single event awaiting network delivery. Persisted by [EventQueue].
 *
 * @property id          Client-generated UUID — used for idempotent removal.
 * @property kind        Semantic classification ([EventKind]).
 * @property endpoint    Edge Function path (e.g. `attribr-track`).
 * @property method      HTTP method — `POST` or `DELETE`.
 * @property payload     Serialised JSON body.
 * @property enqueuedAt  System.currentTimeMillis() at enqueue time.
 * @property attempts    Number of failed delivery attempts so far.
 */
internal data class QueuedEvent(
    val id: String,
    val kind: EventKind,
    val endpoint: String,
    val method: String,
    val payload: String,
    val enqueuedAt: Long,
    val attempts: Int,
)
