// SPDX-License-Identifier: MIT
//
// Monetisation fail-open flag — plain-JVM unit tests.
//
// getEntitlements() keeps its fail-open behaviour (seed defaults when the
// server is unreachable, so offline apps keep working) but the fallback is
// now flagged with `isStale = true` so hosts can distinguish "server says
// seed tier" from "we never reached the server". Contract locked here:
//   • seedDefaults itself is NOT stale (isStale defaults to false), so
//     existing callers and server-parsed entitlements are unaffected.
//   • The fallback shape used by getEntitlements —
//     seedDefaults.copy(isStale = true) — keeps every other field identical.

package com.mrvltechnologies.attribr

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class AttribrEntitlementsTest {

    @Test fun seedDefaults_isNotStale_byDefault() {
        val seed = AttribrEntitlements.seedDefaults
        assertFalse("seedDefaults must not be flagged stale", seed.isStale)
        assertEquals("seed", seed.tier)
        assertFalse(seed.isPaid)
    }

    @Test fun isStale_defaultsFalse_forExistingCallSites() {
        // Compiles without naming isStale — the flag must default to false.
        val ent = AttribrEntitlements(
            tier = "founder",
            features = listOf("receipt_validation"),
            installLimit = 50_000,
            appLimit = 5,
            validUntil = "2027-01-01T00:00:00Z",
        )
        assertFalse(ent.isStale)
        assertTrue(ent.isPaid)
        assertTrue(ent.hasFeature("receipt_validation"))
    }

    @Test fun staleFallback_keepsSeedShape_butIsFlagged() {
        val fallback = AttribrEntitlements.seedDefaults.copy(isStale = true)
        assertTrue(fallback.isStale)
        // Everything else identical to the seed defaults.
        assertEquals(AttribrEntitlements.seedDefaults, fallback.copy(isStale = false))
    }
}
