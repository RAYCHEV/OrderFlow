package com.thexcoder.orderflow.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.room.withTransaction
import com.thexcoder.orderflow.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** All mutations enter here: screens never write events, sessions, employees, or files themselves. */
class AppRepository(
    private val context: Context,
    val database: OrderDatabase = OrderDatabase.open(context),
    private val settingsStore: SettingsStore = SettingsStore(context),
    val storage: OrderStorage = OrderStorage(context),
    private val clockProvider: () -> Clock = { Clock.systemDefaultZone() }
) {
    private val dao = database.dao()
    val adminAccess = AdminAccess()
    val activity = DeviceActivityRepository(context, dao, adminAccess)
    private val mutex = Mutex()
    private lateinit var settings: Settings
    private val problems = mutableListOf<String>()
    val deviceModel: String = listOf(Build.MANUFACTURER, Build.MODEL).distinct().joinToString(" ")
    val deviceId: String get() = if (::settings.isInitialized) settings.deviceId else ""

    data class Snapshot(val settings: Settings, val session: SessionEntity?, val employees: List<EmployeeEntity>,
        val orders: List<OrderEntity>, val order: OrderEntity?, val orderEvents: List<ActivityEvent>,
        val diagnostics: List<String>)

    suspend fun initialize() = mutex.withLock {
        settings = settingsStore.read()
        recoverSettingsEventsLocked()
        retryPendingLocked()
        validateSessionLocked()
        regeneratePrivateLocked(false)
        regeneratePrivateLocked(true)
    }

    suspend fun snapshot(): Snapshot = mutex.withLock {
        ensureInitialized()
        val session = validateSessionLocked()
        val activeOrder = session?.activeOrderKey?.let { dao.order(it) }
        Snapshot(settings, session,
            if (adminAccess.authenticated) dao.employees() else emptyList(),
            if (session != null) dao.allOrders(session.isDeveloper) else emptyList(),
            activeOrder, activeOrder?.let { eventsLocked(it.key) } ?: emptyList(),
            if (adminAccess.authenticated) problems.toList() + storage.diagnostics() +
                listOf("Pending operations: ${dao.pendingOperations().size}",
                    "Production device events: ${dao.activityCount(false)}", "Test device events: ${dao.activityCount(true)}",
                    "Private database: ${context.getDatabasePath("orderflow.db").name}",
                    "Storage: ${settings.storageUri ?: "Not configured"}") else emptyList())
    }

    suspend fun authenticateAdmin(pin: String) = mutex.withLock {
        ensureInitialized()
        val now = clockProvider().millis()
        require(now >= settings.lockedUntil) { "Too many PIN attempts. Try again in ${((settings.lockedUntil - now) / 1000) + 1} seconds." }
        val existing = settings.pinHash
        if (existing == null) {
            val verifier = withContext(Dispatchers.Default) { PinHasher.create(pin) }
            settingsStore.setPin(verifier)
        } else if (!withContext(Dispatchers.Default) { PinHasher.verify(pin, existing) }) {
            val failures = settings.failedPins + 1
            settingsStore.setFailures(failures, if (failures % 5L == 0L) now + 60_000L else 0L)
            settings = settingsStore.read()
            error("Incorrect administrator PIN")
        }
        settingsStore.setFailures(0, 0)
        settings = settingsStore.read()
        // Authentication is successful only after the corresponding durable audit record exists.
        activity.record(event(EventType.ADMIN_LOGIN, details = if (existing == null) "Initial administrator PIN configured" else "Administrator PIN accepted"))
        adminAccess.unlock()
        regeneratePrivateLocked(false)
    }

    fun revokeAdminAccess(): Boolean = adminAccess.revoke()

    suspend fun logoutAdmin(alreadyRevoked: Boolean = false) = mutex.withLock {
        if (alreadyRevoked || adminAccess.revoke()) {
            // Closing the capability is immediate, even if a disk error prevents the audit write.
            try { activity.record(event(EventType.ADMIN_LOGOUT)); regeneratePrivateLocked(false) }
            catch (error: Exception) { diagnostic("Administrator logout audit", error) }
        }
    }

    suspend fun saveEmployee(id: String?, username: String, barcode: String, active: Boolean) = mutex.withLock {
        adminAccess.requireAdmin()
        val name = username.trim()
        val code = barcode.trim()
        require(name.isNotEmpty() && name.length <= 100 && name.none { it.isISOControl() }) { "Username must contain 1–100 characters" }
        require(code.isNotEmpty() && code.length <= 256 && code.none { it.isISOControl() }) { "Barcode must contain 1–256 printable characters" }
        require(code != TEST_BARCODE) { "The Test User barcode is reserved for Developer Mode" }
        val old = id?.let { dao.employeeById(it) ?: error("Employee not found") }
        require(old?.isDeveloper != true) { "Test User is managed by Developer Mode" }
        val duplicate = dao.employeeByBarcode(code)
        require(duplicate == null || duplicate.id == old?.id) { "This barcode is already registered" }
        val employee = EmployeeEntity(id = old?.id ?: UUID.randomUUID().toString(), username = name, barcode = code, active = active)
        val type = when { old == null -> EventType.EMPLOYEE_REGISTERED; old.active && !active -> EventType.EMPLOYEE_DEACTIVATED; else -> EventType.EMPLOYEE_UPDATED }
        database.withTransaction {
            dao.saveEmployee(employee)
            activity.record(event(type, details = "Employee: $name; ID: ${employee.id}; active: $active").copy(employeeId = employee.id))
        }
        validateSessionLocked()
        regeneratePrivateLocked(false)
    }

    suspend fun setDeveloperMode(enabled: Boolean) = mutex.withLock {
        adminAccess.requireAdmin()
        val audit = event(EventType.DEVELOPER_MODE_CHANGED, details = enabled.toString())
        // Settings changes have a small durable intent as Room and DataStore cannot share a transaction.
        dao.queueOperation(PendingOperationEntity(audit.eventId, EventCodec.encode(audit)))
        try { settingsStore.setDeveloper(enabled) }
        catch (error: Exception) { dao.deletePending(audit.eventId); throw error }
        settings = settingsStore.read()
        database.withTransaction {
            val existing = dao.employeeByBarcode(TEST_BARCODE)
            dao.saveEmployee(EmployeeEntity(existing?.id ?: TEST_EMPLOYEE_ID, "Test User", TEST_BARCODE, enabled, true))
            activity.record(audit)
            dao.deletePending(audit.eventId)
        }
        validateSessionLocked()
        regeneratePrivateLocked(false)
    }

    suspend fun setStorage(uri: Uri) = mutex.withLock {
        adminAccess.requireAdmin()
        require(uri.scheme == "content") { "Choose a folder with the Android storage picker" }
        val changed = settings.storageUri != uri.toString()
        if (changed) {
            require(dao.activeSession()?.activeOrderKey == null) { "Close the current order before changing storage" }
            require(dao.pendingOperations().none { !EventCodec.decode(it.eventJson).isDeveloperEvent }) { "Retry pending operations before changing storage" }
        }
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        storage.validateTree(uri.toString())
        val audit = event(EventType.STORAGE_CONFIGURED, details = uri.toString())
        dao.queueOperation(PendingOperationEntity(audit.eventId, EventCodec.encode(audit)))
        try { settingsStore.setStorage(uri.toString()) }
        catch (error: Exception) { dao.deletePending(audit.eventId); throw error }
        settings = settingsStore.read()
        database.withTransaction {
            if (changed) { dao.deleteProductionOrderEvents(); dao.deleteProductionOrders() }
            activity.record(audit); dao.deletePending(audit.eventId)
        }
        regeneratePrivateLocked(false)
    }

    suspend fun loginBarcode(raw: String, simulated: Boolean = false): String = mutex.withLock {
        ensureInitialized()
        require(validateSessionLocked() == null) { "Log out before scanning another employee" }
        val code = raw.trim()
        require(code.isNotBlank() && code.length <= 256) { "Invalid employee barcode" }
        if (simulated) require(settings.developerMode && code == TEST_BARCODE) { "Developer Mode must be enabled by an administrator" }
        val employee = dao.employeeByBarcode(code) ?: error("Unregistered employee barcode. Ask an administrator to register it.")
        require(employee.active) { "This employee is inactive" }
        require(!employee.isDeveloper || settings.developerMode) { "Developer Mode is disabled" }
        val session = SessionPolicy.start(employee.id, employee.username, employee.isDeveloper, clockProvider())
        database.withTransaction {
            dao.saveSession(SessionEntity.from(session))
            activity.record(event(EventType.EMPLOYEE_LOGIN, session, details = "Employee session ${session.sessionId}; valid until ${Instant.ofEpochMilli(session.expiresAtUtc)}"))
        }
        regeneratePrivateLocked(session.isDeveloper)
        employee.username
    }

    suspend fun logout() = mutex.withLock {
        val session = validateSessionLocked() ?: return@withLock
        endSessionLocked(session, false, clockProvider().millis(), clockProvider().zone.id, "Manual employee logout")
        retryPendingLocked()
    }

    /** Also called on launch/resume/time-change and by every protected employee operation. */
    suspend fun validateSession() = mutex.withLock { ensureInitialized(); validateSessionLocked() }

    private suspend fun validateSessionLocked(): SessionEntity? {
        val session = dao.activeSession() ?: return null
        val clock = clockProvider()
        val expiration = SessionPolicy.evaluate(session.toDomain(), clock)
        if (expiration.expired) {
            endSessionLocked(session, true, expiration.effectiveAtUtc, expiration.timezoneId, "Daily login expired; detected ${clock.instant()} (${clock.zone.id})")
            return null
        }
        val employee = dao.employeeById(session.employeeId)
        if (employee == null || !employee.active || (session.isDeveloper && !settings.developerMode)) {
            endSessionLocked(session, false, clock.millis(), clock.zone.id, "Employee deactivated or Developer Mode disabled")
            return null
        }
        val updated = session.copy(expiresAtUtc = minOf(session.expiresAtUtc, expiration.effectiveAtUtc),
            expiresTimezoneId = expiration.timezoneId, lastSeenUtc = clock.millis())
        dao.saveSession(updated)
        return updated
    }

    private suspend fun endSessionLocked(session: SessionEntity, expired: Boolean, effectiveAt: Long, zone: String, reason: String) {
        val detected = clockProvider().millis()
        val audit = event(if (expired) EventType.SESSION_EXPIRED_MIDNIGHT else EventType.EMPLOYEE_LOGOUT,
            session.toDomain(), details = reason).copy(
            eventId = stableId("${session.id}:${if (expired) "expired" else "logout"}"),
            timestampUtc = effectiveAt, timezoneId = zone,
            detectedAtUtc = if (detected != effectiveAt) detected else null)
        val order = session.activeOrderKey?.let { dao.order(it) }
        val cancelled = order?.let {
            event(EventType.ORDER_SESSION_CANCELLED, session.toDomain(), it.orderNumber,
                session.activeOrderSessionId, if (expired) "Interrupted: employee login expired at midnight. Saved work preserved." else "Interrupted: $reason")
                .copy(eventId = stableId("${session.activeOrderSessionId}:cancelled"), timestampUtc = effectiveAt,
                    timezoneId = zone, detectedAtUtc = if (detected != effectiveAt) detected else null)
        }
        database.withTransaction {
            dao.saveSession(session.copy(endedAtUtc = effectiveAt, endReason = reason, activeOrderKey = null, activeOrderSessionId = null))
            activity.record(audit)
            if (cancelled != null) {
                // Ending the private working session succeeded; queue only its shared-file projection.
                indexEventLocked(cancelled, true)
                dao.queueOperation(PendingOperationEntity(cancelled.eventId, EventCodec.encode(cancelled), treeUri = settings.storageUri))
            }
        }
        regeneratePrivateLocked(session.isDeveloper)
    }

    private suspend fun requireEmployeeLocked(): SessionEntity {
        val session = validateSessionLocked() ?: error("Scan your employee barcode. Daily sessions expire at local midnight.")
        retryPendingLocked()
        require(dao.pendingOperations().none { EventCodec.decode(it.eventJson).isDeveloperEvent == session.isDeveloper }) {
            "An earlier action is pending. Restore the selected folder and refresh before making more changes."
        }
        return validateSessionLocked() ?: error("Session expired while retrying saved work. Scan your barcode again.")
    }

    suspend fun createOrder(number: String, initialNote: String) = mutex.withLock {
        val session = requireEmployeeLocked()
        require(session.activeOrderKey == null) { "Close the current order first" }
        val orderNumber = validateOrderNumber(number)
        require(initialNote.length <= MAX_NOTE) { "Notes are limited to $MAX_NOTE characters" }
        importLocked(session.isDeveloper)
        require(dao.order(orderKey(orderNumber, session.isDeveloper)) == null) { "This order already exists. Open it from the list." }
        val id = UUID.randomUUID().toString()
        performLocked(event(EventType.ORDER_CREATED, session.toDomain(), orderNumber, id, "Order created"))
        if (initialNote.isNotBlank()) {
            // The order already exists if this second operation fails; never repeat its creation.
            val current = requireEmployeeLocked()
            performLocked(event(EventType.NOTE_ADDED, current.toDomain(), orderNumber, id, initialNote.trim()))
        }
    }

    suspend fun openOrder(number: String) = mutex.withLock {
        val session = requireEmployeeLocked()
        require(session.activeOrderKey == null) { "Close the current order first" }
        val orderNumber = validateOrderNumber(number)
        importLocked(session.isDeveloper)
        require(dao.order(orderKey(orderNumber, session.isDeveloper)) != null) { "Order not found in the selected folder" }
        performLocked(event(EventType.ORDER_OPENED, session.toDomain(), orderNumber, UUID.randomUUID().toString(), "Order working session opened"))
    }

    suspend fun addNote(note: String) = mutex.withLock {
        val (session, order) = workingOrderLocked()
        require(note.isNotBlank() && note.length <= MAX_NOTE) { "Enter a note of 1–$MAX_NOTE characters" }
        performLocked(event(EventType.NOTE_ADDED, session.toDomain(), order.orderNumber, session.activeOrderSessionId, note.trim()))
    }

    suspend fun addPhoto(source: File) = mutex.withLock {
        val (session, order) = workingOrderLocked()
        require(source.isFile && source.length() in 4..MAX_PHOTO_BYTES) { "Captured photo is missing or too large" }
        source.inputStream().use {
            require(it.read() == 0xff && it.read() == 0xd8) { "Captured image must be JPEG" }
        }
        val name = storage.nextPhotoName(settings.storageUri, order.orderNumber, deviceId, session.isDeveloper)
        val audit = event(EventType.PHOTO_ADDED, session.toDomain(), order.orderNumber, session.activeOrderSessionId,
            "Photo: $name", "photos/$deviceId/$name")
        val directory = File(context.filesDir, "pending-photos")
        check(directory.isDirectory || directory.mkdirs()) { "Cannot preserve pending photo" }
        val retained = File(directory, "${audit.eventId}.jpg")
        withContext(Dispatchers.IO) { source.inputStream().use { input -> java.io.FileOutputStream(retained).use { output -> input.copyTo(output); output.fd.sync() } } }
        // Keep captured JPEG in private durable storage until the shared event and Room commit both succeed.
        try { performLocked(audit, retained, name) }
        finally {
            if (dao.pendingOperations().none { it.eventId == audit.eventId }) withContext(Dispatchers.IO) { retained.delete() }
            withContext(Dispatchers.IO) { source.delete() }
        }
    }

    suspend fun closeOrder(cancel: Boolean) = mutex.withLock {
        val (session, order) = workingOrderLocked()
        performLocked(event(if (cancel) EventType.ORDER_SESSION_CANCELLED else EventType.ORDER_SESSION_CLOSED,
            session.toDomain(), order.orderNumber, session.activeOrderSessionId,
            if (cancel) "Working session cancelled; previously saved work preserved" else "Order working session closed"))
    }

    private suspend fun workingOrderLocked(): Pair<SessionEntity, OrderEntity> {
        val session = requireEmployeeLocked()
        val order = session.activeOrderKey?.let { dao.order(it) } ?: error("Open an order before making changes")
        require(session.activeOrderSessionId != null) { "No active order working session" }
        return session to order
    }

    private suspend fun performLocked(audit: ActivityEvent, photo: File? = null, photoName: String? = null) {
        val current = validateSessionLocked() ?: error("Session expired before saving. Previously saved work is preserved; scan your barcode again.")
        require(current.employeeId == audit.employeeId && current.isDeveloper == audit.isDeveloperEvent) { "Employee session changed" }
        val operation = PendingOperationEntity(audit.eventId, EventCodec.encode(audit), photo?.absolutePath, photoName, settings.storageUri, employeeSessionId = current.id)
        require(audit.isDeveloperEvent || settings.storageUri != null) { "An administrator must configure the orders folder first" }
        dao.queueOperation(operation)
        try { completeOperationLocked(operation) }
        catch (expired: ExpiredActionException) {
            abandonExpiredOperationLocked(operation)
            error("The action was not saved because the employee session ended. Saved work is preserved; scan your barcode again.")
        }
        catch (error: Exception) {
            diagnostic("Pending ${audit.eventType} ${audit.eventId}", error)
            throw IllegalStateException("Action retained for retry. Restore folder access and tap Refresh; do not repeat the action. ${error.message}", error)
        }
    }

    private suspend fun completeOperationLocked(operation: PendingOperationEntity) {
        val audit = EventCodec.decode(operation.eventJson)
        if (audit.eventType !in ORDER_TYPES) return
        val originalSession = operation.employeeSessionId?.let { dao.sessionById(it) }
        val guard: () -> Unit = {
            if (operation.employeeSessionId != null && (originalSession == null || originalSession.endedAtUtc != null ||
                    SessionPolicy.evaluate(originalSession.toDomain(), clockProvider()).expired)) throw ExpiredActionException()
        }
        operation.photoPath?.let { path ->
            val relative = storage.savePhoto(operation.treeUri, audit, File(path), requireNotNull(operation.targetPhotoName), guard)
            check(relative == audit.photoFilename) { "Photo path changed unexpectedly" }
        }
        // A saved photo is already a completed operation; finish its audit even if midnight passed meanwhile.
        // Other operations become successful only when their immutable event is finalized before the deadline.
        storage.appendEvent(operation.treeUri, audit, if (operation.photoPath != null) ({}) else guard)
        database.withTransaction {
            indexEventLocked(audit, true)
            val session = dao.activeSession()
            if (session != null && session.employeeId == audit.employeeId && audit.timestampUtc >= session.loginAtUtc) {
                when (audit.eventType) {
                    EventType.ORDER_CREATED.name, EventType.ORDER_OPENED.name -> dao.saveSession(session.copy(
                        activeOrderKey = orderKey(requireNotNull(audit.orderNumber), audit.isDeveloperEvent), activeOrderSessionId = audit.orderSessionId))
                    EventType.ORDER_SESSION_CLOSED.name, EventType.ORDER_SESSION_CANCELLED.name ->
                        if (session.activeOrderSessionId == audit.orderSessionId) dao.saveSession(session.copy(activeOrderKey = null, activeOrderSessionId = null))
                }
            }
            dao.deletePending(operation.eventId)
        }
        operation.photoPath?.let { withContext(Dispatchers.IO) { File(it).delete() } }
        regeneratePrivateLocked(audit.isDeveloperEvent)
        regenerateOrderLocked(requireNotNull(audit.orderNumber), audit.isDeveloperEvent, operation.treeUri)
    }

    private suspend fun indexEventLocked(audit: ActivityEvent, local: Boolean) {
        val key = orderKey(requireNotNull(audit.orderNumber), audit.isDeveloperEvent)
        val existing = dao.orderEvent(audit.eventId)
        require(existing == null || EventCodec.decode(existing.eventJson) == audit) { "Conflicting event UUID ${audit.eventId}" }
        val order = dao.order(key)
        dao.saveOrder(OrderEntity(key, audit.orderNumber, audit.isDeveloperEvent, maxOf(order?.updatedAtUtc ?: 0L, audit.timestampUtc)))
        dao.insertOrderEvent(OrderEventEntity(audit.eventId, key, EventCodec.encode(audit), audit.timestampUtc))
        if (local) activity.record(audit)
    }

    private suspend fun retryPendingLocked() {
        for (operation in dao.pendingOperations()) {
            val audit = try { EventCodec.decode(operation.eventJson) }
                catch (error: Exception) { diagnostic("Invalid pending operation", error); continue }
            if (audit.eventType !in ORDER_TYPES) continue
            try { completeOperationLocked(operation) }
            catch (expired: ExpiredActionException) { abandonExpiredOperationLocked(operation) }
            catch (error: Exception) { diagnostic("Retry ${audit.eventId}", error) }
        }
    }

    private class ExpiredActionException : IllegalStateException("Employee authorization expired before file finalization")
    private suspend fun abandonExpiredOperationLocked(operation: PendingOperationEntity) {
        val audit = EventCodec.decode(operation.eventJson)
        diagnostic("Uncommitted ${audit.eventType} for ${audit.orderNumber}, ${audit.eventId}", ExpiredActionException())
        dao.deletePending(operation.eventId)
        operation.photoPath?.let { withContext(Dispatchers.IO) { File(it).delete() } }
        validateSessionLocked()
    }

    private suspend fun recoverSettingsEventsLocked() {
        for (operation in dao.pendingOperations()) {
            val audit = EventCodec.decode(operation.eventJson)
            val completed = when (audit.eventType) {
                EventType.STORAGE_CONFIGURED.name -> settings.storageUri == audit.details
                EventType.DEVELOPER_MODE_CHANGED.name -> settings.developerMode.toString() == audit.details
                else -> continue
            }
            database.withTransaction {
                if (completed) {
                    if (audit.eventType == EventType.STORAGE_CONFIGURED.name) { dao.deleteProductionOrderEvents(); dao.deleteProductionOrders() }
                    if (audit.eventType == EventType.DEVELOPER_MODE_CHANGED.name) {
                        val old = dao.employeeByBarcode(TEST_BARCODE)
                        dao.saveEmployee(EmployeeEntity(old?.id ?: TEST_EMPLOYEE_ID, "Test User", TEST_BARCODE, settings.developerMode, true))
                    }
                    activity.record(audit)
                }
                dao.deletePending(operation.eventId)
            }
        }
    }

    suspend fun refreshOrders() = mutex.withLock {
        ensureInitialized()
        recoverSettingsEventsLocked()
        validateSessionLocked()
        retryPendingLocked()
        val session = validateSessionLocked()
        if (session != null) {
            importLocked(session.isDeveloper)
            for (order in dao.allOrders(session.isDeveloper)) regenerateOrderLocked(order.orderNumber, session.isDeveloper, settings.storageUri)
        }
        regeneratePrivateLocked(false)
        regeneratePrivateLocked(true)
    }

    private suspend fun importLocked(developer: Boolean) {
        if (!developer && settings.storageUri == null) return
        val events = storage.readEvents(settings.storageUri, developer)
        database.withTransaction {
            for (audit in events) {
                try {
                    // Imported history belongs in the order cache, never in this phone's private audit log.
                    indexEventLocked(audit, false)
                } catch (error: IllegalArgumentException) { diagnostic("Import ${audit.eventId}", error) }
            }
        }
    }

    private suspend fun eventsLocked(key: String) = dao.eventsForOrder(key).map { EventCodec.decode(it.eventJson) }
    private suspend fun regenerateOrderLocked(number: String, developer: Boolean, treeUri: String?) {
        try { storage.writeOrderMarkdown(treeUri, number, developer, deviceId, OrderMarkdownExporter.render(number, eventsLocked(orderKey(number, developer)))) }
        catch (error: Exception) { diagnostic("Order Markdown $number (regenerate with Refresh)", error) }
    }
    private suspend fun regeneratePrivateLocked(developer: Boolean) {
        try { activity.regenerate(deviceId, deviceModel, developer) }
        catch (error: Exception) { diagnostic("Private Markdown (regenerate with Refresh or Export)", error) }
    }
    private suspend fun ensureInitialized() { if (!::settings.isInitialized) settings = settingsStore.read() }
    private fun diagnostic(operation: String, error: Throwable) {
        if (problems.size >= 100) problems.removeAt(0)
        problems.add("${Instant.now()}: $operation: ${error.message}")
    }
    private fun event(type: EventType, session: DailySession? = null, order: String? = null, workingSession: String? = null,
        details: String? = null, photo: String? = null) = newEvent(type, clockProvider(), deviceId, deviceModel, session, order, workingSession, details, photoFilename = photo)
    private fun stableId(value: String) = UUID.nameUUIDFromBytes(value.toByteArray(Charsets.UTF_8)).toString()

    /** Test-only clock path, available only to the admin-created Test User. No real device clock changes. */
    suspend fun simulateMidnight() = mutex.withLock {
        val session = validateSessionLocked() ?: error("Log in as Test User first")
        require(settings.developerMode && session.isDeveloper) { "Midnight simulation is available only for Test User" }
        val injected = Clock.fixed(Instant.ofEpochMilli(session.expiresAtUtc + 1), ZoneId.of(session.expiresTimezoneId))
        val expiration = SessionPolicy.evaluate(session.toDomain(), injected)
        endSessionLocked(session, true, expiration.effectiveAtUtc, expiration.timezoneId, "TEST: expiration simulated with an injected Clock; actual device time unchanged")
        retryPendingLocked()
    }

    companion object {
        const val TEST_BARCODE = "TEST-USER-001"
        const val TEST_EMPLOYEE_ID = "a0e2d856-ad6e-4d53-97a6-08f8f1ea3e18"
        private const val MAX_NOTE = 20_000
        private const val MAX_PHOTO_BYTES = 40L * 1024 * 1024
        private val ORDER_TYPES = setOf(EventType.ORDER_CREATED.name, EventType.ORDER_OPENED.name,
            EventType.ORDER_SESSION_CLOSED.name, EventType.ORDER_SESSION_CANCELLED.name, EventType.PHOTO_ADDED.name, EventType.NOTE_ADDED.name)
    }
}
