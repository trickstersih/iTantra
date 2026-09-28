package com.tactical.app.di

import android.content.Context
import com.tactical.platform.api.squad.SquadMembershipStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists squad membership independently from BLE.
 *
 * The first read migrates the legacy BLE-owned squad ID set so existing
 * installs keep their current squad without requiring the user to re-add
 * anyone.
 */
@Singleton
class PersistentSquadMembershipStore @Inject constructor(
    @ApplicationContext context: Context
) : SquadMembershipStore {

    private val preferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile
    private var initialized = false

    @Synchronized
    private fun ensureInitialized() {
        if (initialized) return

        val existing = preferences.getStringSet(KEY_SQUAD_IDS, null)
        if (existing == null) {
            val legacyPreferences =
                context.getSharedPreferences(LEGACY_BLE_PREFS_NAME, Context.MODE_PRIVATE)
            val legacy = legacyPreferences
                .getStringSet(LEGACY_SQUAD_IDS_KEY, emptySet())
                ?.filter { it.isNotBlank() }
                ?.toSet()
                ?: emptySet()

            preferences.edit()
                .putStringSet(KEY_SQUAD_IDS, legacy)
                .apply()
        }

        initialized = true
    }

    override fun squadDeviceIds(): Set<String> {
        ensureInitialized()
        return preferences.getStringSet(KEY_SQUAD_IDS, emptySet())
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?: emptySet()
    }

    @Synchronized
    override fun add(deviceId: String) {
        ensureInitialized()
        val cleaned = deviceId.trim()
        if (cleaned.isBlank() || UUID.fromString(cleaned) == null) {
            // Stable iTantra IDs are UUIDs. Keep invalid identifiers out of
            // the shared membership store.
            return
        }

        val updated = squadDeviceIds() + cleaned
        preferences.edit()
            .putStringSet(KEY_SQUAD_IDS, updated)
            .apply()
    }

    @Synchronized
    override fun remove(deviceId: String) {
        ensureInitialized()
        val updated = squadDeviceIds() - deviceId
        preferences.edit()
            .putStringSet(KEY_SQUAD_IDS, updated)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "tactical_squad_membership"
        private const val KEY_SQUAD_IDS = "squad_device_ids"

        private const val LEGACY_BLE_PREFS_NAME = "itantra_ble_links"
        private const val LEGACY_SQUAD_IDS_KEY = "squad_device_ids"
    }
}
