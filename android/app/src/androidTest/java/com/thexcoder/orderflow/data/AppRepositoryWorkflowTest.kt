package com.thexcoder.orderflow.data

import android.content.Context
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.thexcoder.orderflow.core.ActivityEvent
import com.thexcoder.orderflow.core.ActivityFilter
import com.thexcoder.orderflow.core.EventCodec
import com.thexcoder.orderflow.core.EventType
import com.thexcoder.orderflow.core.PinHasher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** Exercises real repository, Room, private files and SAF; no mocked success screens. */
@RunWith(AndroidJUnit4::class)
class AppRepositoryWorkflowTest {
    private class MutableClock(var time: Instant, var timeZone: ZoneId = ZoneId.of("Europe/London")) : Clock() {
        override fun instant(): Instant = time
        override fun getZone(): ZoneId = timeZone
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(time, zone)
    }

    private class Harness(val context: Context, val clock: MutableClock, val tree: String) {
        private val databaseName = "workflow-${UUID.randomUUID()}.db"
        val testOrders = mutableListOf<String>()
        var db: OrderDatabase = openDatabase()
        var repo = repository()
        private fun openDatabase(): OrderDatabase = Room.databaseBuilder(context, OrderDatabase::class.java, databaseName).build()
        private fun repository() = AppRepository(context, database = db, clockProvider = { clock })
        suspend fun restart() {
            db.close()
            db = openDatabase()
            repo = repository()
            repo.initialize()
        }
        fun newOrder(): String = "workflow-${UUID.randomUUID()}".also { testOrders += it }
        suspend fun enableAndLoginTestUser() {
            repo.authenticateAdmin(PIN)
            repo.setDeveloperMode(true)
            assertEquals("Test User", repo.loginBarcode(AppRepository.TEST_BARCODE, simulated = true))
        }
        suspend fun events(developer: Boolean = true) = db.dao().allActivity(developer).map { it.toDomain() }
        fun cleanup() {
            db.close()
            context.deleteDatabase(databaseName)
            testOrders.forEach { File(context.filesDir, "developer/orders/$it").deleteRecursively() }
            DocumentFile.fromTreeUri(context, android.net.Uri.parse(tree))?.delete()
        }
    }

