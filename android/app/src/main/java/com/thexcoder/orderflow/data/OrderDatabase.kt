package com.thexcoder.orderflow.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import com.thexcoder.orderflow.core.ActivityEvent
import com.thexcoder.orderflow.core.DailySession
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import java.time.Instant
import java.time.ZoneId

fun orderKey(orderNumber: String, developer: Boolean): String =
    (if (developer) "dev:" else "prod:") + orderNumber

@Entity(tableName = "employees", indices = [Index(value = ["barcode"], unique = true)])
data class EmployeeEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val username: String,
    val barcode: String,
    val active: Boolean = true,
    val isDeveloper: Boolean = false
)

@Entity(tableName = "employee_sessions", indices = [Index("endedAtUtc"), Index("employeeId")])
data class SessionEntity(
    @PrimaryKey val id: String,
    val employeeId: String,
    val username: String,
    val loginAtUtc: Long,
    val loginDate: String,
    val timezoneId: String,
    val expiresAtUtc: Long,
    val isDeveloper: Boolean,
    val lastSeenUtc: Long,
    val endedAtUtc: Long? = null,
    val endReason: String? = null,
    val activeOrderKey: String? = null,
    val activeOrderSessionId: String? = null,
    val expiresTimezoneId: String = timezoneId
) {
    fun toDomain() = DailySession(id, employeeId, username, loginAtUtc, loginDate,
        timezoneId, expiresAtUtc, isDeveloper, lastSeenUtc, expiresTimezoneId)

    companion object {
        fun from(session: DailySession) = SessionEntity(session.sessionId, session.employeeId,
            session.username, session.loginAtUtc, session.loginDate, session.timezoneId,
            session.expiresAtUtc, session.isDeveloper, session.lastSeenUtc,
            expiresTimezoneId = session.expiresTimezoneId)
    }
}

@Entity(tableName = "orders", indices = [Index(value = ["isDeveloper", "updatedAtUtc"])])
data class OrderEntity(
    @PrimaryKey val key: String,
    val orderNumber: String,
    val isDeveloper: Boolean,
    val updatedAtUtc: Long
)

@Entity(tableName = "order_events", indices = [Index(value = ["orderKey", "timestampUtc"])])
data class OrderEventEntity(
    @PrimaryKey val eventId: String,
    val orderKey: String,
    val eventJson: String,
    val timestampUtc: Long
)

@Entity(tableName = "device_activity_events", indices = [
    Index(value = ["isDeveloperEvent", "timestampUtc"]),
    Index(value = ["isDeveloperEvent", "localDate"]),
    Index("eventType"), Index("employeeId"), Index("username"), Index("orderNumber")
])
data class DeviceActivityEventEntity(
    @PrimaryKey val eventId: String,
    val eventType: String,
    val timestampUtc: Long,
    val timezoneId: String,
    val username: String?,
    val employeeId: String?,
    val deviceId: String,
    val deviceModel: String,
    val orderNumber: String?,
    val orderSessionId: String?,
    val details: String?,
    val isDeveloperEvent: Boolean,
    val photoFilename: String?,
    val detectedAtUtc: Long?,
    val localDate: String = Instant.ofEpochMilli(timestampUtc).atZone(ZoneId.of(timezoneId)).toLocalDate().toString()
) {
    fun toDomain() = ActivityEvent(eventId, eventType, timestampUtc, timezoneId, username,
        employeeId, deviceId, deviceModel, orderNumber, orderSessionId, details,
        isDeveloperEvent, photoFilename, detectedAtUtc)

    companion object {
        fun from(event: ActivityEvent) = DeviceActivityEventEntity(event.eventId, event.eventType,
            event.timestampUtc, event.timezoneId, event.username, event.employeeId,
            event.deviceId, event.deviceModel, event.orderNumber, event.orderSessionId,
            event.details, event.isDeveloperEvent, event.photoFilename, event.detectedAtUtc)
    }
}

/** Durable intent. Completion removes this only in the transaction indexing the saved event. */
@Entity(tableName = "pending_operations")
data class PendingOperationEntity(
    @PrimaryKey val eventId: String,
    val eventJson: String,
    val photoPath: String? = null,
    val targetPhotoName: String? = null,
    val treeUri: String? = null,
    val employeeSessionId: String? = null
)

@Dao
interface OrderDao {
    @Query("SELECT * FROM employees WHERE barcode = :barcode LIMIT 1")
    suspend fun employeeByBarcode(barcode: String): EmployeeEntity?
    @Query("SELECT * FROM employees WHERE id = :id LIMIT 1")
    suspend fun employeeById(id: String): EmployeeEntity?
    @Query("SELECT * FROM employees ORDER BY isDeveloper, username COLLATE NOCASE, id")
    suspend fun employees(): List<EmployeeEntity>
    @Query("SELECT * FROM employees ORDER BY isDeveloper, username COLLATE NOCASE, id")
    fun observeEmployees(): Flow<List<EmployeeEntity>>
    @Upsert suspend fun saveEmployee(employee: EmployeeEntity)

