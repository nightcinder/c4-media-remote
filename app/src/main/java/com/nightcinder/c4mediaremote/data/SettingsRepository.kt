package com.nightcinder.c4mediaremote.data

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

enum class Room(val label: String) {
    UPSTAIRS("Upstairs"),
    DOWNSTAIRS("Downstairs");

    fun next(): Room = entries[(ordinal + 1) % entries.size]
}

data class AppSettings(
    val address: String = DEFAULT_ADDRESS,
    val port: Int = DEFAULT_PORT,
    val activeRoom: Room = Room.UPSTAIRS,
    val roomLocked: Boolean = false,
) {
    companion object {
        const val DEFAULT_PORT = 8765
        const val DEFAULT_ADDRESS = "172.21.1.152"
    }
}

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Non-secret settings live in DataStore. The bearer token lives in
 * EncryptedSharedPreferences (AES256-GCM master key in the Android Keystore).
 */
class SettingsRepository(context: Context) {

    private val appContext = context.applicationContext
    private val store = appContext.settingsStore

    private val keyAddress = stringPreferencesKey("controller_address")
    private val keyPort = intPreferencesKey("controller_port")
    private val keyRoom = stringPreferencesKey("active_room")
    private val keyLock = booleanPreferencesKey("room_locked")

    val settings: Flow<AppSettings> = store.data.map { p ->
        AppSettings(
            address = p[keyAddress] ?: AppSettings.DEFAULT_ADDRESS,
            port = p[keyPort] ?: AppSettings.DEFAULT_PORT,
            activeRoom = p[keyRoom]?.let { name -> runCatching { Room.valueOf(name) }.getOrNull() }
                ?: Room.UPSTAIRS,
            roomLocked = p[keyLock] ?: false,
        )
    }

    suspend fun save(settings: AppSettings) {
        store.edit { p ->
            p[keyAddress] = settings.address
            p[keyPort] = settings.port
            p[keyRoom] = settings.activeRoom.name
            p[keyLock] = settings.roomLocked
        }
    }

    suspend fun setActiveRoom(room: Room) {
        store.edit { it[keyRoom] = room.name }
    }

    suspend fun toggleRoomLock() {
        store.edit { it[keyLock] = !(it[keyLock] ?: false) }
    }

    suspend fun loadToken(): String = withContext(Dispatchers.IO) {
        tokenPrefs.getString(KEY_TOKEN, "").orEmpty()
    }

    suspend fun saveToken(token: String) = withContext(Dispatchers.IO) {
        tokenPrefs.edit().putString(KEY_TOKEN, token).apply()
    }

    private val tokenPrefs: SharedPreferences by lazy {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            TOKEN_PREFS_FILE,
            masterKeyAlias,
            appContext,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private companion object {
        const val KEY_TOKEN = "bearer_token"
        const val TOKEN_PREFS_FILE = "secure_token_prefs"
    }
}
