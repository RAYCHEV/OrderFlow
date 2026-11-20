package com.thexcoder.orderflow

import com.thexcoder.orderflow.core.*
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class EventCodecMarkdownTest {
    private fun event(type: EventType = EventType.ORDER_CREATED, time: String = "2026-11-19T08:00:00Z") = ActivityEvent(
        eventType = type.name, timestampUtc = Instant.parse(time).toEpochMilli(), timezoneId = "Europe/London",
        username = "John", employeeId = "employee-1", deviceId = "device-1", deviceModel = "Google Pixel 4a",
        orderNumber = "ord123", orderSessionId = "order-session-1", details = "Saved successfully"
    )
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected invalid content to be rejected") } catch (_: IllegalArgumentException) { }
    }

    @Test fun portableJsonRoundTripRetainsAllFields() {
        val original = event(EventType.PHOTO_ADDED).copy(
            photoFilename = "photos/device-1/ord123-1.jpg", details = "Line 1\n\"Line 2\" – Unicode ✓",
            detectedAtUtc = Instant.parse("2026-11-19T08:10:00Z").toEpochMilli()
        )
        assertEquals(original, EventCodec.decode(EventCodec.encode(original)))
    }

    @Test fun nullableFieldsRoundTrip() {
        val original = event(EventType.ADMIN_LOGIN).copy(employeeId = null, username = null, orderNumber = null,
            orderSessionId = null, details = null, photoFilename = null)
        assertEquals(original, EventCodec.decode(EventCodec.encode(original)))
    }

    @Test fun schemaVersionIsRequired() { rejects { EventCodec.decode("{}") } }
    @Test fun futureSchemaIsRejected() {
        rejects { EventCodec.decode(EventCodec.encode(event()).replace("\"schemaVersion\": 1", "\"schemaVersion\": 999")) }
    }
    @Test fun unknownEventTypesAreRejected() { rejects { EventCodec.encode(event().copy(eventType = "DELETE_EVERYTHING")) } }
    @Test fun uuidIsRequired() { rejects { EventCodec.encode(event().copy(eventId = "1-1-1-1-1")) } }
    @Test fun timezoneMustBeValid() { rejects { EventCodec.encode(event().copy(timezoneId = "Invalid/Zone")) } }
    @Test fun negativeTimestampsAreRejected() { rejects { EventCodec.encode(event().copy(timestampUtc = -1)) } }
    @Test fun orderTraversalIsRejected() { rejects { validateOrderNumber("../../private") } }
    @Test fun orderNamesAreTrimmedAndRestricted() {
        assertEquals("ord-123_1", validateOrderNumber(" ord-123_1 "))
        rejects { validateOrderNumber("order/name") }
        rejects { validateOrderNumber(".") }
        rejects { validateOrderNumber("") }
    }
    @Test fun photoTraversalIsRejected() { rejects { EventCodec.encode(event().copy(photoFilename = "../private.jpg")) } }
    @Test fun absolutePhotoPathsAreRejected() { rejects { EventCodec.encode(event().copy(photoFilename = "/private.jpg")) } }
    @Test fun photoFileTypeIsRestricted() { rejects { EventCodec.encode(event().copy(photoFilename = "test.exe")) } }
    @Test fun oversizedEventsAreRejected() { rejects { EventCodec.decode("x".repeat(262_145)) } }

    @Test fun importedPhotoCannotReferenceAnotherDevicesNamespace() {
        rejects { EventCodec.encode(event(EventType.PHOTO_ADDED).copy(photoFilename = "photos/device-2/ord123-1.jpg")) }
    }
    @Test fun importedPhotoCannotReferenceAnotherOrder() {
        rejects { EventCodec.encode(event(EventType.PHOTO_ADDED).copy(photoFilename = "photos/device-1/ord456-1.jpg")) }
    }
    @Test fun photoNumbersMustBePositive() {
        rejects { EventCodec.encode(event(EventType.PHOTO_ADDED).copy(photoFilename = "photos/device-1/ord123-0.jpg")) }
    }
    @Test fun nonPhotoEventsCannotReferenceFiles() {
        rejects { EventCodec.encode(event().copy(photoFilename = "photos/device-1/ord123-1.jpg")) }
    }
    @Test fun malformedJsonIsRejected() { rejects { EventCodec.decode("not JSON") } }

    @Test fun deviceMarkdownRegeneratesCompleteChronologicalHistory() {
        val creation = event()
        val note = event(EventType.NOTE_ADDED, "2026-11-19T08:10:00Z")
        val close = event(EventType.ORDER_SESSION_CLOSED, "2026-11-20T08:20:00Z")
        val report = DeviceActivityMarkdownExporter.render(listOf(close, note, creation), "device-1", "Google Pixel 4a")
        assertTrue(report.indexOf("ORDER_CREATED") < report.indexOf("NOTE_ADDED"))
        assertTrue(report.indexOf("NOTE_ADDED") < report.indexOf("ORDER_SESSION_CLOSED"))
        assertTrue(report.contains("## 2026-11-19"))
        assertTrue(report.contains("## 2026-11-20"))
        listOf(creation, note, close).forEach { assertTrue(report.contains(it.eventId)) }
        assertEquals(report, DeviceActivityMarkdownExporter.render(listOf(close, creation, note), "device-1", "Google Pixel 4a"))
    }

    @Test fun photoAndExpirationMetadataAreVisible() {
        val photo = event(EventType.PHOTO_ADDED).copy(photoFilename = "photos/device-1/ord123-2.jpg")
        val expiration = event(EventType.SESSION_EXPIRED_MIDNIGHT, "2026-11-20T00:00:00Z").copy(
            detectedAtUtc = Instant.parse("2026-11-20T09:00:00Z").toEpochMilli())
        val report = DeviceActivityMarkdownExporter.render(listOf(photo, expiration), "device-1", "Google Pixel 4a")
        assertTrue(report.contains("ord123-2.jpg"))
        assertTrue(report.contains("Detected at UTC: 2026-11-20T09:00:00Z"))
        assertTrue(report.contains("Employee ID: employee-1"))
        assertTrue(report.contains("Order session: order-session-1"))
    }

    @Test fun developerActivityIsIsolatedByDefault() {
        val production = event()
        val test = event(EventType.NOTE_ADDED).copy(isDeveloperEvent = true)
        val report = DeviceActivityMarkdownExporter.render(listOf(production, test), "device-1", "Pixel")
        assertTrue(report.contains(production.eventId))
        assertFalse(report.contains(test.eventId))
        val developmentReport = DeviceActivityMarkdownExporter.render(listOf(production, test), "device-1", "Pixel", true)
        assertTrue(developmentReport.contains(test.eventId))
        assertFalse(developmentReport.contains(production.eventId))
        assertTrue(developmentReport.contains("TEST ACTIVITY"))
    }

    @Test fun untrustedMarkdownIsEscaped() {
        val malicious = event().copy(username = "<script>Bad</script>", details = "[link](https://bad)\n# heading")
        val report = DeviceActivityMarkdownExporter.render(listOf(malicious), "device-1", "Pixel")
        assertFalse(report.contains("<script>"))
        assertFalse(report.contains("[link](https://bad)"))
        assertTrue(report.contains("&lt;script&gt;"))
        assertTrue(report.contains("\\# heading"))
    }

    @Test fun orderMarkdownIncludesOnlyThatOrdersHistory() {
        val first = event()
        val second = event().copy(orderNumber = "ord456")
        val report = OrderMarkdownExporter.render("ord123", listOf(first, second))
        assertTrue(report.contains(first.eventId))
        assertFalse(report.contains(second.eventId))
    }

    @Test fun timezoneChangesPreserveAbsoluteChronology() {
        val first = event(time = "2026-11-20T00:30:00Z")
        val second = event(EventType.NOTE_ADDED, "2026-11-20T01:00:00Z").copy(timezoneId = "America/Los_Angeles")
        val report = DeviceActivityMarkdownExporter.render(listOf(second, first), "device-1", "Pixel")
        assertTrue(report.indexOf(first.eventId) < report.indexOf(second.eventId))
        assertTrue(report.contains("## 2026-11-19"))
    }
}
