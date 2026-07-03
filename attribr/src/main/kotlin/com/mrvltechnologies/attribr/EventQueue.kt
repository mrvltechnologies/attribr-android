package com.mrvltechnologies.attribr

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bounded FIFO queue of failed events, persisted to `SharedPreferences("attribr")`.
 *
 * Drops the oldest event when [maxSize] would be exceeded — the SDK favours
 * fresh data over historical completeness. On success, [remove] is called;
 * on repeated failure, [markFailed] increments the retry counter so the
 * host can inspect stalled events during debug.
 */
internal class EventQueue(
    context: Context,
    private val maxSize: Int,
    private val logger: AttribrLogger,
) {

    private val prefs = context.applicationContext
        .getSharedPreferences("attribr", Context.MODE_PRIVATE)
    private val lock = Any()

    fun enqueue(event: QueuedEvent) {
        synchronized(lock) {
            val current = load().toMutableList()
            current.add(event)
            while (current.size > maxSize) {
                val dropped = current.removeAt(0)
                logger.debug("Queue overflow — dropping oldest event ${dropped.id}")
            }
            save(current)
        }
    }

    fun peek(): List<QueuedEvent> = synchronized(lock) { load() }

    fun remove(id: String) {
        synchronized(lock) {
            val remaining = load().filterNot { it.id == id }
            save(remaining)
        }
    }

    fun markFailed(id: String) {
        synchronized(lock) {
            val updated = load().map {
                if (it.id == id) it.copy(attempts = it.attempts + 1) else it
            }
            save(updated)
        }
    }

    // ── Persistence ───────────────────────────────────────────────────────

    private fun load(): List<QueuedEvent> {
        val raw = prefs.getString(KEY_QUEUE, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    add(
                        QueuedEvent(
                            id         = obj.getString("id"),
                            kind       = EventKind.fromRaw(obj.optString("kind")),
                            endpoint   = obj.getString("endpoint"),
                            method     = obj.optString("method", "POST"),
                            payload    = obj.getString("payload"),
                            enqueuedAt = obj.optLong("enqueuedAt", 0L),
                            attempts   = obj.optInt("attempts", 0),
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            logger.error("Failed to load queue — clearing corrupt state", t)
            prefs.edit().remove(KEY_QUEUE).apply()
            emptyList()
        }
    }

    private fun save(events: List<QueuedEvent>) {
        val arr = JSONArray()
        for (e in events) {
            arr.put(
                JSONObject().apply {
                    put("id",         e.id)
                    put("kind",       e.kind.value)
                    put("endpoint",   e.endpoint)
                    put("method",     e.method)
                    put("payload",    e.payload)
                    put("enqueuedAt", e.enqueuedAt)
                    put("attempts",   e.attempts)
                }
            )
        }
        prefs.edit().putString(KEY_QUEUE, arr.toString()).apply()
    }

    private companion object {
        const val KEY_QUEUE = "attribr_event_queue"
    }
}
