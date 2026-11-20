package com.thexcoder.orderflow.ui

import android.net.Uri
import com.thexcoder.orderflow.core.ActivityEvent
import com.thexcoder.orderflow.core.ActivityFilter
import com.thexcoder.orderflow.data.EmployeeEntity
import com.thexcoder.orderflow.data.OrderEntity
import java.io.File

enum class Screen { LOGIN, ORDERS, ORDER, ADMIN_PIN, ADMIN, EMPLOYEES, ACTIVITY, DIAGNOSTICS, SCANNER, CAMERA }

data class AppUiState(
    val loading: Boolean = true,
    val busy: Boolean = false,
    val screen: Screen = Screen.LOGIN,
    val username: String? = null,
    val developerSession: Boolean = false,
    val developerMode: Boolean = false,
    val adminConfigured: Boolean = false,
    val adminAuthenticated: Boolean = false,
    val deviceId: String = "",
    val deviceModel: String = "",
    val storageUri: String? = null,
    val employees: List<EmployeeEntity> = emptyList(),
    val orders: List<OrderEntity> = emptyList(),
    val orderNumber: String? = null,
    val orderEvents: List<ActivityEvent> = emptyList(),
    val message: String? = null,
    val diagnostics: List<String> = emptyList(),
    val activity: List<ActivityEvent> = emptyList(),
    val activityFilter: ActivityFilter = ActivityFilter(),
    val moreActivity: Boolean = false
)

interface AppActions {
    fun dismissMessage()
    fun navigate(screen: Screen)
    fun back()
    fun authenticateAdmin(pin: String)
    fun logoutAdmin()
    fun saveEmployee(id: String?, username: String, barcode: String, active: Boolean)
    fun setDeveloperMode(enabled: Boolean)
    fun setStorage(uri: Uri)
    fun beginStorageSelection()
    fun loginBarcode(barcode: String)
    fun simulateTestBarcode()
    fun logout()
    fun createOrder(number: String, initialNote: String)
    fun openOrder(number: String)
    fun closeOrder(cancel: Boolean = false)
    fun addNote(note: String)
    fun addPhoto(file: File)
    fun refreshOrders()
    fun filterActivity(filter: ActivityFilter)
    fun loadMoreActivity()
    fun beginExport(): String?
    fun exportActivity(uri: Uri?)
    fun simulateMidnight()
}
