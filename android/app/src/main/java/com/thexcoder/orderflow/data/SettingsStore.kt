package com.thexcoder.orderflow.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.util.UUID

private val Context.orderFlowSettings by preferencesDataStore("private_settings")
data class Settings(val deviceId: String, val pinHash: String?, val storageUri: String?,
    val developerMode: Boolean, val failedPins: Long, val lockedUntil: Long)

/** This DataStore, including the salted PIN verifier, is never exported or backed up. */
class SettingsStore(context: Context) {
    private val store = context.applicationContext.orderFlowSettings
    private val device = stringPreferencesKey("installation_id")
    private val pin = stringPreferencesKey("pin_verifier")
    private val storage = stringPreferencesKey("orders_tree_uri")
    private val developer = booleanPreferencesKey("developer_mode")
    private val failures = longPreferencesKey("pin_failures")
    private val locked = longPreferencesKey("pin_locked_until")
    suspend fun read(): Settings {
        if (store.data.first()[device] == null) store.edit {
            if (it[device] == null) it[device] = UUID.randomUUID().toString()
        }
        val prefs = store.data.first()
        return Settings(requireNotNull(prefs[device]), prefs[pin], prefs[storage],
            prefs[developer] ?: false, prefs[failures] ?: 0L, prefs[locked] ?: 0L)
    }
    suspend fun setPin(value: String) { store.edit { it[pin] = value } }
    suspend fun setStorage(value: String) { store.edit { it[storage] = value } }
    suspend fun setDeveloper(value: Boolean) { store.edit { it[developer] = value } }
    suspend fun setFailures(count: Long, until: Long) { store.edit { it[failures] = count; it[locked] = until } }
}
