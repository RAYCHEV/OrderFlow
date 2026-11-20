package com.thexcoder.orderflow.data

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import com.thexcoder.orderflow.core.ActivityEvent
import com.thexcoder.orderflow.core.ActivityFilter
import com.thexcoder.orderflow.core.DeviceActivityMarkdownExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/** In-memory PIN capability. Never restored, placed in intents, or granted by developer mode. */
class AdminAccess {
    @Volatile var authenticated: Boolean = false
        private set
    internal fun unlock() { authenticated = true }
    @Synchronized internal fun revoke(): Boolean {
        val wasAuthenticated = authenticated
        authenticated = false
        return wasAuthenticated
    }
    fun requireAdmin() { check(authenticated) { "Administrator PIN authentication required" } }
}

/** Persisted Room records are authoritative; Markdown is always safely regenerable. */
class DeviceActivityRepository(
    private val context: Context,
    private val dao: OrderDao,
    private val access: AdminAccess
) {
    private val reportMutex = Mutex()
    suspend fun record(event: ActivityEvent) {
        val existing = dao.deviceActivity(event.eventId)
        require(existing == null || existing.toDomain() == event) { "Conflicting device activity UUID ${event.eventId}" }
        dao.insertDeviceActivity(DeviceActivityEventEntity.from(event))
    }

    suspend fun page(filter: ActivityFilter, offset: Int, limit: Int = 100): List<ActivityEvent> {
        access.requireAdmin()
        val from = filter.fromDate.trim()
        val to = filter.toDate.trim()
        if (from.isNotBlank()) LocalDate.parse(from)
        if (to.isNotBlank()) LocalDate.parse(to)
        require(from.isBlank() || to.isBlank() || from <= to) { "Start date must be before end date" }
        return dao.activityPage(filter.developer, filter.username.trim(), filter.orderNumber.trim(),
            filter.eventType, filter.search.trim(), from, to, filter.oldestFirst, limit, offset).map { it.toDomain() }
            .also { access.requireAdmin() }
    }

    internal suspend fun regenerate(deviceId: String, deviceModel: String, developer: Boolean): File =
        withContext(Dispatchers.IO) {
            reportMutex.withLock {
                val directory = File(context.filesDir, if (developer) "developer/activity" else "admin/activity")
                check(directory.isDirectory || directory.mkdirs()) { "Cannot create private report directory" }
                val file = AtomicFile(File(directory, "device-activity.md"))
                val markdown = DeviceActivityMarkdownExporter.render(dao.allActivity(developer).map { it.toDomain() }, deviceId, deviceModel, developer)
                val output = file.startWrite()
                try {
                    output.write(markdown.toByteArray(Charsets.UTF_8))
                    file.finishWrite(output)
                } catch (error: Exception) {
                    file.failWrite(output)
                    throw error
                }
                file.baseFile
            }
        }

    suspend fun export(uri: Uri, deviceId: String, deviceModel: String, developer: Boolean) = withContext(Dispatchers.IO) {
        access.requireAdmin()
        val file = regenerate(deviceId, deviceModel, developer)
        access.requireAdmin()
        require(uri.scheme == "content") { "Choose a document using Android's file picker" }
        context.contentResolver.openOutputStream(uri, "wt")?.use { target ->
            file.inputStream().use { it.copyTo(target) }
            target.flush()
        } ?: error("Cannot open export document")
    }
}