    /** DataStore intentionally remains a singleton. Seed test settings via its public store API. */
    private suspend fun withHarness(block: suspend (Harness) -> Unit) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        // DocumentsProvider lives in the separate test APK. This capability is test-only.
        automation.adoptShellPermissionIdentity(android.Manifest.permission.MANAGE_DOCUMENTS)
        try {
            val context: Context = ApplicationProvider.getApplicationContext()
            val providerRoot = DocumentsContract.buildTreeDocumentUri(TestDocumentsProvider.AUTHORITY, "root")
            TestStorageGrants.grant(context, providerRoot.toString())
            val parent = requireNotNull(DocumentFile.fromTreeUri(context, providerRoot))
            val directory = requireNotNull(parent.createDirectory("workflow-${UUID.randomUUID()}"))
            val tree = DocumentsContract.buildTreeDocumentUri(TestDocumentsProvider.AUTHORITY,
                DocumentsContract.getDocumentId(directory.uri)).toString()
            TestStorageGrants.grant(context, tree)
            val settings = SettingsStore(context)
            settings.setPin(PIN_VERIFIER)
            settings.setFailures(0, 0)
            settings.setDeveloper(false)
            settings.setStorage(tree)
            val harness = Harness(context, MutableClock(Instant.parse("2026-11-19T08:00:00Z")), tree)
            try {
                harness.repo.initialize()
                block(harness)
            } finally {
                try { harness.cleanup() } finally { settings.setDeveloper(false) }
            }
        } finally {
            automation.dropShellPermissionIdentity()
        }
    }

    @Test fun registeredBarcodeValidationAndAdminProtection() = runBlocking {
        withHarness { h ->
            assertTrue(runCatching { h.repo.saveEmployee(null, "Alice", "ALICE-001", true) }.isFailure)
            assertTrue(runCatching { h.repo.activity.page(ActivityFilter(), 0) }.isFailure)
            assertTrue(runCatching { h.repo.loginBarcode("UNKNOWN") }.isFailure)
            assertTrue(runCatching { h.repo.loginBarcode(AppRepository.TEST_BARCODE, true) }.isFailure)
            assertTrue(h.events(false).isEmpty())
            assertTrue(runCatching { h.repo.authenticateAdmin("999999") }.isFailure)
            assertFalse(h.repo.adminAccess.authenticated)
            h.repo.authenticateAdmin(PIN)
            h.repo.saveEmployee(null, "Alice", "ALICE-001", true)
            val employee = h.db.dao().employeeByBarcode("ALICE-001")!!
            h.repo.saveEmployee(employee.id, "Alice", "ALICE-001", false)
            assertTrue(runCatching { h.repo.loginBarcode("ALICE-001") }.isFailure)
            assertEquals(0, h.events(false).count { it.eventType == EventType.EMPLOYEE_LOGIN.name })
            h.repo.saveEmployee(employee.id, "Alice Updated", "ALICE-001", true)
            assertEquals("Alice Updated", h.repo.loginBarcode(" ALICE-001 "))
            assertEquals(employee.id, h.repo.snapshot().session?.employeeId)
            assertEquals(1, h.events(false).count { it.eventType == EventType.EMPLOYEE_LOGIN.name })
            assertTrue(h.events(false).any { it.eventType == EventType.EMPLOYEE_REGISTERED.name })
            assertTrue(h.events(false).any { it.eventType == EventType.EMPLOYEE_DEACTIVATED.name })
            assertTrue(h.events(false).any { it.eventType == EventType.EMPLOYEE_UPDATED.name })
        }
    }

    @Test fun pinLockoutSurvivesRestartAndUsesInjectedClock() = runBlocking {
        withHarness { h ->
            repeat(5) { assertTrue(runCatching { h.repo.authenticateAdmin("999999") }.isFailure) }
            assertTrue(runCatching { h.repo.authenticateAdmin(PIN) }.isFailure)
            assertFalse(h.repo.adminAccess.authenticated)
            h.restart()
            assertTrue(runCatching { h.repo.authenticateAdmin(PIN) }.isFailure)
            h.clock.time = h.clock.time.plusSeconds(61)
            h.repo.authenticateAdmin(PIN)
            assertTrue(h.repo.adminAccess.authenticated)
            assertEquals(1, h.events(false).count { it.eventType == EventType.ADMIN_LOGIN.name })
        }
    }

    @Test fun employeeDeactivationInterruptsOpenOrderWithoutLosingSavedNotes() = runBlocking {
        withHarness { h ->
            h.repo.authenticateAdmin(PIN)
            h.repo.saveEmployee(null, "Alice", "ALICE-ACTIVE", true)
            val employee = requireNotNull(h.db.dao().employeeByBarcode("ALICE-ACTIVE"))
            h.repo.loginBarcode("ALICE-ACTIVE")
            val order = h.newOrder()
            h.repo.createOrder(order, "Saved before deactivation")
            h.repo.saveEmployee(employee.id, "Alice", "ALICE-ACTIVE", false)
            assertNull(h.repo.snapshot().session)
            assertTrue(runCatching { h.repo.addNote("Rejected after deactivation") }.isFailure)
            val history = h.db.dao().eventsForOrder(orderKey(order, false)).map { EventCodec.decode(it.eventJson) }
            assertTrue(history.any { it.details == "Saved before deactivation" })
            assertEquals(1, history.count { it.eventType == EventType.ORDER_SESSION_CANCELLED.name })
            assertEquals(0, history.count { it.eventType == EventType.ORDER_SESSION_CLOSED.name })
            assertTrue(runCatching { h.repo.loginBarcode("ALICE-ACTIVE") }.isFailure)
        }
    }

    @Test fun sessionsRestoreSameDayAndExpireExactlyOnceAfterRestart() = runBlocking {
        withHarness { h ->
            h.enableAndLoginTestUser()
            val order = h.newOrder()
            h.repo.createOrder(order, "Saved before midnight")
            val original = requireNotNull(h.repo.snapshot().session)
            h.clock.time = Instant.parse("2026-11-19T18:00:00Z")
            h.restart()
            assertEquals(original.id, h.repo.snapshot().session?.id)
            assertEquals(order, h.repo.snapshot().order?.orderNumber)
            assertFalse(h.repo.adminAccess.authenticated)
            h.clock.time = Instant.parse("2026-11-20T09:15:00Z")
            h.restart()
            assertNull(h.repo.snapshot().session)
            assertTrue(runCatching { h.repo.addNote("Must be rejected") }.isFailure)
            repeat(3) { h.repo.validateSession(); h.repo.snapshot() }
            val events = h.events()
            val expiration = events.single { it.eventType == EventType.SESSION_EXPIRED_MIDNIGHT.name }
            assertEquals(Instant.parse("2026-11-20T00:00:00Z").toEpochMilli(), expiration.timestampUtc)
            assertEquals(h.clock.millis(), expiration.detectedAtUtc)
            assertEquals(1, events.count { it.eventType == EventType.ORDER_SESSION_CANCELLED.name })
            assertEquals(0, events.count { it.eventType == EventType.ORDER_SESSION_CLOSED.name })
            val saved = h.db.dao().eventsForOrder(orderKey(order, true)).map { EventCodec.decode(it.eventJson) }
            assertTrue(saved.any { it.details == "Saved before midnight" })
            assertEquals("Test User", h.repo.loginBarcode(AppRepository.TEST_BARCODE, true))
            assertNotEquals(original.id, h.repo.snapshot().session?.id)
            h.repo.openOrder(order)
            h.repo.addNote("Saved after new scan")
            assertTrue(h.repo.snapshot().orderEvents.any { it.details == "Saved after new scan" })
        }
    }

    @Test fun createNotesPhotoCloseReopenAndLogoutKeepCompleteHistory() = runBlocking {
        withHarness { h ->
            h.enableAndLoginTestUser()
            val order = h.newOrder()
            h.repo.createOrder(order, "Initial note")
            h.repo.addNote("Second note")
            val photograph = File.createTempFile("workflow-photo", ".jpg", h.context.cacheDir)
            try {
                photograph.writeBytes(byteArrayOf(-1, -40, 1, 2, 3, -1, -39))
                h.repo.addPhoto(photograph)
            } finally { photograph.delete() }
            h.repo.closeOrder(false)
            assertNotNull(h.repo.snapshot().session)
            assertNull(h.repo.snapshot().order)
            h.repo.openOrder(order)
            h.repo.addNote("Reopened note")
            h.repo.closeOrder(false)
            val orderEvents = h.events().filter { it.orderNumber == order }
            assertEquals(1, orderEvents.count { it.eventType == EventType.ORDER_CREATED.name })
            assertEquals(1, orderEvents.count { it.eventType == EventType.ORDER_OPENED.name })
            assertEquals(3, orderEvents.count { it.eventType == EventType.NOTE_ADDED.name })
            assertEquals(1, orderEvents.count { it.eventType == EventType.PHOTO_ADDED.name })
            assertEquals(2, orderEvents.count { it.eventType == EventType.ORDER_SESSION_CLOSED.name })
            val photo = orderEvents.single { it.eventType == EventType.PHOTO_ADDED.name }
            assertEquals("photos/${h.repo.deviceId}/$order-1.jpg", photo.photoFilename)
            assertTrue(File(h.context.filesDir, "developer/orders/$order/${photo.photoFilename}").isFile)
            val report = File(h.context.filesDir, "developer/orders/$order/$order.md").readText()
            orderEvents.forEach { assertTrue(report.contains(it.eventId)) }
            assertEquals(0, h.events().count { it.eventType == EventType.EMPLOYEE_LOGOUT.name })
            h.repo.logout()
            h.repo.logout()
            assertNull(h.repo.snapshot().session)
            assertEquals(1, h.events().count { it.eventType == EventType.EMPLOYEE_LOGOUT.name })
            h.restart()
            assertEquals(orderEvents.size, h.db.dao().eventsForOrder(orderKey(order, true)).size)
        }
    }

    @Test fun manualLogoutInterruptsOpenOrderAndAllowsFreshSameDayLogin() = runBlocking {
        withHarness { h ->
            h.enableAndLoginTestUser()
            val order = h.newOrder()
            h.repo.createOrder(order, "Saved before manual logout")
            val originalSession = h.repo.snapshot().session!!.id
            h.repo.logout()
            h.repo.logout()
            assertNull(h.repo.snapshot().session)
            val history = h.events()
            assertEquals(1, history.count { it.eventType == EventType.EMPLOYEE_LOGOUT.name })
            assertEquals(1, history.count { it.eventType == EventType.ORDER_SESSION_CANCELLED.name })
            assertEquals(0, history.count { it.eventType == EventType.ORDER_SESSION_CLOSED.name })
            assertEquals("Test User", history.single { it.eventType == EventType.EMPLOYEE_LOGOUT.name }.username)
            assertTrue(h.db.dao().pendingOperations().isEmpty())
            h.repo.loginBarcode(AppRepository.TEST_BARCODE, true)
            assertNotEquals(originalSession, h.repo.snapshot().session!!.id)
            h.repo.openOrder(order)
            assertTrue(h.repo.snapshot().orderEvents.any { it.details == "Saved before manual logout" })
        }
    }

    @Test fun failedActionsNeverCreateSuccessEvents() = runBlocking {
        withHarness { h ->
            h.enableAndLoginTestUser()
            val order = h.newOrder()
            h.repo.createOrder(order, "")
            val before = h.events().map { it.eventId }
            assertTrue(runCatching { h.repo.addNote(" ") }.isFailure)
            assertTrue(runCatching { h.repo.createOrder("../escape", "") }.isFailure)
            val notJpeg = File.createTempFile("not-a-photo", ".jpg", h.context.cacheDir)
            try {
                notJpeg.writeText("This is not a JPEG")
                assertTrue(runCatching { h.repo.addPhoto(notJpeg) }.isFailure)
            } finally { notJpeg.delete() }
            assertEquals(before, h.events().map { it.eventId })
            assertTrue(h.db.dao().pendingOperations().isEmpty())
            h.repo.closeOrder(false)
            val afterClose = h.events().map { it.eventId }
            assertTrue(runCatching { h.repo.closeOrder(false) }.isFailure)
            assertTrue(runCatching { h.repo.createOrder(order, "") }.isFailure)
            assertEquals(afterClose, h.events().map { it.eventId })
        }
    }

    @Test fun successfulSharedWriteRecoversAfterLocalIndexFailureWithoutRepeatingAction() = runBlocking {
        withHarness { h ->
            h.enableAndLoginTestUser()
            val session = requireNotNull(h.repo.snapshot().session)
            val order = h.newOrder()
            val event = ActivityEvent(eventType = EventType.ORDER_CREATED.name, timestampUtc = h.clock.millis(),
                timezoneId = h.clock.zone.id, username = session.username, employeeId = session.employeeId,
                deviceId = h.repo.deviceId, deviceModel = h.repo.deviceModel, orderNumber = order,
                orderSessionId = UUID.randomUUID().toString(), details = "Saved before local index interruption", isDeveloperEvent = true)
            h.db.dao().queueOperation(PendingOperationEntity(event.eventId, EventCodec.encode(event), treeUri = h.tree, employeeSessionId = session.id))
            h.repo.storage.appendEvent(h.tree, event) // Simulate crash after successful shared write, before Room commit.
            assertNull(h.db.dao().orderEvent(event.eventId))
            h.restart()
            h.repo.refreshOrders()
            h.repo.refreshOrders()
            assertTrue(h.db.dao().pendingOperations().isEmpty())
            assertEquals(1, h.events().count { it.eventId == event.eventId })
            assertEquals(1, h.db.dao().eventsForOrder(orderKey(order, true)).count { it.eventId == event.eventId })
            assertEquals(1, h.repo.storage.readEvents(h.tree, true).count { it.eventId == event.eventId })
            assertEquals(order, h.repo.snapshot().order?.orderNumber)
        }
    }

    @Test fun expiredUncommittedNoteAndCloseNeverBecomeSuccessfulActions() = runBlocking {
        withHarness { h ->
            h.enableAndLoginTestUser()
            val order = h.newOrder()
            h.repo.createOrder(order, "Previously saved work")
            val session = requireNotNull(h.repo.snapshot().session)
            val unsaved = listOf(EventType.NOTE_ADDED, EventType.ORDER_SESSION_CLOSED).map { type ->
                ActivityEvent(eventType = type.name, timestampUtc = h.clock.millis(), timezoneId = h.clock.zone.id,
                    username = session.username, employeeId = session.employeeId, deviceId = h.repo.deviceId,
                    deviceModel = h.repo.deviceModel, orderNumber = order, orderSessionId = session.activeOrderSessionId,
                    details = "Uncommitted action must not become success after expiry", isDeveloperEvent = true)
            }
            for (event in unsaved) h.db.dao().queueOperation(PendingOperationEntity(event.eventId,
                EventCodec.encode(event), treeUri = h.tree, employeeSessionId = session.id))
            h.clock.time = Instant.parse("2026-11-20T09:00:00Z")
            h.restart()
            h.repo.refreshOrders()
            assertNull(h.repo.snapshot().session)
            assertTrue(h.db.dao().pendingOperations().isEmpty())
            val deviceHistory = h.events()
            val orderHistory = h.db.dao().eventsForOrder(orderKey(order, true)).map { EventCodec.decode(it.eventJson) }
            val sharedHistory = h.repo.storage.readEvents(h.tree, true)
            unsaved.forEach { event ->
                assertFalse(deviceHistory.any { it.eventId == event.eventId })
                assertFalse(orderHistory.any { it.eventId == event.eventId })
                assertFalse(sharedHistory.any { it.eventId == event.eventId })
            }
            assertEquals(0, orderHistory.count { it.eventType == EventType.ORDER_SESSION_CLOSED.name })
            assertEquals(1, orderHistory.count { it.eventType == EventType.ORDER_SESSION_CANCELLED.name })
            assertEquals(1, deviceHistory.count { it.eventType == EventType.SESSION_EXPIRED_MIDNIGHT.name })
            assertTrue(orderHistory.any { it.details == "Previously saved work" })
        }
    }

    @Test fun finalizedCloseBeforeMidnightStillRecoversItsAuditAfterExpiry() = runBlocking {
        withHarness { h ->
            h.enableAndLoginTestUser()
            val order = h.newOrder()
            h.repo.createOrder(order, "Saved before successful close")
            val session = requireNotNull(h.repo.snapshot().session)
            h.clock.time = Instant.parse("2026-11-19T23:59:59Z")
            val completed = ActivityEvent(eventType = EventType.ORDER_SESSION_CLOSED.name,
                timestampUtc = h.clock.millis(), timezoneId = h.clock.zone.id, username = session.username,
                employeeId = session.employeeId, deviceId = h.repo.deviceId, deviceModel = h.repo.deviceModel,
                orderNumber = order, orderSessionId = session.activeOrderSessionId,
                details = "Close completed before midnight; local indexing interrupted", isDeveloperEvent = true)
            h.db.dao().queueOperation(PendingOperationEntity(completed.eventId, EventCodec.encode(completed),
                treeUri = h.tree, employeeSessionId = session.id))
            h.repo.storage.appendEvent(h.tree, completed)
            assertNull(h.db.dao().orderEvent(completed.eventId))
            h.clock.time = Instant.parse("2026-11-20T09:00:00Z")
            h.restart()
            h.repo.refreshOrders()
            assertNull(h.repo.snapshot().session)
            assertTrue(h.db.dao().pendingOperations().isEmpty())
            val history = h.events()
            assertEquals(1, history.count { it.eventId == completed.eventId })
            assertEquals(1, history.count { it.eventType == EventType.ORDER_SESSION_CLOSED.name })
            assertEquals(0, history.count { it.eventType == EventType.ORDER_SESSION_CANCELLED.name })
            assertEquals(1, history.count { it.eventType == EventType.SESSION_EXPIRED_MIDNIGHT.name })
            assertEquals(1, h.repo.storage.readEvents(h.tree, true).count { it.eventId == completed.eventId })
        }
    }

    @Test fun timezoneDeadlineShorteningPersistsAcrossRestart() = runBlocking {
        withHarness { h ->
            h.enableAndLoginTestUser()
            h.clock.timeZone = ZoneId.of("Europe/Helsinki")
            h.clock.time = Instant.parse("2026-11-19T10:00:00Z")
            val session = requireNotNull(h.repo.snapshot().session)
            assertEquals(Instant.parse("2026-11-19T22:00:00Z").toEpochMilli(), session.expiresAtUtc)
            assertEquals("Europe/Helsinki", session.expiresTimezoneId)
            h.clock.timeZone = ZoneId.of("Europe/London")
            h.clock.time = Instant.parse("2026-11-19T22:15:00Z")
            h.restart()
            assertNull(h.repo.snapshot().session)
            val expiration = h.events().single { it.eventType == EventType.SESSION_EXPIRED_MIDNIGHT.name }
            assertEquals(session.expiresAtUtc, expiration.timestampUtc)
            assertEquals("Europe/Helsinki", expiration.timezoneId)
        }
    }

    @Test fun developerEventsAndExportsStaySeparateAndRequireAdmin() = runBlocking {
        withHarness { h ->
            h.enableAndLoginTestUser()
            val order = h.newOrder()
            h.repo.createOrder(order, "Private test note")
            val testCreation = h.events().single { it.eventType == EventType.ORDER_CREATED.name }
            assertFalse(h.repo.activity.page(ActivityFilter(), 0).any { it.eventId == testCreation.eventId })
            assertTrue(h.repo.activity.page(ActivityFilter(developer = true), 0).any { it.eventId == testCreation.eventId })
            assertTrue(h.repo.storage.readEvents(h.tree, false).isEmpty())
            val destinationRoot = requireNotNull(DocumentFile.fromTreeUri(h.context, android.net.Uri.parse(h.tree)))
            val destination = requireNotNull(destinationRoot.createFile("text/markdown", "test-activity.md"))
            h.repo.logoutAdmin()
            assertTrue(runCatching { h.repo.activity.page(ActivityFilter(developer = true), 0) }.isFailure)
            assertTrue(runCatching { h.repo.activity.export(destination.uri, h.repo.deviceId, h.repo.deviceModel, true) }.isFailure)
            assertTrue(runCatching { h.repo.setDeveloperMode(false) }.isFailure)
            h.repo.authenticateAdmin(PIN)
            h.repo.activity.export(destination.uri, h.repo.deviceId, h.repo.deviceModel, true)
            val exported = h.context.contentResolver.openInputStream(destination.uri)!!.bufferedReader().use { it.readText() }
            assertTrue(exported.contains("TEST ACTIVITY"))
            assertTrue(exported.contains(testCreation.eventId))
            assertFalse(exported.contains("ADMIN_LOGIN"))
            assertTrue(File(h.context.filesDir, "admin/activity/device-activity.md").isFile)
            assertTrue(File(h.context.filesDir, "developer/activity/device-activity.md").isFile)
        }
    }

    @Test fun adminControlledMidnightSimulationPreservesWorkAndDoesNotChangeRealClock() = runBlocking {
        withHarness { h ->
            assertTrue(runCatching { h.repo.simulateMidnight() }.isFailure)
            h.enableAndLoginTestUser()
            val order = h.newOrder()
            h.repo.createOrder(order, "Keep this note")
            val before = h.clock.instant()
            h.repo.simulateMidnight()
            assertEquals(before, h.clock.instant())
            assertNull(h.repo.snapshot().session)
            assertEquals(1, h.events().count { it.eventType == EventType.SESSION_EXPIRED_MIDNIGHT.name })
            assertEquals(1, h.events().count { it.eventType == EventType.ORDER_SESSION_CANCELLED.name })
            assertTrue(h.events(false).none { it.eventType == EventType.SESSION_EXPIRED_MIDNIGHT.name })
            assertTrue(h.db.dao().eventsForOrder(orderKey(order, true)).map { EventCodec.decode(it.eventJson) }
                .any { it.details == "Keep this note" })
        }
    }

    @Test fun sharedFolderEventsPopulateOrderCacheWithoutPollutingPrivateDeviceAudit() = runBlocking {
        withHarness { h ->
            h.repo.authenticateAdmin(PIN)
            h.repo.saveEmployee(null, "Alice", "ALICE-PRODUCTION", true)
            val order = h.newOrder()
            val remote = ActivityEvent(eventType = EventType.ORDER_CREATED.name,
                timestampUtc = Instant.parse("2026-11-18T12:00:00Z").toEpochMilli(), timezoneId = "Europe/London",
                username = "Bob", employeeId = UUID.randomUUID().toString(), deviceId = "remote-phone",
                deviceModel = "Google Pixel 4a", orderNumber = order, orderSessionId = UUID.randomUUID().toString(),
                details = "Created on another phone")
            h.repo.storage.appendEvent(h.tree, remote)
            h.repo.loginBarcode("ALICE-PRODUCTION")
            h.repo.refreshOrders()
            assertTrue(h.repo.snapshot().orders.any { it.orderNumber == order })
            assertNotNull(h.db.dao().orderEvent(remote.eventId))
            assertFalse(h.events(false).any { it.eventId == remote.eventId })
            h.repo.openOrder(order)
            assertTrue(h.repo.snapshot().orderEvents.any { it.eventId == remote.eventId })
            val localOpen = h.events(false).single { it.eventType == EventType.ORDER_OPENED.name }
            assertEquals("Alice", localOpen.username)
            assertEquals(h.repo.deviceId, localOpen.deviceId)
        }
    }

    companion object {
        private const val PIN = "482615"
        private val PIN_VERIFIER = PinHasher.create(PIN)
    }
}
