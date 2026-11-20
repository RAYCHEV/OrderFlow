package com.thexcoder.orderflow.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.thexcoder.orderflow.core.ActivityEvent
import com.thexcoder.orderflow.core.EventCodec
import com.thexcoder.orderflow.core.EventType
import com.thexcoder.orderflow.core.validateOrderNumber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

/** SAF is used only for production order artifacts. Private developer data never enters that tree. */
class OrderStorage(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val developerRoot = File(appContext.filesDir, "developer/orders")
    private val mutex = Mutex()
    private val problems = CopyOnWriteArrayList<String>()
    private val sharedTypes = setOf(EventType.ORDER_CREATED.name, EventType.ORDER_OPENED.name,
        EventType.ORDER_SESSION_CLOSED.name, EventType.ORDER_SESSION_CANCELLED.name,
        EventType.PHOTO_ADDED.name, EventType.NOTE_ADDED.name)

    fun diagnostics(): List<String> = problems.toList()

    private fun problem(message: String) {
        // Diagnostics are bounded; the activity history itself is never truncated.
        if (problems.size >= 100) problems.removeAt(0)
        problems.add(message)
    }

    suspend fun appendEvent(treeUri: String?, event: ActivityEvent, beforeFinalize: () -> Unit = {}) = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(event.eventType in sharedTypes) { "Only order events belong in shared storage" }
            val order = validateOrderNumber(requireNotNull(event.orderNumber))
            val json = EventCodec.encode(event)
            // Validate locally produced events as rigorously as imported events.
            EventCodec.decode(json)
            val root = root(treeUri, event.isDeveloperEvent, true)!!
            val events = root.directory(order).directory("events").directory(segment(event.deviceId))
            immutableBytes(events, "${event.eventId}.json", json.toByteArray(Charsets.UTF_8),
                "application/json", "${event.eventId}.pending", beforeFinalize)
        }
    }

    suspend fun readEvents(treeUri: String?, developer: Boolean): List<ActivityEvent> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val root = root(treeUri, developer, false) ?: return@withLock emptyList()
                val result = LinkedHashMap<String, ActivityEvent>()
                for (order in root.children().filter { it.isDirectory }) {
                    val orderNumber = runCatching { validateOrderNumber(order.name) }.getOrNull() ?: continue
                    val eventRoot = order.find("events") ?: continue
                    for (device in eventRoot.children().filter { it.isDirectory }) {
                        for (file in device.children().filter { !it.isDirectory && it.name.endsWith(".json") }) {
                            try {
                                val raw = file.readLimited(MAX_EVENT_BYTES).toString(Charsets.UTF_8)
                                val event = EventCodec.decode(raw)
                                require(event.eventType in sharedTypes) { "Unexpected shared event type" }
                                require(event.orderNumber == orderNumber) { "Order directory does not match event" }
                                require(event.isDeveloperEvent == developer) { "Test/production history mismatch" }
                                require(event.deviceId == device.name) { "Device directory does not match event" }
                                require(file.name == "${event.eventId}.json") { "UUID filename does not match event" }
                                val previous = result[event.eventId]
                                require(previous == null || previous == event) { "Conflicting duplicate event UUID" }
                                result[event.eventId] = event
                            } catch (error: Exception) {
                                problem("Skipped ${order.name}/events/${device.name}/${file.name}: ${error.message}")
                            }
                        }
                    }
                }
                result.values.sortedWith(compareBy<ActivityEvent> { it.timestampUtc }.thenBy { it.eventId })
            }
        }

    suspend fun nextPhotoName(treeUri: String?, orderNumber: String, deviceId: String,
        developer: Boolean): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val order = validateOrderNumber(orderNumber)
            val photos = root(treeUri, developer, true)!!.directory(order)
                .directory("photos").directory(segment(deviceId))
            val pattern = Regex("${Regex.escape(order)}-(\\d+)\\.jpg(?:\\..*)?")
            val max = photos.children().mapNotNull { pattern.matchEntire(it.name)?.groupValues?.get(1)?.toLongOrNull() }
                .maxOrNull() ?: 0L
            check(max < Long.MAX_VALUE) { "Photo number limit reached" }
            "$order-${max + 1}.jpg"
        }
    }

    /** Returns the path relative to the order folder; the name is unique within this device namespace. */
    suspend fun savePhoto(treeUri: String?, event: ActivityEvent, source: File, filename: String, beforeFinalize: () -> Unit = {}): String =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val order = validateOrderNumber(requireNotNull(event.orderNumber))
                require(Regex("${Regex.escape(order)}-[1-9][0-9]*\\.jpg").matches(filename)) { "Invalid photo name" }
                require(source.isFile && source.length() > 0L) { "Photo capture is missing or empty" }
                val photos = root(treeUri, event.isDeveloperEvent, true)!!.directory(order)
                    .directory("photos").directory(segment(event.deviceId))
                val existing = photos.find(filename)
                if (existing != null) {
                    require(!existing.isDirectory && digest(existing.openInput()) == digest(source.inputStream())) {
                        "Photo collision: $filename was not overwritten"
                    }
                } else {
                    val pendingName = "$filename.${event.eventId}.pending"
                    photos.find(pendingName)?.let { check(it.delete()) { "Cannot remove incomplete photo" } }
                    val pending = photos.create("application/octet-stream", pendingName)
                    try {
                        pending.openOutput().use { output -> source.inputStream().use { it.copyTo(output) } }
                        check(digest(pending.openInput()) == digest(source.inputStream())) { "Photo verification failed" }
                        check(photos.find(filename) == null) { "Photo collision: $filename was not overwritten" }
                        beforeFinalize()
                        check(pending.rename(filename)) { "Storage provider cannot finalize photos; choose a local folder" }
                        val saved = photos.find(filename) ?: error("Saved photo is unavailable")
                        check(saved.name == filename && digest(saved.openInput()) == digest(source.inputStream())) {
                            "Saved photo verification failed"
                        }
                    } catch (error: Exception) {
                        // Keep a successfully finalized photo for idempotent recovery; only .pending is disposable.
                        photos.find(pendingName)?.delete()
                        throw error
                    }
                }
                "photos/${segment(event.deviceId)}/$filename"
            }
        }

    suspend fun writeOrderMarkdown(treeUri: String?, orderNumber: String, developer: Boolean,
        deviceId: String, markdown: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val order = validateOrderNumber(orderNumber)
            val directory = root(treeUri, developer, true)!!.directory(order)
            // Each device owns its report, so simultaneous synchronization cannot lose the immutable history.
            replaceReport(directory.directory("reports"), "${segment(deviceId)}.md", markdown)
            // This is a convenient derived view. It may be regenerated by any device after importing all events.
            replaceReport(directory, "$order.md", markdown)
        }
    }

    /** Validate that a selected production tree really supports the local-folder protocol. */
    suspend fun validateTree(treeUri: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val directory = root(treeUri, false, true)!!
            val name = ".orderflow-check-${java.util.UUID.randomUUID()}"
            val probe = directory.create("application/octet-stream", name)
            try {
                probe.openOutput().use { it.write(byteArrayOf(79, 70)) }
                check(probe.readLimited(16).contentEquals(byteArrayOf(79, 70))) { "Cannot read selected folder" }
                check(probe.rename("$name.done")) { "Select a local folder that supports renaming files" }
            } finally {
                probe.delete()
                directory.find(name)?.delete()
                directory.find("$name.done")?.delete()
            }
        }
    }

    private fun root(treeUri: String?, developer: Boolean, create: Boolean): Node? {
        if (developer) {
            if (create) check(developerRoot.isDirectory || developerRoot.mkdirs()) { "Cannot create private test folder" }
            return if (developerRoot.isDirectory) LocalNode(developerRoot) else null
        }
        require(!treeUri.isNullOrBlank()) { "An administrator must select an order storage folder" }
        val document = DocumentFile.fromTreeUri(appContext, Uri.parse(treeUri))
        require(document != null && document.isDirectory && document.canRead() && document.canWrite()) {
            "Order folder permission is unavailable. Ask an administrator to select it again."
        }
        return SafNode(document)
    }

    private fun segment(value: String): String {
        require(Regex("[A-Za-z0-9_-]{1,128}").matches(value)) { "Invalid device identifier" }
        return value
    }

    private fun immutableBytes(directory: Node, name: String, bytes: ByteArray, mime: String, pendingName: String, beforeFinalize: () -> Unit) {
        val existing = directory.find(name)
        if (existing != null) {
            require(!existing.isDirectory && existing.readLimited(MAX_EVENT_BYTES).contentEquals(bytes)) {
                "Conflicting event $name was not overwritten"
            }
            return
        }
        directory.find(pendingName)?.let { check(it.delete()) { "Cannot remove incomplete event" } }
        val pending = directory.create("application/octet-stream", pendingName)
        try {
            pending.openOutput().use { it.write(bytes) }
            check(pending.readLimited(MAX_EVENT_BYTES).contentEquals(bytes)) { "Event verification failed" }
            // Never replace a final event. UUIDs plus a device namespace make accidental collisions unlikely.
            check(directory.find(name) == null) { "Event already exists" }
            beforeFinalize()
            check(pending.rename(name)) { "Storage provider cannot finalize $mime files; select a local folder" }
            val saved = directory.find(name) ?: error("Saved event cannot be found")
            check(saved.readLimited(MAX_EVENT_BYTES).contentEquals(bytes)) { "Saved event verification failed" }
        } catch (error: Exception) {
            directory.find(pendingName)?.delete()
            throw error
        }
    }

    private fun replaceReport(directory: Node, name: String, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val pendingName = "$name.pending"
        directory.find(pendingName)?.let { check(it.delete()) { "Cannot remove old temporary report" } }
        val pending = directory.create("application/octet-stream", pendingName)
        pending.openOutput().use { it.write(bytes) }
        check(pending.readLimited(MAX_REPORT_BYTES).contentEquals(bytes)) { "Report verification failed" }
        // SAF has no atomic replace. Preserve the old copy until a full new report exists.
        val backupName = "$name.previous"
        val old = directory.find(name)
        directory.find(backupName)?.delete()
        if (old != null) check(old.rename(backupName)) { "Cannot preserve previous report" }
        try {
            check(pending.rename(name)) { "Cannot finalize order report" }
            directory.find(backupName)?.delete()
        } catch (error: Exception) {
            directory.find(backupName)?.rename(name)
            throw error
        }
    }

    private fun digest(input: InputStream): String = input.use {
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val read = it.read(buffer)
            if (read < 0) break
            hash.update(buffer, 0, read)
        }
        hash.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private interface Node {
        val name: String
        val isDirectory: Boolean
        fun children(): List<Node>
        fun find(name: String): Node? = children().firstOrNull { it.name == name }
        fun directory(name: String): Node
        fun create(mime: String, name: String): Node
        fun openInput(): InputStream
        fun openOutput(): OutputStream
        fun delete(): Boolean
        fun rename(name: String): Boolean
        fun readLimited(max: Int): ByteArray = openInput().use {
            val buffer = ByteArray(16 * 1024)
            val output = ByteArrayOutputStream()
            var count = 0
            while (true) {
                val read = it.read(buffer)
                if (read < 0) break
                count += read
                require(count <= max) { "File exceeds size limit" }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
    }

    private inner class SafNode(private val document: DocumentFile) : Node {
        override val name: String get() = document.name ?: ""
        override val isDirectory: Boolean get() = document.isDirectory
        override fun children(): List<Node> = document.listFiles().map { SafNode(it) }
        override fun directory(name: String): Node {
            val found = document.findFile(name)
            val dir = found ?: document.createDirectory(name) ?: error("Cannot create $name")
            check(dir.isDirectory && dir.name == name) { "Invalid folder $name" }
            return SafNode(dir)
        }
        override fun create(mime: String, name: String): Node {
            check(document.findFile(name) == null) { "File already exists: $name" }
            val child = document.createFile(mime, name) ?: error("Cannot create $name")
            if (child.name != name) {
                child.delete()
                error("Storage provider changed filename $name; nothing was overwritten")
            }
            return SafNode(child)
        }
        override fun openInput(): InputStream = resolver.openInputStream(document.uri) ?: error("Cannot read $name")
        override fun openOutput(): OutputStream = resolver.openOutputStream(document.uri, "w") ?: error("Cannot write $name")
        override fun delete(): Boolean = document.delete()
        override fun rename(name: String): Boolean = document.renameTo(name) && document.name == name
    }

    private class LocalNode(private var file: File) : Node {
        override val name: String get() = file.name
        override val isDirectory: Boolean get() = file.isDirectory
        override fun children(): List<Node> = file.listFiles()?.map { LocalNode(it) } ?: emptyList()
        override fun directory(name: String): Node {
            val child = File(file, name)
            check(child.isDirectory || child.mkdirs()) { "Cannot create private directory" }
            return LocalNode(child)
        }
        override fun create(mime: String, name: String): Node {
            val child = File(file, name)
            check(child.createNewFile()) { "Private file already exists" }
            return LocalNode(child)
        }
        override fun openInput(): InputStream = file.inputStream()
        override fun openOutput(): OutputStream = file.outputStream()
        override fun delete(): Boolean = file.delete()
        override fun rename(name: String): Boolean {
            val target = File(file.parentFile, name)
            if (target.exists()) return false
            if (!file.renameTo(target)) return false
            file = target
            return true
        }
    }

    private companion object {
        const val MAX_EVENT_BYTES = 1024 * 1024
        const val MAX_REPORT_BYTES = 128 * 1024 * 1024
    }
}
