package com.thexcoder.orderflow.core

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Pure daily-session policy. The stored deadline may only move earlier. */
object SessionPolicy {
    fun start(employeeId: String, username: String, developer: Boolean, clock: Clock): DailySession {
        val now = clock.instant()
        val today = now.atZone(clock.zone).toLocalDate()
        return DailySession(
            employeeId = employeeId,
            username = username,
            loginAtUtc = now.toEpochMilli(),
            loginDate = today.toString(),
            timezoneId = clock.zone.id,
            expiresAtUtc = today.plusDays(1).atStartOfDay(clock.zone).toInstant().toEpochMilli(),
            isDeveloper = developer
        )
    }

    /**
     * For a valid session, effectiveAtUtc is its latest allowed deadline. Persist the
     * minimum deadline and lastSeenUtc after checking. No timezone change can extend
     * a deadline already observed. Date/clock rollback requires a fresh login.
     */
    fun evaluate(session: DailySession, clock: Clock): Expiration {
        val now = clock.millis()
        val loginDate = LocalDate.parse(session.loginDate)
        val originalZone = ZoneId.of(session.timezoneId)
        val currentZone = clock.zone
        val currentDate = Instant.ofEpochMilli(now).atZone(currentZone).toLocalDate()
        val originalMidnight = loginDate.plusDays(1).atStartOfDay(originalZone).toInstant().toEpochMilli()
        val fixedDeadline = minOf(session.expiresAtUtc, originalMidnight)
        if (now >= fixedDeadline) return Expiration(true, fixedDeadline, session.expiresTimezoneId)
        if (now < session.lastSeenUtc || currentDate < loginDate) {
            return Expiration(true, now, currentZone.id)
        }
        val currentMidnight = loginDate.plusDays(1).atStartOfDay(currentZone).toInstant().toEpochMilli()
        if (currentDate > loginDate) {
            // A timezone jump may cross midnight instantaneously. Never predate
            // login when that zone's midnight occurred before the session existed.
            val effective = if (currentMidnight >= session.loginAtUtc) minOf(now, currentMidnight) else now
            return Expiration(true, minOf(fixedDeadline, effective), currentZone.id)
        }
        val deadline = minOf(fixedDeadline, currentMidnight)
        val deadlineZone = if (currentMidnight < fixedDeadline) currentZone.id else session.expiresTimezoneId
        return Expiration(now >= deadline, deadline, deadlineZone)
    }
}
