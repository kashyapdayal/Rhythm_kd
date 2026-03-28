package chromahub.rhythm.app.shared.data.model

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * DataStore for saving per-DAC configurations.
 * Indexed by a unique device key, normally "${vendorId}_${productId}".
 */
val Context.dacProfileDataStore: DataStore<Preferences> by preferencesDataStore(name = "dac_profiles")

class DacProfileDataStore(private val context: Context) {

    /**
     * Get a Flow for whether a specific DAC requires software volume fallback.
     * @param deviceKey Unique string identifier for the DAC (e.g., "1234_5678" for vendorId_productId).
     * @return Flow emitting true if software volume is required, false if hardware volume works.
     */
    fun requiresSoftwareVolume(deviceKey: String): Flow<Boolean> {
        val key = booleanPreferencesKey("software_volume_$deviceKey")
        return context.dacProfileDataStore.data.map { prefs ->
            prefs[key] ?: false
        }
    }

    /**
     * Set whether a specific DAC requires software volume fallback.
     */
    suspend fun setRequiresSoftwareVolume(deviceKey: String, required: Boolean) {
        val key = booleanPreferencesKey("software_volume_$deviceKey")
        context.dacProfileDataStore.edit { prefs ->
            prefs[key] = required
        }
    }
}
