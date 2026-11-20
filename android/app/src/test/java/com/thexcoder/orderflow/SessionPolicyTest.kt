package com.thexcoder.orderflow

import com.thexcoder.orderflow.core.LocalSystemClock
import com.thexcoder.orderflow.core.DailySession
import com.thexcoder.orderflow.core.SessionPolicy
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class SessionPolicyTest {
    private class FakeClock(var now: Instant, var timeZone: ZoneId = ZoneId.of("Europe/London")) : Clock() {
        override fun getZone(): ZoneId = timeZone
        override fun instant(): Instant = now
        override fun withZone(zone: ZoneId): Clock = FakeClock(now, zone)
    }
    private fun clock(instant: String, zone: String = "Europe/London") = FakeClock(Instant.parse(instant), ZoneId.of(zone))
    private fun session(clock: Clock): DailySession = SessionPolicy.start("employee-1", "John", false, clock)

    @Test fun loginIsValidUntilLocalMidnight() {
        val clock = clock("2026-11-19T08:30:00Z")
        val session = session(clock)
        assertEquals("John", session.username)
        assertEquals("2026-11-19", session.loginDate)
        assertEquals(Instant.parse("2026-11-20T00:00:00Z").toEpochMilli(), session.expiresAtUtc)
        clock.now = Instant.parse("2026-11-19T23:59:59.999Z")
        assertFalse(SessionPolicy.evaluate(session, clock).expired)
    }

    @Test fun sessionExpiresExactlyAtMidnight() {
        val clock = clock("2026-11-19T08:30:00Z")
        val session = session(clock)
        clock.now = Instant.parse("2026-11-20T00:00:00Z")
        val expiration = SessionPolicy.evaluate(session, clock)
        assertTrue(expiration.expired)
        assertEquals(session.expiresAtUtc, expiration.effectiveAtUtc)
    }

    @Test fun restoredSessionRemainsValidSameDay() {
        val clock = clock("2026-11-19T08:30:00Z")
        val restored = session(clock).copy()
        val restartedClock = clock("2026-11-19T17:45:00Z")
        assertFalse(SessionPolicy.evaluate(restored, restartedClock).expired)
    }

    @Test fun nextDayRestartDetectsOriginalExpiration() {
        val clock = clock("2026-11-19T08:30:00Z")
        val restored = session(clock).copy()
        val restartedClock = clock("2026-11-22T12:45:00Z")
        val result = SessionPolicy.evaluate(restored, restartedClock)
        assertTrue(result.expired)
        assertEquals(Instant.parse("2026-11-20T00:00:00Z").toEpochMilli(), result.effectiveAtUtc)
    }

    @Test fun repeatedExpirationHasStableEffectiveTime() {
        val clock = clock("2026-11-19T08:30:00Z")
        val session = session(clock)
        clock.now = Instant.parse("2026-11-20T02:00:00Z")
        val first = SessionPolicy.evaluate(session, clock)
        clock.now = Instant.parse("2026-11-21T02:00:00Z")
        assertEquals(first, SessionPolicy.evaluate(session, clock))
    }

    @Test fun movingWestCannotExtendOriginalDeadline() {
        val clock = clock("2026-11-19T08:30:00Z")
        val session = session(clock)
        clock.timeZone = ZoneId.of("America/Los_Angeles")
        clock.now = Instant.parse("2026-11-20T00:30:00Z") // Still November 19 locally.
        assertTrue(SessionPolicy.evaluate(session, clock).expired)
        assertEquals(session.expiresAtUtc, SessionPolicy.evaluate(session, clock).effectiveAtUtc)
    }

    @Test fun movingEastShortensDeadline() {
        val clock = clock("2026-11-19T08:30:00Z")
        val session = session(clock)
        clock.timeZone = ZoneId.of("Europe/Helsinki")
        clock.now = Instant.parse("2026-11-19T10:00:00Z")
        val result = SessionPolicy.evaluate(session, clock)
        assertFalse(result.expired)
        assertEquals(Instant.parse("2026-11-19T22:00:00Z").toEpochMilli(), result.effectiveAtUtc)
        assertEquals("Europe/Helsinki", result.timezoneId)
    }

    @Test fun movingEastAcrossMidnightExpiresAtEarlierBoundary() {
        val clock = clock("2026-11-19T08:30:00Z")
        val session = session(clock)
        clock.timeZone = ZoneId.of("Europe/Helsinki")
        clock.now = Instant.parse("2026-11-19T22:30:00Z")
        val result = SessionPolicy.evaluate(session, clock)
        assertTrue(result.expired)
        assertEquals(Instant.parse("2026-11-19T22:00:00Z").toEpochMilli(), result.effectiveAtUtc)
    }

    @Test fun temporaryTimezoneMoveCannotRestoreLongerDeadline() {
        val clock = clock("2026-11-19T08:30:00Z")
        val session = session(clock)
        clock.timeZone = ZoneId.of("Europe/Helsinki")
        val earlier = SessionPolicy.evaluate(session, clock).effectiveAtUtc
        val persisted = session.copy(expiresAtUtc = earlier, expiresTimezoneId = "Europe/Helsinki")
        clock.timeZone = ZoneId.of("Europe/London")
        clock.now = Instant.parse("2026-11-19T22:15:00Z")
        assertTrue(SessionPolicy.evaluate(persisted, clock).expired)
        assertEquals("Europe/Helsinki", SessionPolicy.evaluate(persisted, clock).timezoneId)
    }

    @Test fun zoneDateRollbackRequiresRescan() {
        val clock = clock("2026-11-19T00:30:00Z")
        val session = session(clock)
        clock.timeZone = ZoneId.of("America/Los_Angeles")
        assertTrue(SessionPolicy.evaluate(session, clock).expired)
    }

    @Test fun wallClockRollbackRequiresRescan() {
        val clock = clock("2026-11-19T08:30:00Z")
        val session = session(clock).copy(lastSeenUtc = Instant.parse("2026-11-19T12:00:00Z").toEpochMilli())
        clock.now = Instant.parse("2026-11-19T11:00:00Z")
        assertTrue(SessionPolicy.evaluate(session, clock).expired)
    }

    @Test fun springDstUsesLocalMidnightRatherThanTwentyFourHours() {
        val clock = clock("2026-03-29T00:30:00Z")
        val session = session(clock)
        assertEquals(Instant.parse("2026-03-29T23:00:00Z").toEpochMilli(), session.expiresAtUtc)
    }

    @Test fun autumnDstUsesLocalMidnightRatherThanTwentyFourHours() {
        val clock = clock("2026-10-25T00:30:00Z")
        assertEquals(Instant.parse("2026-10-26T00:00:00Z").toEpochMilli(), session(clock).expiresAtUtc)
    }

    @Test fun productionClockObservesTimezoneChanges() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"))
            assertEquals(ZoneId.of("Europe/London"), LocalSystemClock.zone)
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            assertEquals(ZoneId.of("America/Los_Angeles"), LocalSystemClock.zone)
            assertEquals(ZoneId.of("UTC"), LocalSystemClock.withZone(ZoneId.of("UTC")).zone)
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test fun developerSessionsUseSamePolicy() {
        val clock = clock("2026-11-19T08:30:00Z")
        val session = SessionPolicy.start("test", "Test User", true, clock)
        assertTrue(session.isDeveloper)
        clock.now = Instant.parse("2026-11-20T01:00:00Z")
        assertTrue(SessionPolicy.evaluate(session, clock).expired)
    }
}
