package com.mrvltechnologies.attribr

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bounded FIFO queue of failed events, persisted as a single JSON-array
 * string value in [DeviceStateStore] (`noBackupFilesDir`).
 *
 * ## Storage (Auto Backup fix)
 * The queue used to live in `SharedPreferences("attribr")`, which Android
 * Auto Backup restores onto fresh installs and new devices — a restored
 * phone would replay the OLD phone's queued events, with the old
 * device_hash baked into every payload. The queue now lives in
 * `noBackupFilesDir`, which the OS never backs up.
 *
 * A legacy prefs queue found at construction is DISCARDED, not migrated:
 * a restored blob is exactly what must never be replayed, and there is no
 * on-device way to distinguish a restore from a genuine in-place upgrade.
 * Losing a handful of queued retry events on one upgrade is an acceptable
 * trade for never replaying another device's events.
 *
 * Drops the oldest event when [maxSize] would be exceeded — the SDK favours
 * fresh data over historical completeness. On success, [remove] is called;
 * on repeated failure, [markFailed] increments the retry counter and drops
 * the event permanently once its attempts exceed [MAX_ATTEMPTS] (a
 * permanently-rejected event must not retry forever).
 */
internal class EventQueue internal constructor(
    private val store: DeviceStateStore,
    legacyPrefs: SharedPreferences?,
    private val maxSize: Int,
    private val logger: AttribrLogger,
) {

    constructor(context: Context, maxSize: Int, logger: AttribrLogger) : this(
        DeviceStateStore(context),
        context.applicationContext.getSharedPreferences("attribr", Context.MODE_PRIVATE),
        maxSize,
        logger,
    )

    private val lock = Any()

    init {
        // One-time cleanup of the legacy SharedPreferences queue — discard,
        // never migrate (see class doc for the restore-vs-upgrade tradeoff).
        legacyPrefs?.let { prefs ->
            if (prefs.contains(KEY_QUEUE)) {
                prefs.edit().remove(KEY_QUEUE).apply()
                logger.debug("Discarded legacy SharedPreferences event queue (possible Auto Backup restore)")
            }
        }
    }

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
            // Permanently drop events whose attempts exceed the cap — e.g. a
            // payload the server rejects with 400 on every attempt would
            // otherwise retry forever on every flush.
            val (kept, exhausted) = updated.partition { it.attempts <= MAX_ATTEMPTS }
            for (dead in exhausted) {
                logger.error("Dropping event ${dead.id} (${dead.kind.value}) after ${dead.attempts} failed attempts")
            }
            save(kept)
        }
    }

    // ── Persistence ───────────────────────────────────────────────────────

    private fun load(): List<QueuedEvent> {
        val raw = store.getString(KEY_QUEUE) ?: return emptyList()
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
            store.remove(KEY_QUEUE)
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
        store.putString(KEY_QUEUE, arr.toString())
    }

    internal companion object {
        const val KEY_QUEUE = "attribr_event_queue"

        /** An event is dropped once its failed attempts exceed this cap. */
        const val MAX_ATTEMPTS = 10
    }
}
