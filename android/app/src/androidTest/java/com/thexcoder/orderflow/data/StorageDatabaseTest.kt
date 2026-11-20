package com.thexcoder.orderflow.data

import android.content.Context
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.thexcoder.orderflow.core.ActivityEvent
import com.thexcoder.orderflow.core.EventCodec
import com.thexcoder.orderflow.core.EventType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class StorageDatabaseTest {
    @Before fun adoptDocumentsPermission() {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .adoptShellPermissionIdentity(android.Manifest.permission.MANAGE_DOCUMENTS)
    }

    @After fun dropDocumentsPermission() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.dropShellPermissionIdentity()
    }

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun event(type: EventType = EventType.ORDER_CREATED, order: String = "ord123",
        developer: Boolean = false, device: String = "phone-a", timestamp: Long = 1000L) =
        ActivityEvent(eventType = type.name, timestampUtc = timestamp, timezoneId = "Europe/London",
            username = "Alice", employeeId = UUID.randomUUID().toString(), deviceId = device,
            deviceModel = "Google Pixel 4a", orderNumber = order, orderSessionId = UUID.randomUUID().toString(),
            details = "Saved detail", isDeveloperEvent = developer)

    private fun productionTree(): String {
        val uri = DocumentsContract.buildTreeDocumentUri(TestDocumentsProvider.AUTHORITY, "root")
        TestStorageGrants.grant(context, uri.toString())
        val root = requireNotNull(DocumentFile.fromTreeUri(context, uri))
        val test = requireNotNull(root.createDirectory("case-${UUID.randomUUID()}"))
        val selectedTree = DocumentsContract.buildTreeDocumentUri(TestDocumentsProvider.AUTHORITY,
            DocumentsContract.getDocumentId(test.uri)).toString()
        TestStorageGrants.grant(context, selectedTree)
        return selectedTree
    }

    @Test fun roomKeepsHistoryAfterReopenAndDeduplicatesUuid() = runBlocking {
        val name = "room-test-${UUID.randomUUID()}.db"
        var database = Room.databaseBuilder(context, OrderDatabase::class.java, name).build()
        try {
            val record = DeviceActivityEventEntity.from(event())
            assertTrue(database.dao().insertDeviceActivity(record) != -1L)
            assertEquals(-1L, database.dao().insertDeviceActivity(record))
            database.close()
            database = Room.databaseBuilder(context, OrderDatabase::class.java, name).build()
            assertEquals(listOf(record), database.dao().allActivity(false))
        } finally { database.close(); context.deleteDatabase(name) }
    }

    @Test fun durableOutboxRetainsOriginalDestinationAcrossRestart() = runBlocking {
        val name = "outbox-test-${UUID.randomUUID()}.db"
        var db = Room.databaseBuilder(context, OrderDatabase::class.java, name).build()
        try {
            val e = event(type = EventType.PHOTO_ADDED)
            val daily = com.thexcoder.orderflow.core.SessionPolicy.start(requireNotNull(e.employeeId), "Alice", false,
                java.time.Clock.fixed(java.time.Instant.parse("2026-11-19T10:00:00Z"), java.time.ZoneId.of("Europe/London")))
            db.dao().saveSession(SessionEntity.from(daily))
            val pending = PendingOperationEntity(e.eventId, EventCodec.encode(e),
                "/private/pending-photo.jpg", "ord123-1.jpg", "content://original/tree", daily.sessionId)
            db.dao().queueOperation(pending)
            assertTrue(runCatching { db.dao().queueOperation(pending) }.isFailure)
            db.close()
            db = Room.databaseBuilder(context, OrderDatabase::class.java, name).build()
            assertEquals(listOf(pending), db.dao().pendingOperations())
            assertEquals(daily, db.dao().sessionById(daily.sessionId)?.toDomain())
            db.withTransaction {
                db.dao().insertOrderEvent(OrderEventEntity(e.eventId, orderKey("ord123", false),
                    pending.eventJson, e.timestampUtc))
                db.dao().insertDeviceActivity(DeviceActivityEventEntity.from(e))
                db.dao().deletePending(e.eventId)
            }
            assertTrue(db.dao().pendingOperations().isEmpty())
            assertEquals(e.eventId, db.dao().orderEvent(e.eventId)?.eventId)
            assertEquals(e.eventId, db.dao().allActivity(false).single().eventId)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun failedDatabaseCommitNeverLeavesPartialDeviceSuccess() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, OrderDatabase::class.java).build()
        try {
            val e = event()
            val pending = PendingOperationEntity(e.eventId, EventCodec.encode(e))
            db.dao().queueOperation(pending)
            assertTrue(runCatching {
                db.withTransaction {
                    db.dao().insertOrderEvent(OrderEventEntity(e.eventId, orderKey("ord123", false),
                        pending.eventJson, e.timestampUtc))
                    db.dao().insertDeviceActivity(DeviceActivityEventEntity.from(e))
                    db.dao().deletePending(e.eventId)
                    error("Simulated local commit failure")
                }
            }.isFailure)
            assertNull(db.dao().orderEvent(e.eventId))
            assertTrue(db.dao().allActivity(false).isEmpty())
            assertEquals(listOf(pending), db.dao().pendingOperations())
        } finally { db.close() }
    }

    @Test fun roomFiltersAndSortsWithDeveloperIsolation() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, OrderDatabase::class.java).build()
        try {
            val earlier = event(timestamp = 1000)
            val later = event(type = EventType.NOTE_ADDED, timestamp = 2000)
            val developer = event(developer = true, timestamp = 3000)
            for (e in listOf(later, developer, earlier)) db.dao().insertDeviceActivity(DeviceActivityEventEntity.from(e))
            assertEquals(listOf(later.eventId, earlier.eventId), db.dao().activityPage(false, "Ali", "ord",
                "", "saved", "", "", false, 50, 0).map { it.eventId })
            assertEquals(listOf(earlier.eventId, later.eventId), db.dao().activityPage(false, "", "",
                "", "", "", "", true, 50, 0).map { it.eventId })
            assertEquals(1, db.dao().allActivity(true).size)
            assertEquals(1, db.dao().activityPage(false, "", "", EventType.NOTE_ADDED.name,
                "", "", "", true, 50, 0).size)
            assertTrue(db.dao().activityPage(false, "Bob", "", "", "", "",
                "", true, 50, 0).isEmpty())
        } finally { db.close() }
    }

    @Test fun changingProductionRootClearsOnlyItsIndex() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, OrderDatabase::class.java).build()
        try {
            val production = event()
            val developer = event(developer = true)
            for (e in listOf(production, developer)) {
                val key = orderKey(requireNotNull(e.orderNumber), e.isDeveloperEvent)
                db.dao().saveOrder(OrderEntity(key, e.orderNumber, e.isDeveloperEvent, e.timestampUtc))
                db.dao().insertOrderEvent(OrderEventEntity(e.eventId, key, EventCodec.encode(e), e.timestampUtc))
                db.dao().insertDeviceActivity(DeviceActivityEventEntity.from(e))
            }
            db.withTransaction {
                db.dao().deleteProductionOrderEvents()
                db.dao().deleteProductionOrders()
            }
            assertTrue(db.dao().allOrders(false).isEmpty())
            assertNull(db.dao().orderEvent(production.eventId))
            assertEquals(developer.eventId, db.dao().orderEvent(developer.eventId)?.eventId)
            assertEquals(1, db.dao().allOrders(true).size)
            assertEquals(1, db.dao().allActivity(false).size)
            assertEquals(1, db.dao().allActivity(true).size)
        } finally { db.close() }
    }

    @Test fun dateFiltersUseRecordedTimezoneRatherThanCurrentDeviceZone() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, OrderDatabase::class.java).build()
        try {
            val utc = java.time.Instant.parse("2026-11-20T00:30:00Z").toEpochMilli()
            val london = event(timestamp = utc)
            val losAngeles = event(timestamp = utc).copy(timezoneId = "America/Los_Angeles")
            db.dao().insertDeviceActivity(DeviceActivityEventEntity.from(london))
            db.dao().insertDeviceActivity(DeviceActivityEventEntity.from(losAngeles))
            assertEquals(listOf(losAngeles.eventId), db.dao().activityPage(false, "", "", "", "",
                "2026-11-19", "2026-11-19", false, 50, 0).map { it.eventId })
            assertEquals(listOf(london.eventId), db.dao().activityPage(false, "", "", "", "",
                "2026-11-20", "2026-11-20", false, 50, 0).map { it.eventId })
        } finally { db.close() }
    }

    @Test fun expiredBoundaryGuardPreventsFinalSuccessButDoesNotRejectCompletedReplay() = runBlocking {
        val tree = productionTree()
        val storage = OrderStorage(context)
        val e = event()
        assertTrue(runCatching {
            storage.appendEvent(tree, e) { error("Employee session expired during storage write") }
        }.isFailure)
        assertTrue(storage.readEvents(tree, false).isEmpty())
        storage.appendEvent(tree, e)
        // Crash recovery must accept the already completed immutable event without applying it twice.
        storage.appendEvent(tree, e) { error("Should not be called for an event already saved") }
        assertEquals(listOf(e), storage.readEvents(tree, false))
    }

    @Test fun productionSafEventsAreImmutableAndImportOtherDevices() = runBlocking {
        val tree = productionTree()
        val storage = OrderStorage(context)
        storage.validateTree(tree)
        val first = event(timestamp = 1000)
        val second = event(type = EventType.ORDER_OPENED, device = "phone-b", timestamp = 2000)
        storage.appendEvent(tree, second)
        storage.appendEvent(tree, first)
        storage.appendEvent(tree, first)
        assertEquals(listOf(first, second), storage.readEvents(tree, false))
        val freshStorage = OrderStorage(context)
        assertEquals(listOf(first, second), freshStorage.readEvents(tree, false))
        val conflict = first.copy(details = "Modified after save")
        assertTrue(runCatching { storage.appendEvent(tree, conflict) }.isFailure)
        assertEquals(first, storage.readEvents(tree, false).first())
    }

    @Test fun developerFilesNeverEnterProductionTree() = runBlocking {
        val tree = productionTree()
        val storage = OrderStorage(context)
        val order = "dev-${UUID.randomUUID()}"
        val test = event(order = order, developer = true)
        try {
            storage.appendEvent(tree, test)
            assertTrue(storage.readEvents(tree, false).isEmpty())
            assertTrue(storage.readEvents(null, true).contains(test))
            assertTrue(File(context.filesDir, "developer/orders/$order/events/phone-a/${test.eventId}.json").isFile)
        } finally {
            File(context.filesDir, "developer/orders/$order").deleteRecursively()
        }
    }

    @Test fun photographsNeverOverwriteAndNumberingIsPerDevice() = runBlocking {
        val tree = productionTree()
        val storage = OrderStorage(context)
        val photo = File.createTempFile("photo-test", ".jpg", context.cacheDir)
        try {
            photo.writeBytes(byteArrayOf(-1, -40, 1, 2, 3, -1, -39))
            val e = event(type = EventType.PHOTO_ADDED)
            val filename = storage.nextPhotoName(tree, "ord123", "phone-a", false)
            assertEquals("ord123-1.jpg", filename)
            val relative = storage.savePhoto(tree, e, photo, filename)
            assertEquals("photos/phone-a/ord123-1.jpg", relative)
            assertEquals(relative, storage.savePhoto(tree, e, photo, filename))
            assertEquals("ord123-2.jpg", storage.nextPhotoName(tree, "ord123", "phone-a", false))
            assertEquals("ord123-1.jpg", storage.nextPhotoName(tree, "ord123", "phone-b", false))
            photo.writeBytes(byteArrayOf(9, 8, 7))
            assertTrue(runCatching { storage.savePhoto(tree, e, photo, filename) }.isFailure)
        } finally { photo.delete() }
    }

    @Test fun photoBoundaryGuardPreventsLateSaveButKeepsCompletedPhotoRecoverable() = runBlocking {
        val tree = productionTree()
        val storage = OrderStorage(context)
        val photo = File.createTempFile("photo-boundary", ".jpg", context.cacheDir)
        try {
            photo.writeBytes(byteArrayOf(-1, -40, 1, 2, 3, -1, -39))
            val e = event(type = EventType.PHOTO_ADDED)
            val name = "ord123-1.jpg"
            assertTrue(runCatching {
                storage.savePhoto(tree, e, photo, name) { error("Employee session expired during capture") }
            }.isFailure)
            assertEquals(name, storage.nextPhotoName(tree, "ord123", "phone-a", false))
            storage.savePhoto(tree, e, photo, name)
            assertEquals("photos/phone-a/$name", storage.savePhoto(tree, e, photo, name) {
                error("Completed photo recovery must bypass the expired session guard")
            })
        } finally { photo.delete() }
    }

    @Test fun invalidOrPartialImportedJsonDoesNotDiscardValidHistory() = runBlocking {
        val tree = productionTree()
        val storage = OrderStorage(context)
        val e = event()
        storage.appendEvent(tree, e)
        val root = requireNotNull(DocumentFile.fromTreeUri(context, android.net.Uri.parse(tree)))
        val events = requireNotNull(root.findFile("ord123")?.findFile("events")?.findFile("phone-a"))
        val broken = requireNotNull(events.createFile("application/json", "${UUID.randomUUID()}.json"))
        context.contentResolver.openOutputStream(broken.uri)!!.use { it.write("{broken".toByteArray()) }
        assertEquals(listOf(e), storage.readEvents(tree, false))
        assertFalse(storage.diagnostics().isEmpty())
    }

    @Test fun sharedFoldersCannotImportAuthenticationOrCrossNamespaceEvents() = runBlocking {
        val tree = productionTree()
        val storage = OrderStorage(context)
        val original = event()
        storage.appendEvent(tree, original)
        assertTrue(runCatching { storage.appendEvent(tree, event(type = EventType.EMPLOYEE_LOGIN)) }.isFailure)
        val root = requireNotNull(DocumentFile.fromTreeUri(context, android.net.Uri.parse(tree)))
        val directory = requireNotNull(root.findFile("ord123")?.findFile("events")?.findFile("phone-a"))
        for (spoofed in listOf(event(type = EventType.ADMIN_LOGIN), event(developer = true),
            event(order = "different-order"), event(device = "phone-b"))) {
            val document = requireNotNull(directory.createFile("application/json", "${spoofed.eventId}.json"))
            context.contentResolver.openOutputStream(document.uri)!!.use {
                it.write(EventCodec.encode(spoofed).toByteArray())
            }
        }
        assertEquals(listOf(original), storage.readEvents(tree, false))
        assertTrue(storage.diagnostics().size >= 4)
    }

    @Test fun orderMarkdownCanBeRegeneratedFromSavedJson() = runBlocking {
        val tree = productionTree()
        val storage = OrderStorage(context)
        val e = event()
        storage.appendEvent(tree, e)
        val root = requireNotNull(DocumentFile.fromTreeUri(context, android.net.Uri.parse(tree)))
        storage.writeOrderMarkdown(tree, "ord123", false, "phone-a", "# First report")
        val folder = requireNotNull(root.findFile("ord123"))
        requireNotNull(folder.findFile("ord123.md")).delete()
        val all = storage.readEvents(tree, false)
        val report = com.thexcoder.orderflow.core.OrderMarkdownExporter.render("ord123", all)
        storage.writeOrderMarkdown(tree, "ord123", false, "phone-a", report)
        val regenerated = requireNotNull(folder.findFile("ord123.md"))
        val actual = context.contentResolver.openInputStream(regenerated.uri)!!.bufferedReader().use { it.readText() }
        assertEquals(report, actual)
        assertTrue(actual.contains(e.eventId))
        assertEquals(e, EventCodec.decode(EventCodec.encode(all.single())))
    }
}
