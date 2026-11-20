package com.thexcoder.orderflow.core

import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

fun validateOrderNumber(value: String): String {
    val result = value.trim()
    require(result.matches(Regex("[A-Za-z0-9_-]{1,64}"))) {
        "Order number must contain 1–64 letters, numbers, underscores or hyphens."
    }
    return result
}

/** Versioned, immutable, portable event format. Imported content is untrusted. */
object EventCodec {
    private val json = Json { prettyPrint = true }

    fun encode(event: ActivityEvent): String {
        validate(event)
        return buildJsonObject {
            put("schemaVersion", 1)
            put("eventId", event.eventId)
            put("eventType", event.eventType)
            put("timestampUtc", event.timestampUtc)
            put("timezoneId", event.timezoneId)
            nullable("username", event.username)
            nullable("employeeId", event.employeeId)
            put("deviceId", event.deviceId)
            put("deviceModel", event.deviceModel)
            nullable("orderNumber", event.orderNumber)
            nullable("orderSessionId", event.orderSessionId)
            nullable("details", event.details)
            put("isDeveloperEvent", event.isDeveloperEvent)
            nullable("photoFilename", event.photoFilename)
            put("detectedAtUtc", event.detectedAtUtc?.let { JsonPrimitive(it) } ?: JsonNull)
        }.let { json.encodeToString(JsonObject.serializer(), it) }
    }

    fun decode(value: String): ActivityEvent {
        require(value.length <= 262_144) { "Event document is too large." }
        return try {
            val obj = json.parseToJsonElement(value).jsonObject
            require(obj.getValue("schemaVersion").jsonPrimitive.int == 1) { "Unsupported event schema." }
            ActivityEvent(
                eventId = obj.required("eventId"),
                eventType = obj.required("eventType"),
                timestampUtc = obj.getValue("timestampUtc").jsonPrimitive.long,
                timezoneId = obj.required("timezoneId"),
                username = obj.optional("username"),
                employeeId = obj.optional("employeeId"),
                deviceId = obj.required("deviceId"),
                deviceModel = obj.required("deviceModel"),
                orderNumber = obj.optional("orderNumber"),
                orderSessionId = obj.optional("orderSessionId"),
                details = obj.optional("details"),
                isDeveloperEvent = obj.getValue("isDeveloperEvent").jsonPrimitive.boolean,
                photoFilename = obj.optional("photoFilename"),
                detectedAtUtc = obj["detectedAtUtc"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.long
            ).also(::validate)
        } catch (error: RuntimeException) {
            throw IllegalArgumentException("Invalid or unsupported activity event.", error)
        }
    }

    private fun validate(event: ActivityEvent) {
        require(event.eventId.length == 36 && UUID.fromString(event.eventId).toString() == event.eventId) {
            "Invalid event UUID."
        }
        EventType.valueOf(event.eventType)
        require(event.timestampUtc >= 0 && (event.detectedAtUtc == null || event.detectedAtUtc >= 0)) {
            "Invalid event timestamp."
        }
        Instant.ofEpochMilli(event.timestampUtc)
        require(runCatching { ZoneId.of(event.timezoneId) }.isSuccess) { "Invalid event timezone." }
        require(event.deviceId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        require(event.deviceModel.isNotBlank() && event.deviceModel.length <= 256 && !event.deviceModel.containsControl())
        event.username?.let { require(it.isNotBlank() && it.length <= 256 && !it.containsControl()) }
        event.employeeId?.let { require(it.isNotBlank() && it.length <= 128 && !it.containsControl()) }
        event.orderNumber?.let { require(validateOrderNumber(it) == it) }
        event.orderSessionId?.let { require(it.isNotBlank() && it.length <= 128 && !it.containsControl()) }
        event.details?.let { require(it.length <= 65_536 && !it.contains('\u0000')) }
        event.photoFilename?.let { filename ->
            require(filename.length <= 512 && filename.endsWith(".jpg")) { "Invalid photo filename." }
            require(filename.split('/').all { it != "." && it != ".." && it.matches(Regex("[A-Za-z0-9_.-]+")) }) {
                "Photo path must be a safe relative path."
            }
            require(event.eventType == EventType.PHOTO_ADDED.name && event.orderNumber != null) {
                "Only an order photo event may reference a photo."
            }
            val prefix = "photos/${event.deviceId}/${event.orderNumber}-"
            require(Regex("${Regex.escape(prefix)}[1-9][0-9]*\\.jpg").matches(filename)) {
                "Photo path does not match its device and order."
            }
        }
    }

    private fun String.containsControl(): Boolean = any { it.code < 32 || it.code == 127 }
    private fun JsonObject.required(key: String): String = getValue(key).jsonPrimitive.contentOrNull
        ?: throw IllegalArgumentException("Missing $key")
    private fun JsonObject.optional(key: String): String? = get(key)?.jsonPrimitive?.contentOrNull
    private fun kotlinx.serialization.json.JsonObjectBuilder.nullable(key: String, value: String?) {
        put(key, value?.let(::JsonPrimitive) ?: JsonNull)
    }
}
