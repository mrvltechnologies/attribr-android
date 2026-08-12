// SPDX-License-Identifier: MIT
//
// Auto Backup fix + retry-cap — EventQueue JVM unit tests.
//
// Queue persistence moved from SharedPreferences("attribr") (restored by
// Android Auto Backup onto new devices — a restored phone replayed the OLD
// phone's queued events with the old device_hash baked into the payloads)
// to DeviceStateStore (noBackupFilesDir). Contract locked here:
//   • enqueue/peek/remove round-trips persist across instances over the
//     same store file (simulating separate launches).
//   • Overflow drops the oldest event.
//   • A legacy prefs queue is DISCARDED (never migrated) and the prefs key
//     removed — a restored blob must never be replayed.
//   • markFailed increments attempts; once attempts exceed MAX_ATTEMPTS the
//     event is dropped permanently and logged at error level.
//   • Corrupt store content is cleared, never crashes.
//
// Uses org.json (real impl via unitTests.isIncludeAndroidResources) and
// RecordingLogger (android.util.Log is unmocked on plain JVM).
//
// Run with: ./gradlew :attribr:testDebugUnitTest

package com.mrvltechnologies.attribr

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.io.File

class EventQueueTest {

    private fun event(id: String, attempts: Int = 0, payload: String = """{"k":"v"}""") = QueuedEvent(
        id         = id,
        kind       = EventKind.TRACK,
        endpoint   = "attribr-track",
        method     = "POST",
        payload    = payload,
        enqueuedAt = 1_700_000_000_000L,
        attempts   = attempts,
    )

    private fun queue(
        file: File = tempStateFile("attribr_eq_test"),
        prefs: FakeSharedPrefs? = null,
        maxSize: Int = 500,
        logger: RecordingLogger = RecordingLogger(),
    ): EventQueue = EventQueue(DeviceStateStore(file), prefs, maxSize, logger)

    // ── Round-trips ──────────────────────────────────────────────────────

    @Test fun enqueue_peek_roundTrip() {
        val q = queue()
        q.enqueue(event("a"))
        q.enqueue(event("b"))
        assertEquals(listOf("a", "b"), q.peek().map { it.id })
        assertEquals("""{"k":"v"}""", q.peek()[0].payload)
    }

    @Test fun queue_persistsAcrossInstances_overSameStoreFile() {
        val file = tempStateFile("attribr_eq_persist")
        queue(file).enqueue(event("a"))
        // Brand-new instance over the same file — simulates a fresh launch.
        val reloaded = queue(file).peek()
        assertEquals(1, reloaded.size)
        assertEquals("a", reloaded[0].id)
        assertEquals(EventKind.TRACK, reloaded[0].kind)
    }

    @Test fun remove_deletesOnlyThatEvent() {
        val q = queue()
        q.enqueue(event("a"))
        q.enqueue(event("b"))
        q.remove("a")
        assertEquals(listOf("b"), q.peek().map { it.id })
    }

    @Test fun overflow_dropsOldest() {
        val q = queue(maxSize = 2)
        q.enqueue(event("a"))
        q.enqueue(event("b"))
        q.enqueue(event("c"))
        assertEquals(listOf("b", "c"), q.peek().map { it.id })
    }

    // ── Legacy prefs queue: discard, never migrate ───────────────────────

    @Test fun legacyPrefsQueue_isDiscardedNotMigrated_andPrefsKeyRemoved() {
        val prefs = FakeSharedPrefs()
        // A restored-from-backup blob from a DIFFERENT device.
        prefs.store[EventQueue.KEY_QUEUE] =
            """[{"id":"old","kind":"track","endpoint":"attribr-track","method":"POST","payload":"{}","enqueuedAt":1,"attempts":0}]"""

        val q = queue(prefs = prefs)

        assertTrue("restored blob must never be replayed", q.peek().isEmpty())
        assertFalse("prefs key must be removed", prefs.contains(EventQueue.KEY_QUEUE))
    }

    @Test fun noLegacyPrefsKey_constructionIsHarmless() {
        val prefs = FakeSharedPrefs()
        val q = queue(prefs = prefs)
        assertTrue(q.peek().isEmpty())
        assertTrue(prefs.store.isEmpty())
    }

    // ── Retry cap ────────────────────────────────────────────────────────

    @Test fun markFailed_incrementsAttempts() {
        val q = queue()
        q.enqueue(event("a", attempts = 1))
        q.markFailed("a")
        assertEquals(2, q.peek().single().attempts)
    }

    @Test fun markFailed_dropsEvent_onceAttemptsExceedCap() {
        val logger = RecordingLogger()
        val q = queue(logger = logger)
        q.enqueue(event("dead", attempts = EventQueue.MAX_ATTEMPTS)) // next failure → 11 > 10
        q.enqueue(event("alive", attempts = EventQueue.MAX_ATTEMPTS - 1))

        q.markFailed("dead")
        q.markFailed("alive")

        assertEquals(listOf("alive"), q.peek().map { it.id })
        assertEquals(EventQueue.MAX_ATTEMPTS, q.peek().single().attempts)
        assertTrue(
            "permanent drop must be logged at error level",
            logger.errors.any { it.contains("dead") },
        )
    }

    // ── Corruption ───────────────────────────────────────────────────────

    @Test fun corruptStoreValue_isCleared_andQueueRemainsUsable() {
        val file = tempStateFile("attribr_eq_corrupt")
        val store = DeviceStateStore(file)
        store.putString(EventQueue.KEY_QUEUE, "{not json[")

        val q = EventQueue(store, null, 500, RecordingLogger())
        assertTrue(q.peek().isEmpty())
        assertNull("corrupt state must be cleared", store.getString(EventQueue.KEY_QUEUE))
        q.enqueue(event("a"))
        assertEquals(listOf("a"), q.peek().map { it.id })
    }
}
