package com.thexcoder.orderflow.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val eventOrder = compareBy<ActivityEvent> { it.timestampUtc }.thenBy { it.eventId }

/** Pure generation. Callers atomically replace a private file after rendering succeeds. */
object DeviceActivityMarkdownExporter {
    fun render(events: List<ActivityEvent>, deviceId: String, deviceModel: String, developer: Boolean = false): String {
        val selected = events.filter { it.isDeveloperEvent == developer }.sortedWith(eventOrder)
        return buildString {
            appendLine("# OrderFlow - Device Activity Log")
            appendLine()
            if (developer) {
                appendLine("**DEVELOPER TEST ACTIVITY — separate from production history**")
                appendLine()
            }
            appendLine("Device: ${markdown(deviceModel)}  ")
            appendLine("Device ID: ${markdown(deviceId)}")
            appendLine()
            appendLine("This report is generated from the complete persisted device activity records.")
            appendLine()
            appendEvents(selected)
        }
    }
}

object OrderMarkdownExporter {
    fun render(orderNumber: String, events: List<ActivityEvent>): String = buildString {
        val number = validateOrderNumber(orderNumber)
        appendLine("# OrderFlow - Order ${markdown(number)}")
        appendLine()
        appendLine("Generated from immutable JSON events. Shared-folder discovery may reveal additional events later.")
        appendLine()
        appendEvents(events.filter { it.orderNumber == number }.sortedWith(eventOrder))
    }
}

private fun StringBuilder.appendEvents(events: List<ActivityEvent>) {
    if (events.isEmpty()) {
        appendLine("No activity has been recorded.")
        return
    }
    var previousDate: String? = null
    events.forEach { event ->
        val instant = Instant.ofEpochMilli(event.timestampUtc)
        val local = instant.atZone(ZoneId.of(event.timezoneId))
        val date = local.toLocalDate().toString()
        // A timezone change can revisit a date. Repeating its heading keeps the
        // absolute chronology intact rather than moving older records forward.
        if (date != previousDate) {
            appendLine("## $date")
            appendLine()
            previousDate = date
        }
        val time = local.format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        appendLine("### $time - ${markdown(event.username ?: "Unknown")} - ${event.eventType}")
        appendLine()
        if (event.isDeveloperEvent) appendLine("- **TEST ACTIVITY**")
        appendLine("- Event UUID: ${markdown(event.eventId)}")
        appendLine("- UTC: $instant")
        appendLine("- Local time: ${local.toLocalDateTime()} ${local.offset} (${markdown(event.timezoneId)})")
        appendLine("- Username: ${markdown(event.username ?: "Not applicable")}")
        event.employeeId?.let { appendLine("- Employee ID: ${markdown(it)}") }
        appendLine("- Device: ${markdown(event.deviceModel)}")
        appendLine("- Device ID: ${markdown(event.deviceId)}")
        event.orderNumber?.let { appendLine("- Order: ${markdown(it)}") }
        event.orderSessionId?.let { appendLine("- Order session: ${markdown(it)}") }
        event.photoFilename?.let { appendLine("- File: ${markdown(it)}") }
        event.detectedAtUtc?.let { appendLine("- Detected at UTC: ${Instant.ofEpochMilli(it)}") }
        event.details?.let { details ->
            appendLine("- Details:")
            details.lineSequence().forEach { appendLine("  ${markdown(it)}  ") }
        }
        appendLine()
    }
}

/** Escape user-controlled Markdown and HTML while preserving readable text. */
private fun markdown(value: String): String = buildString {
    value.forEach { c ->
        when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '\\', '`', '*', '_', '{', '}', '[', ']', '(', ')', '#', '+', '!', '|' -> append('\\').append(c)
            '\n', '\r' -> append(' ')
            else -> if (c.code >= 32 || c == '\t') append(c)
        }
    }
}
