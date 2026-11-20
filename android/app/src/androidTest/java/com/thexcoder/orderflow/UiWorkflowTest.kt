package com.thexcoder.orderflow

import android.net.Uri
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.thexcoder.orderflow.core.ActivityFilter
import com.thexcoder.orderflow.ui.AppActions
import com.thexcoder.orderflow.ui.AppUiState
import com.thexcoder.orderflow.ui.OrderFlowApp
import com.thexcoder.orderflow.ui.Screen
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** UI guards supplement, and do not replace, repository authentication checks. */
class UiWorkflowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun directActivityNavigationWithoutPinShowsPinGate() {
        compose.setContent {
            OrderFlowApp(AppUiState(loading = false, screen = Screen.ACTIVITY, adminConfigured = true, adminAuthenticated = false, deviceId = "PRIVATE-DEVICE-ID"), RecordingActions())
        }
        compose.onNodeWithText("Admin PIN").assertExists()
        compose.onNodeWithText("Export Device Activity Log").assertDoesNotExist()
        compose.onNodeWithText("Device ID: PRIVATE-DEVICE-ID").assertDoesNotExist()
    }

    @Test fun initialSetupRequiresMatchingSixDigitPin() {
        val actions = RecordingActions()
        compose.setContent { OrderFlowApp(AppUiState(loading = false, screen = Screen.ADMIN_PIN), actions) }
        compose.onNodeWithText("Create PIN").assertIsNotEnabled()
        compose.onNodeWithText("New admin PIN").performTextInput("123456")
        compose.onNodeWithText("Confirm PIN").performTextInput("654321")
        compose.onNodeWithText("Create PIN").assertIsNotEnabled()
    }

    @Test fun employeeWelcomeIncludesManualLogoutAndCreateOrder() {
        val actions = RecordingActions()
        compose.setContent { OrderFlowApp(AppUiState(loading = false, screen = Screen.ORDERS, username = "John", storageUri = "content://orders"), actions) }
        compose.onNodeWithText("Welcome, John").assertExists()
        compose.onNodeWithText("Create order").assertIsEnabled()
        compose.onNodeWithText("Log out").performClick()
        assertTrue(actions.loggedOut)
    }

    @Test fun developerBarcodeHiddenInProductionLogin() {
        compose.setContent { OrderFlowApp(AppUiState(loading = false, screen = Screen.LOGIN, developerMode = false), RecordingActions()) }
        compose.onNodeWithText("Scan employee barcode").assertExists()
        compose.onNodeWithText("Simulate Test Barcode").assertDoesNotExist()
    }

    @Test fun developerBarcodeUsesSimulationAction() {
        val actions = RecordingActions()
        compose.setContent { OrderFlowApp(AppUiState(loading = false, screen = Screen.LOGIN, developerMode = true, adminConfigured = true), actions) }
        compose.onNodeWithText("Simulate Test Barcode").performScrollTo().performClick()
        assertTrue(actions.testBarcode)
    }

    @Test fun adminExportRequiresExplicitInitiation() {
        val actions = RecordingActions()
        compose.setContent { OrderFlowApp(AppUiState(loading = false, screen = Screen.ACTIVITY, adminConfigured = true, adminAuthenticated = true, deviceModel = "Google Pixel 4a", deviceId = "device-one"), actions) }
        assertEquals(0, actions.exportRequests)
        compose.onNodeWithText("Export Device Activity Log").performClick()
        assertEquals(1, actions.exportRequests)
    }

    private class RecordingActions : AppActions {
        var loggedOut = false
        var testBarcode = false
        var exportRequests = 0
        override fun dismissMessage() {}
        override fun navigate(screen: Screen) {}
        override fun back() {}
        override fun authenticateAdmin(pin: String) {}
        override fun logoutAdmin() {}
        override fun saveEmployee(id: String?, username: String, barcode: String, active: Boolean) {}
        override fun setDeveloperMode(enabled: Boolean) {}
        override fun setStorage(uri: Uri) {}
        override fun beginStorageSelection() {}
        override fun loginBarcode(barcode: String) {}
        override fun simulateTestBarcode() { testBarcode = true }
        override fun logout() { loggedOut = true }
        override fun createOrder(number: String, initialNote: String) {}
        override fun openOrder(number: String) {}
        override fun closeOrder(cancel: Boolean) {}
        override fun addNote(note: String) {}
        override fun addPhoto(file: File) {}
        override fun refreshOrders() {}
        override fun filterActivity(filter: ActivityFilter) {}
        override fun loadMoreActivity() {}
        override fun beginExport(): String? { exportRequests++; return null }
        override fun exportActivity(uri: Uri?) {}
        override fun simulateMidnight() {}
    }
}
