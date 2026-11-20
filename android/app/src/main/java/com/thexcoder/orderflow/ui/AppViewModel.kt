package com.thexcoder.orderflow.ui

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.thexcoder.orderflow.OrderFlowApplication
import com.thexcoder.orderflow.core.ActivityFilter
import com.thexcoder.orderflow.data.AppRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

class AppViewModel(application: Application) : AndroidViewModel(application), AppActions {
    private val repository: AppRepository = (application as OrderFlowApplication).repository
    private val mutableState = MutableStateFlow(AppUiState())
    val state = mutableState.asStateFlow()
    @Volatile private var foreground = false
    @Volatile private var awaitingAdminPicker = false
    private var exportDeveloper = false
    private var pickerDeadline = 0L
    private val actionMutex = Mutex()
    private var pendingActions = 0
    private var initialized = false
    private var operationRunning = false
    private var refreshCounter = 0

    init {
        execute {
            repository.initialize()
            initialized = true
            repository.refreshOrders()
            synchronize(initial = true)
        }
        viewModelScope.launch {
            while (true) {
                delay(1_000)
                if (foreground && initialized && !mutableState.value.busy) {
                    refreshCounter++
                    if (refreshCounter >= 60) {
                        refreshCounter = 0
                        execute { repository.refreshOrders(); synchronize() }
                    } else {
                        // Foreground expiry does not depend on Android background scheduling.
                        execute(quiet = true) { repository.validateSession(); synchronize() }
                    }
                }
            }
        }
    }

