package com.mrvltechnologies.attribr

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

/** A denied/revoked SDK dispatch must return before opening a connection. */
class NetworkConsentGateTest {
    @Test fun deniedConsentBlocksTrackAndNonErasureDelete() {
        var allowed = false
        val network = NetworkClient("test-public-key", AttribrConfiguration(), RecordingLogger()) { allowed }
        val p = network.post("attribr-track", "{}")
        val d = network.delete("not-erasure", "{}")
        assertTrue(p is NetworkResult.Failure)
        assertTrue(d is NetworkResult.Failure)
        assertEquals(0, (p as NetworkResult.Failure).statusCode)
        allowed = false
    }
}