    @Query("SELECT * FROM employee_sessions WHERE endedAtUtc IS NULL ORDER BY loginAtUtc DESC LIMIT 1")
    suspend fun activeSession(): SessionEntity?
    @Upsert suspend fun saveSession(session: SessionEntity)
    @Query("SELECT * FROM employee_sessions WHERE id = :id LIMIT 1")
    suspend fun sessionById(id: String): SessionEntity?

    @Query("SELECT * FROM orders WHERE isDeveloper = :developer ORDER BY updatedAtUtc DESC, orderNumber")
    fun observeOrders(developer: Boolean): Flow<List<OrderEntity>>
    @Query("SELECT * FROM orders WHERE isDeveloper = :developer ORDER BY updatedAtUtc DESC, orderNumber")
    suspend fun allOrders(developer: Boolean): List<OrderEntity>
    @Query("SELECT * FROM orders WHERE `key` = :key LIMIT 1")
    suspend fun order(key: String): OrderEntity?
    @Upsert suspend fun saveOrder(order: OrderEntity)
    /** Drop only the production index when changing roots; authoritative files and private activity remain. */
    @Query("DELETE FROM order_events WHERE orderKey LIKE 'prod:%'")
    suspend fun deleteProductionOrderEvents()
    @Query("DELETE FROM orders WHERE isDeveloper = 0")
    suspend fun deleteProductionOrders()

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrderEvent(event: OrderEventEntity): Long
    @Query("SELECT * FROM order_events WHERE orderKey = :key ORDER BY timestampUtc, eventId")
    suspend fun eventsForOrder(key: String): List<OrderEventEntity>
    @Query("SELECT * FROM order_events WHERE eventId = :eventId LIMIT 1")
    suspend fun orderEvent(eventId: String): OrderEventEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDeviceActivity(event: DeviceActivityEventEntity): Long
    @Query("SELECT * FROM device_activity_events WHERE isDeveloperEvent = :developer ORDER BY timestampUtc, eventId")
    suspend fun allActivity(developer: Boolean): List<DeviceActivityEventEntity>
    @Query("SELECT COUNT(*) FROM device_activity_events WHERE isDeveloperEvent = :developer")
    fun observeActivityCount(developer: Boolean): Flow<Int>
    @Query("SELECT COUNT(*) FROM device_activity_events WHERE isDeveloperEvent = :developer")
    suspend fun activityCount(developer: Boolean): Int
    @Query("SELECT * FROM device_activity_events WHERE eventId = :eventId LIMIT 1")
    suspend fun deviceActivity(eventId: String): DeviceActivityEventEntity?

    @Query("""
        SELECT * FROM device_activity_events
        WHERE isDeveloperEvent = :developer
          AND (:username = '' OR instr(lower(coalesce(username, '')), lower(:username)) > 0)
          AND (:orderNumber = '' OR instr(lower(coalesce(orderNumber, '')), lower(:orderNumber)) > 0)
          AND (:eventType = '' OR eventType = :eventType)
          AND (:fromDate = '' OR localDate >= :fromDate)
          AND (:toDate = '' OR localDate <= :toDate)
          AND (:search = '' OR instr(lower(coalesce(username, '') || ' ' || eventType || ' ' ||
              coalesce(orderNumber, '') || ' ' || coalesce(details, '') || ' ' ||
              coalesce(photoFilename, '') || ' ' || deviceModel || ' ' || eventId), lower(:search)) > 0)
        ORDER BY CASE WHEN :oldestFirst THEN timestampUtc END ASC,
                 CASE WHEN NOT :oldestFirst THEN timestampUtc END DESC,
                 eventId ASC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun activityPage(developer: Boolean, username: String, orderNumber: String,
        eventType: String, search: String, fromDate: String, toDate: String, oldestFirst: Boolean,
        limit: Int, offset: Int): List<DeviceActivityEventEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun queueOperation(operation: PendingOperationEntity)
    @Query("SELECT * FROM pending_operations ORDER BY rowid")
    suspend fun pendingOperations(): List<PendingOperationEntity>
    @Query("DELETE FROM pending_operations WHERE eventId = :eventId")
    suspend fun deletePending(eventId: String)
}

@Database(entities = [EmployeeEntity::class, SessionEntity::class, OrderEntity::class,
    OrderEventEntity::class, DeviceActivityEventEntity::class, PendingOperationEntity::class],
    version = 1, exportSchema = true)
abstract class OrderDatabase : RoomDatabase() {
    abstract fun dao(): OrderDao

    companion object {
        fun open(context: Context): OrderDatabase = Room.databaseBuilder(
            context.applicationContext, OrderDatabase::class.java, "orderflow.db"
        ).build()
    }
}
