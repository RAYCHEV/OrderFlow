package com.thexcoder.orderflow.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.thexcoder.orderflow.core.ActivityEvent
import com.thexcoder.orderflow.core.ActivityFilter
import com.thexcoder.orderflow.core.EventType
import com.thexcoder.orderflow.core.formatTimestamp
import com.thexcoder.orderflow.data.EmployeeEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val OrderFlowColors = lightColorScheme(
    primary = Color(0xFF006B60), onPrimary = Color.White,
    primaryContainer = Color(0xFF9DF2DF), onPrimaryContainer = Color(0xFF002019),
    secondary = Color(0xFF48645D), background = Color(0xFFF5F8F5),
    surface = Color(0xFFFCFDF9), surfaceVariant = Color(0xFFE1EBE5)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderFlowApp(state: AppUiState, actions: AppActions) {
    MaterialTheme(colorScheme = OrderFlowColors) {
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(state.message) {
            state.message?.let {
                snackbar.showSnackbar(it, duration = SnackbarDuration.Long)
                actions.dismissMessage()
            }
        }
        val title = when (state.screen) {
            Screen.LOGIN -> "OrderFlow"
            Screen.ORDERS -> "Orders"
            Screen.ORDER -> state.orderNumber ?: "Order"
            Screen.ADMIN_PIN -> if (state.adminConfigured) "Administrator" else "Set up administrator"
            Screen.ADMIN -> "Admin dashboard"
            Screen.EMPLOYEES -> "Employees"
            Screen.ACTIVITY -> "Device Activity Log"
            Screen.DIAGNOSTICS -> "Diagnostics"
            Screen.SCANNER -> "Scan employee barcode"
            Screen.CAMERA -> "Add photograph"
        }
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = { Text(title, fontWeight = FontWeight.SemiBold) },
                    navigationIcon = {
                        if (state.screen != Screen.LOGIN && state.screen != Screen.ORDERS) {
                            TextButton(onClick = actions::back, enabled = !state.busy) { Text("Back") }
                        }
                    },
                    actions = {
                        if (state.screen == Screen.LOGIN || state.screen == Screen.ORDERS) {
                            TextButton(onClick = { actions.navigate(Screen.ADMIN_PIN) }, enabled = !state.busy) { Text("Admin") }
                        }
                    }
                )
            },
            snackbarHost = { SnackbarHost(snackbar) }
        ) { insets ->
            Column(Modifier.fillMaxSize().padding(insets)) {
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.developerSession) {
                    Surface(color = Color(0xFFFFE2A2), modifier = Modifier.fillMaxWidth()) {
                        Text("DEVELOPER TEST SESSION · Private test data", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (state.loading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else {
                    val adminScreen = state.screen in setOf(Screen.ADMIN, Screen.EMPLOYEES, Screen.ACTIVITY, Screen.DIAGNOSTICS)
                    if (adminScreen && !state.adminAuthenticated) {
                        AdminPinScreen(state, actions)
                    } else when (state.screen) {
                        Screen.LOGIN -> LoginScreen(state, actions)
                        Screen.ORDERS -> OrdersScreen(state, actions)
                        Screen.ORDER -> OrderScreen(state, actions)
                        Screen.ADMIN_PIN -> AdminPinScreen(state, actions)
                        Screen.ADMIN -> AdminScreen(state, actions)
                        Screen.EMPLOYEES -> EmployeesScreen(state, actions)
                        Screen.ACTIVITY -> ActivityScreen(state, actions)
                        Screen.DIAGNOSTICS -> DiagnosticsScreen(state)
                        Screen.SCANNER -> CameraScreen(true, state.busy, actions::loginBarcode, actions::addPhoto, actions::back)
                        Screen.CAMERA -> CameraScreen(false, state.busy, actions::loginBarcode, actions::addPhoto, actions::back)
                    }
                }
            }
        }
    }
}

@Composable
private fun LoginScreen(state: AppUiState, actions: AppActions) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("A clear history.\nEvery order.", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(20.dp))
        Text("Scan your registered employee barcode to start your working day. Your session stays active until local midnight.", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(28.dp))
        Button(onClick = { actions.navigate(Screen.SCANNER) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Scan employee barcode") }
        if (!state.adminConfigured) {
            Spacer(Modifier.height(16.dp))
            InfoCard("First use", "Set up the administrator PIN, choose an orders folder, and register employee barcodes.")
            TextButton(onClick = { actions.navigate(Screen.ADMIN_PIN) }, modifier = Modifier.fillMaxWidth()) { Text("Set up administrator") }
        }
        if (state.developerMode) {
            Spacer(Modifier.height(24.dp))
            InfoCard("Developer Mode", "Test User activity and orders stay in private test storage.")
            OutlinedButton(onClick = actions::simulateTestBarcode, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Simulate Test Barcode") }
        }
        Spacer(Modifier.height(32.dp))
        Text(state.deviceModel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun OrdersScreen(state: AppUiState, actions: AppActions) {
    var query by rememberSaveable { mutableStateOf("") }
    var creating by rememberSaveable { mutableStateOf(false) }
    val orders = remember(state.orders, query) { state.orders.filter { it.orderNumber.contains(query.trim(), true) } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Welcome, ${state.username.orEmpty()}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text("Your working day", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = actions::logout, enabled = !state.busy) { Text("Log out") }
            }
        }
        if (state.storageUri == null && !state.developerSession) item { InfoCard("Orders folder needed", "Ask an administrator to select the local orders folder before creating orders.") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { creating = true }, enabled = !state.busy && (state.storageUri != null || state.developerSession), modifier = Modifier.weight(1f)) { Text("Create order") }
                OutlinedButton(onClick = actions::refreshOrders, enabled = !state.busy) { Text("Refresh") }
            }
        }
        item { OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Search order number") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
        item { Text("${orders.size} order${if (orders.size == 1) "" else "s"}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (orders.isEmpty()) item { InfoCard("No orders yet", "Create an order, or refresh to discover orders synchronized into the selected folder.") }
        items(orders, key = { it.key }) { order ->
            Card(onClick = { actions.openOrder(order.orderNumber) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(order.orderNumber, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text("Updated ${displayDate(order.updatedAtUtc)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("Open →", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        if (state.developerSession) item { OutlinedButton(onClick = actions::simulateMidnight, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Simulate midnight expiration") } }
        item { Spacer(Modifier.height(20.dp)) }
    }
    if (creating) CreateOrderDialog(onDismiss = { creating = false }, onCreate = { number, note -> creating = false; actions.createOrder(number, note) })
}

@Composable
private fun CreateOrderDialog(onDismiss: () -> Unit, onCreate: (String, String) -> Unit) {
    var number by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    val valid = number.matches(Regex("[A-Za-z0-9_-]{1,64}"))
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Create order") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(number, { number = it }, label = { Text("Order number") }, supportingText = { Text("Letters, numbers, hyphens and underscores; 1–64 characters.") }, singleLine = true)
            OutlinedTextField(note, { note = it }, label = { Text("Initial note (optional)") }, minLines = 3, maxLines = 6)
        }
    }, confirmButton = { TextButton(enabled = valid, onClick = { onCreate(number, note) }) { Text("Create") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun OrderScreen(state: AppUiState, actions: AppActions) {
    var addingNote by rememberSaveable { mutableStateOf(false) }
    var closing by rememberSaveable { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Working as ${state.username.orEmpty()}", style = MaterialTheme.typography.titleMedium)
            Text("Saved changes remain in the complete order history.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { actions.navigate(Screen.CAMERA) }, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text("Add photo") }
                OutlinedButton(onClick = { addingNote = true }, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text("Add note") }
            }
        }
        item { Button(onClick = { closing = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Close order session") } }
        item { Text("Complete order history", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
        items(state.orderEvents.asReversed(), key = { it.eventId }) { EventCard(it, includeOrder = false) }
        if (state.orderEvents.isEmpty()) item { InfoCard("No history available", "Refresh from the orders list to import synchronized order events.") }
        item { Spacer(Modifier.height(20.dp)) }
    }
    if (addingNote) {
        var text by rememberSaveable { mutableStateOf("") }
        AlertDialog(onDismissRequest = { addingNote = false }, title = { Text("Add note") }, text = {
            OutlinedTextField(text, { text = it }, label = { Text("Note") }, minLines = 4, maxLines = 9, modifier = Modifier.fillMaxWidth())
        }, confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { addingNote = false; actions.addNote(text) }) { Text("Save note") } }, dismissButton = { TextButton(onClick = { addingNote = false }) { Text("Cancel") } })
    }
    if (closing) AlertDialog(onDismissRequest = { closing = false }, title = { Text("Finish this order session?") }, text = { Text("All saved photographs and notes are preserved. You remain logged in and can reopen this order later.") }, confirmButton = { TextButton(onClick = { closing = false; actions.closeOrder() }) { Text("Close session") } }, dismissButton = {
        Column {
            TextButton(onClick = { closing = false; actions.closeOrder(cancel = true) }) { Text("Mark session cancelled") }
            TextButton(onClick = { closing = false }) { Text("Keep working") }
        }
    })
}

@Composable
private fun AdminPinScreen(state: AppUiState, actions: AppActions) {
    var pin by remember(state.adminConfigured) { mutableStateOf("") }
    var confirmation by remember(state.adminConfigured) { mutableStateOf("") }
    val valid = pin.matches(Regex("[0-9]{6,12}")) && (state.adminConfigured || pin == confirmation)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text(if (state.adminConfigured) "Administrator access" else "Protect this device", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        Text(if (state.adminConfigured) "Enter the administrator PIN to manage employees, device settings, and private activity history." else "Choose a 6–12 digit administrator PIN. This PIN belongs to this installation and is never placed in the shared orders folder.")
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(12) }, label = { Text(if (state.adminConfigured) "Admin PIN" else "New admin PIN") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), singleLine = true, modifier = Modifier.fillMaxWidth())
        if (!state.adminConfigured) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(confirmation, { confirmation = it.filter(Char::isDigit).take(12) }, label = { Text("Confirm PIN") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(20.dp))
        Button(onClick = { actions.authenticateAdmin(pin); pin = ""; confirmation = "" }, enabled = valid && !state.busy, modifier = Modifier.fillMaxWidth()) { Text(if (state.adminConfigured) "Unlock admin panel" else "Create PIN") }
    }
}

@Composable
private fun AdminScreen(state: AppUiState, actions: AppActions) {
    val storagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) actions.setStorage(uri) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        DeviceCard(state)
        MenuCard("Employees", "Register barcodes, edit names, and deactivate access", onClick = { actions.navigate(Screen.EMPLOYEES) })
        MenuCard("Device Activity Log", "Private history for this Android installation", onClick = { actions.navigate(Screen.ACTIVITY) })
        MenuCard("Diagnostics", "Storage, pending operations, and import information", onClick = { actions.navigate(Screen.DIAGNOSTICS) })
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Orders folder", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(state.storageUri ?: "No production folder selected", style = MaterialTheme.typography.bodySmall)
                Text("Choose a local folder managed by your external synchronization software. OrderFlow does not connect to OneDrive.", style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = {
                    actions.beginStorageSelection()
                    storagePicker.launch(state.storageUri?.let(Uri::parse))
                }, enabled = !state.busy) { Text("Choose storage directory") }
            }
        }
        ElevatedCard(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(state.developerMode, { actions.setDeveloperMode(it) }, enabled = !state.busy)
                Column(Modifier.weight(1f)) {
                    Text("Developer Mode", style = MaterialTheme.typography.titleMedium)
                    Text("Test User · TEST-USER-001\nTest orders and logs stay private.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        OutlinedButton(onClick = actions::logoutAdmin, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Lock admin panel") }
    }
}

@Composable
private fun EmployeesScreen(state: AppUiState, actions: AppActions) {
    var editing by remember { mutableStateOf<EmployeeEntity?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Button(onClick = { adding = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Register employee") }
        }
        if (state.employees.isEmpty()) item { InfoCard("Register your team", "Each employee needs a unique barcode. Inactive employees cannot sign in.") }
        items(state.employees, key = { it.id }) { employee ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(employee.username, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(employee.barcode, style = MaterialTheme.typography.bodySmall)
                        Text(if (employee.isDeveloper) "Developer test employee" else if (employee.active) "Active" else "Deactivated", color = if (employee.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                    }
                    if (!employee.isDeveloper) TextButton(onClick = { editing = employee }, enabled = !state.busy) { Text("Edit") }
                }
            }
        }
    }
    if (adding || editing != null) EmployeeDialog(employee = editing, onDismiss = { adding = false; editing = null }, onSave = { id, name, barcode, active -> adding = false; editing = null; actions.saveEmployee(id, name, barcode, active) })
}

@Composable
private fun EmployeeDialog(employee: EmployeeEntity?, onDismiss: () -> Unit, onSave: (String?, String, String, Boolean) -> Unit) {
    var name by rememberSaveable(employee?.id) { mutableStateOf(employee?.username ?: "") }
    var barcode by rememberSaveable(employee?.id) { mutableStateOf(employee?.barcode ?: "") }
    var active by rememberSaveable(employee?.id) { mutableStateOf(employee?.active ?: true) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (employee == null) "Register employee" else "Edit employee") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Username") }, singleLine = true)
            OutlinedTextField(barcode, { barcode = it }, label = { Text("Employee barcode") }, singleLine = true)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(active, { active = it })
                Text("Employee is active")
            }
        }
    }, confirmButton = { TextButton(enabled = name.isNotBlank() && barcode.isNotBlank(), onClick = { onSave(employee?.id, name.trim(), barcode.trim(), active) }) { Text("Save employee") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun ActivityScreen(state: AppUiState, actions: AppActions) {
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown"), actions::exportActivity)
    var filtersExpanded by rememberSaveable { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { DeviceCard(state) }
        item {
            InfoCard(if (state.activityFilter.developer) "Developer test history" else "Protected device history", "This history is stored privately on this device. Export only when you intend to create a copy outside protected storage.")
        }
        item {
            Button(onClick = { actions.beginExport()?.let { exportPicker.launch(it) } }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Export Device Activity Log") }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { filtersExpanded = !filtersExpanded }, modifier = Modifier.weight(1f)) { Text(if (filtersExpanded) "Hide filters" else "Search and filters") }
                TextButton(onClick = { actions.filterActivity(state.activityFilter.copy(oldestFirst = !state.activityFilter.oldestFirst)) }) { Text(if (state.activityFilter.oldestFirst) "Oldest first ↑" else "Newest first ↓") }
            }
        }
        if (filtersExpanded) item { ActivityFilters(state.activityFilter, actions::filterActivity) }
        if (state.activity.isEmpty()) item { InfoCard("No matching activity", "Adjust the filters, or complete an action to add activity to this device's history.") }
        items(state.activity, key = { it.eventId }) { event -> EventCard(event, includeOrder = true) }
        if (state.moreActivity) item { OutlinedButton(onClick = actions::loadMoreActivity, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Load more activity") } }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun ActivityFilters(filter: ActivityFilter, onApply: (ActivityFilter) -> Unit) {
    var draft by remember(filter) { mutableStateOf(filter) }
    var actionsOpen by remember { mutableStateOf(false) }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(draft.search, { draft = draft.copy(search = it) }, label = { Text("Search activity") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(draft.fromDate, { draft = draft.copy(fromDate = it) }, label = { Text("From YYYY-MM-DD") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(draft.toDate, { draft = draft.copy(toDate = it) }, label = { Text("To YYYY-MM-DD") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            OutlinedTextField(draft.username, { draft = draft.copy(username = it) }, label = { Text("Employee username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(draft.orderNumber, { draft = draft.copy(orderNumber = it) }, label = { Text("Order number") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Box {
                OutlinedButton(onClick = { actionsOpen = true }) { Text(if (draft.eventType.isBlank()) "Action: All" else actionName(draft.eventType)) }
                DropdownMenu(expanded = actionsOpen, onDismissRequest = { actionsOpen = false }, modifier = Modifier.heightIn(max = 320.dp)) {
                    DropdownMenuItem(text = { Text("All actions") }, onClick = { draft = draft.copy(eventType = ""); actionsOpen = false })
                    EventType.entries.forEach { type -> DropdownMenuItem(text = { Text(actionName(type.name)) }, onClick = { draft = draft.copy(eventType = type.name); actionsOpen = false }) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(draft.developer, { draft = draft.copy(developer = it) })
                Text("View developer test activity")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onApply(draft) }, modifier = Modifier.weight(1f)) { Text("Apply filters") }
                TextButton(onClick = { onApply(ActivityFilter(developer = draft.developer)) }) { Text("Reset") }
            }
        }
    }
}

@Composable
private fun EventCard(event: ActivityEvent, includeOrder: Boolean) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(actionName(event.eventType), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("${event.username ?: "Unknown employee"} · ${displayDate(event.timestampUtc, event.timezoneId)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (includeOrder) event.orderNumber?.let { Text("Order: $it", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
            event.details?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            event.photoFilename?.let { Text("Photo: $it", style = MaterialTheme.typography.bodySmall) }
            if (event.detectedAtUtc != null && event.detectedAtUtc != event.timestampUtc) Text("Detected: ${displayDate(event.detectedAtUtc, event.timezoneId)}", style = MaterialTheme.typography.bodySmall)
            Text(event.deviceModel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (event.isDeveloperEvent) Text("TEST ACTIVITY", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
        }
    }
}

@Composable
private fun DiagnosticsScreen(state: AppUiState) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { DeviceCard(state) }
        item { InfoCard("Storage", state.storageUri ?: "Production orders folder not configured") }
        if (state.diagnostics.isEmpty()) item { InfoCard("No diagnostics", "No storage or import issues have been reported.") }
        items(state.diagnostics) { message -> InfoCard("Status", message) }
    }
}

@Composable
private fun DeviceCard(state: AppUiState) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(state.deviceModel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Device ID: ${state.deviceId}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun MenuCard(title: String, subtitle: String, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("→", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

private fun actionName(type: String): String = when (type) {
    "EMPLOYEE_LOGIN" -> "Employee login"
    "EMPLOYEE_LOGOUT" -> "Employee logout"
    "SESSION_EXPIRED_MIDNIGHT" -> "Session expired at midnight"
    "ORDER_CREATED" -> "Order created"
    "ORDER_OPENED" -> "Order opened"
    "ORDER_SESSION_CLOSED" -> "Order session closed"
    "ORDER_SESSION_CANCELLED" -> "Order session interrupted / cancelled"
    "PHOTO_ADDED" -> "Photo added"
    "NOTE_ADDED" -> "Note added"
    "ADMIN_LOGIN" -> "Administrator login"
    "ADMIN_LOGOUT" -> "Administrator logout"
    "EMPLOYEE_REGISTERED" -> "Employee registered"
    "EMPLOYEE_UPDATED" -> "Employee updated"
    "EMPLOYEE_DEACTIVATED" -> "Employee deactivated"
    "STORAGE_CONFIGURED" -> "Orders folder configured"
    "DEVELOPER_MODE_CHANGED" -> "Developer Mode changed"
    else -> type.replace('_', ' ').lowercase().replaceFirstChar(Char::uppercase)
}

private fun displayDate(timestamp: Long, timezone: String = ZoneId.systemDefault().id): String = runCatching {
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.of(timezone)))
}.getOrElse { formatTimestamp(timestamp, "UTC") }
