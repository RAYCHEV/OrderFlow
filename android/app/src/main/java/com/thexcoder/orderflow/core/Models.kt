package com.thexcoder.orderflow.core

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

enum class EventType {
    EMPLOYEE_LOGIN, EMPLOYEE_LOGOUT, SESSION_EXPIRED_MIDNIGHT,
    ORDER_CREATED, ORDER_OPENED, ORDER_SESSION_CLOSED, ORDER_SESSION_CANCELLED,
    PHOTO_ADDED, NOTE_ADDED, ADMIN_LOGIN, ADMIN_LOGOUT,
    EMPLOYEE_REGISTERED, EMPLOYEE_UPDATED, EMPLOYEE_DEACTIVATED,
    STORAGE_CONFIGURED, DEVELOPER_MODE_CHANGED
}

data class ActivityEvent(
    val eventId: String = UUID.randomUUID().toString(),
    val eventType: String,
    val timestampUtc: Long,
    val timezoneId: String,
    val username: String?,
    val employeeId: String?,
    val deviceId: String,
    val deviceModel: String,
    val orderNumber: String? = null,
    val orderSessionId: String? = null,
    val details: String? = null,
    val isDeveloperEvent: Boolean = false,
    val photoFilename: String? = null,
    val detectedAtUtc: Long? = null
)

data class DailySession(
    val sessionId: String = UUID.randomUUID().toString(),
    val employeeId: String,
    val username: String,
    val loginAtUtc: Long,
    val loginDate: String,
    val timezoneId: String,
    val expiresAtUtc: Long,
    val isDeveloper: Boolean = false,
    val lastSeenUtc: Long = loginAtUtc,
    val expiresTimezoneId: String = timezoneId
)

data class Expiration(val expired: Boolean, val effectiveAtUtc: Long, val timezoneId: String)

data class ActivityFilter(
    val fromDate: String = "",
    val toDate: String = "",
    val username: String = "",
    val orderNumber: String = "",
    val eventType: String = "",
    val search: String = "",
    val oldestFirst: Boolean = false,
    val developer: Boolean = false
)

fun newEvent(type: EventType, clock: Clock, deviceId: String, deviceModel: String,
    session: DailySession? = null, orderNumber: String? = null, orderSessionId: String? = null,
    details: String? = null, developer: Boolean = session?.isDeveloper ?: false,
    photoFilename: String? = null): ActivityEvent = ActivityEvent(
    eventType = type.name, timestampUtc = clock.millis(), timezoneId = clock.zone.id,
    username = session?.username ?: "Administrator", employeeId = session?.employeeId,
    deviceId = deviceId, deviceModel = deviceModel, orderNumber = orderNumber,
    orderSessionId = orderSessionId, details = details, isDeveloperEvent = developer,
    photoFilename = photoFilename
)

fun formatTimestamp(timestamp: Long, timezone: String): String =
    Instant.ofEpochMilli(timestamp).atZone(ZoneId.of(timezone)).toString()