    private fun execute(quiet: Boolean = false, block: suspend () -> Unit) {
        // Quiet lifecycle checks may be skipped; user/camera/SAF results are always serialized and retained.
        if (quiet && (operationRunning || pendingActions > 0)) return
        if (!quiet) { pendingActions++; mutableState.update { it.copy(busy = true) } }
        viewModelScope.launch {
            actionMutex.withLock {
                operationRunning = true
                try {
                    withContext(Dispatchers.IO) { block() }
                    if (!foreground && !validPicker() && repository.adminAccess.authenticated) {
                        withContext(Dispatchers.IO) { repository.logoutAdmin(); synchronize() }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    mutableState.update { it.copy(message = error.message ?: "Operation failed. See administrator diagnostics.") }
                    if (initialized) {
                        try { withContext(Dispatchers.IO) { synchronize() } }
                        catch (_: Exception) { /* Preserve the last usable UI on storage/database failures. */ }
                    }
                } finally {
                    operationRunning = false
                    if (!quiet) { pendingActions--; mutableState.update { it.copy(busy = pendingActions > 0, loading = false) } }
                }
            }
        }
    }

    private fun validPicker() = awaitingAdminPicker && SystemClock.elapsedRealtime() <= pickerDeadline
    private fun startPicker() { awaitingAdminPicker = true; pickerDeadline = SystemClock.elapsedRealtime() + 120_000L }
    private fun finishPicker(): Boolean {
        val valid = validPicker() && repository.adminAccess.authenticated
        awaitingAdminPicker = false
        if (!valid) {
            val revoked = repository.revokeAdminAccess()
            execute { repository.logoutAdmin(alreadyRevoked = revoked); synchronize(Screen.ADMIN_PIN); message("Administrator picker session expired. Enter your PIN and try again.") }
        }
        return valid
    }

    private suspend fun synchronize(preferred: Screen? = null, initial: Boolean = false) {
        val snapshot = repository.snapshot()
        val session = snapshot.session
        withContext(Dispatchers.Main.immediate) {
        val admin = repository.adminAccess.authenticated
        mutableState.update { old ->
            var screen = preferred ?: old.screen
            if (initial) screen = if (snapshot.order != null) Screen.ORDER else if (session != null) Screen.ORDERS else Screen.LOGIN
            if (screen in ADMIN_SCREENS && !admin) screen = Screen.ADMIN_PIN
            if (screen in EMPLOYEE_SCREENS && session == null) screen = Screen.LOGIN
            if (screen in setOf(Screen.ORDER, Screen.CAMERA) && snapshot.order == null) screen = if (session == null) Screen.LOGIN else Screen.ORDERS
            if (screen == Screen.ORDERS && snapshot.order != null) screen = Screen.ORDER
            if (screen == Screen.LOGIN && session != null) screen = if (snapshot.order != null) Screen.ORDER else Screen.ORDERS
            old.copy(loading = false, screen = screen, username = session?.username,
                developerSession = session?.isDeveloper ?: false, developerMode = snapshot.settings.developerMode,
                adminConfigured = snapshot.settings.pinHash != null, adminAuthenticated = admin,
                deviceId = snapshot.settings.deviceId, deviceModel = repository.deviceModel,
                storageUri = snapshot.settings.storageUri, employees = if (admin) snapshot.employees else emptyList(),
                orders = snapshot.orders, orderNumber = snapshot.order?.orderNumber,
                orderEvents = snapshot.orderEvents, diagnostics = if (admin) snapshot.diagnostics else emptyList(),
                activity = if (admin) old.activity else emptyList(), moreActivity = admin && old.moreActivity,
                message = if (old.username != null && session == null && old.message == null) "Employee session ended. Scan your barcode to continue; saved work is preserved." else old.message)
        }
        }
    }

    fun onResume() {
        foreground = true
        // Activity-result callbacks are dispatched before RESUMED. Cancellation also releases this exemption.
        if (awaitingAdminPicker && !validPicker()) { finishPicker() }
        awaitingAdminPicker = false
        if (initialized) execute { repository.refreshOrders(); synchronize() }
    }

    fun onBackground() {
        foreground = false
        if (!validPicker()) {
            val revoked = repository.revokeAdminAccess()
            mutableState.update { it.copy(adminAuthenticated = false, activity = emptyList(), employees = emptyList(), diagnostics = emptyList(), moreActivity = false) }
            if (revoked) viewModelScope.launch {
                actionMutex.withLock { withContext(Dispatchers.IO) { repository.logoutAdmin(alreadyRevoked = true); synchronize() } }
            }
        }
    }

    fun timeChanged() { if (initialized) execute { repository.validateSession(); synchronize() } }
    override fun dismissMessage() { mutableState.update { it.copy(message = null) } }
    override fun navigate(screen: Screen) = execute {
        val destination = if (screen == Screen.ADMIN_PIN && repository.adminAccess.authenticated) Screen.ADMIN else screen
        synchronize(destination)
        if (destination == Screen.ACTIVITY && repository.adminAccess.authenticated) loadActivity(false)
    }

    override fun back() {
        when (mutableState.value.screen) {
            Screen.ORDER -> closeOrder(true)
            Screen.CAMERA -> navigate(Screen.ORDER)
            Screen.SCANNER -> navigate(Screen.LOGIN)
            Screen.EMPLOYEES, Screen.ACTIVITY, Screen.DIAGNOSTICS -> navigate(Screen.ADMIN)
            Screen.ADMIN, Screen.ADMIN_PIN -> logoutAdmin()
            else -> Unit
        }
    }
    override fun authenticateAdmin(pin: String) = execute { repository.authenticateAdmin(pin); synchronize(Screen.ADMIN) }
    override fun logoutAdmin() = execute { repository.logoutAdmin(); synchronize(if (mutableState.value.username == null) Screen.LOGIN else Screen.ORDERS) }
    override fun saveEmployee(id: String?, username: String, barcode: String, active: Boolean) = execute {
        repository.saveEmployee(id, username, barcode, active); synchronize(); message("Employee saved")
    }
    override fun setDeveloperMode(enabled: Boolean) = execute { repository.setDeveloperMode(enabled); synchronize(); message(if (enabled) "Developer Mode enabled. Test data stays private." else "Developer Mode disabled") }
    override fun beginStorageSelection() { repository.adminAccess.requireAdmin(); startPicker() }
    override fun setStorage(uri: Uri) {
        if (!finishPicker()) return
        execute { repository.setStorage(uri); synchronize(); message("Orders folder configured") }
    }
    override fun loginBarcode(barcode: String) = execute {
        val username = repository.loginBarcode(barcode); repository.refreshOrders(); synchronize(Screen.ORDERS); message("Welcome, $username")
    }
    override fun simulateTestBarcode() = execute {
        val username = repository.loginBarcode(AppRepository.TEST_BARCODE, true); repository.refreshOrders(); synchronize(Screen.ORDERS); message("Welcome, $username")
    }
    override fun logout() = execute { repository.logout(); synchronize(Screen.LOGIN); message("Employee logged out") }
    override fun createOrder(number: String, initialNote: String) = execute { repository.createOrder(number, initialNote); synchronize(Screen.ORDER) }
    override fun openOrder(number: String) = execute { repository.openOrder(number); synchronize(Screen.ORDER) }
    override fun closeOrder(cancel: Boolean) = execute { repository.closeOrder(cancel); synchronize(Screen.ORDERS); message(if (cancel) "Working session cancelled. Saved work preserved." else "Order session closed. You remain logged in.") }
    override fun addNote(note: String) = execute { repository.addNote(note); synchronize(Screen.ORDER); message("Note saved") }
    override fun addPhoto(file: File) = execute { repository.addPhoto(file); synchronize(Screen.ORDER); message("Photo saved") }
    override fun refreshOrders() = execute { repository.refreshOrders(); synchronize(); message("Order history refreshed") }
    override fun filterActivity(filter: ActivityFilter) = execute {
        repository.adminAccess.requireAdmin()
        val events = repository.activity.page(filter, 0, 100)
        withContext(Dispatchers.Main.immediate) {
            repository.adminAccess.requireAdmin()
            mutableState.update { it.copy(activityFilter = filter, activity = events, moreActivity = events.size == 100) }
        }
    }
    override fun loadMoreActivity() = execute { loadActivity(true) }
    private suspend fun loadActivity(append: Boolean) {
        val old = mutableState.value
        val events = repository.activity.page(old.activityFilter, if (append) old.activity.size else 0, 100)
        withContext(Dispatchers.Main.immediate) {
            repository.adminAccess.requireAdmin()
            mutableState.update { it.copy(activity = if (append) (it.activity + events).distinctBy { e -> e.eventId } else events, moreActivity = events.size == 100) }
        }
    }
    override fun beginExport(): String? {
        if (!repository.adminAccess.authenticated || mutableState.value.screen != Screen.ACTIVITY) return null
        exportDeveloper = mutableState.value.activityFilter.developer
        startPicker()
        return "OrderFlow-${if (exportDeveloper) "Test-" else ""}DeviceActivity-${repository.deviceId}-${LocalDate.now()}.md"
    }
    override fun exportActivity(uri: Uri?) {
        if (uri == null) { awaitingAdminPicker = false; return }
        if (!finishPicker()) return
        execute {
            repository.activity.export(uri, repository.deviceId, repository.deviceModel, exportDeveloper)
            message("Exported. This copy is outside the app's protected storage and may be accessible to other applications or sync services.")
        }
    }
    override fun simulateMidnight() = execute { repository.simulateMidnight(); synchronize(Screen.LOGIN); message("Test session expired at simulated midnight. Saved work preserved.") }
    private fun message(text: String) { mutableState.update { it.copy(message = text) } }

    companion object {
        private val ADMIN_SCREENS = setOf(Screen.ADMIN, Screen.EMPLOYEES, Screen.ACTIVITY, Screen.DIAGNOSTICS)
        private val EMPLOYEE_SCREENS = setOf(Screen.ORDERS, Screen.ORDER, Screen.CAMERA)
    }
}
