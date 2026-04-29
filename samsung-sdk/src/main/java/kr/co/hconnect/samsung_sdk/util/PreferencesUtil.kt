package kr.co.hconnect.samsung_sdk.util

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.dataStore by preferencesDataStore(name = "samsung_sdk_preferences")

object PreferencesUtil {
    private val HAS_RUN_BEFORE = booleanPreferencesKey("has_run_before")
    private val MEASUREMENT_DURATION_MS = longPreferencesKey("measurement_duration_ms")
    private val MEASUREMENT_TYPE = stringPreferencesKey("measurement_type")

    suspend fun setHasRunBefore(context: Context, value: Boolean) {
        context.dataStore.edit { it[HAS_RUN_BEFORE] = value }
    }

    suspend fun getHasRunBefore(context: Context): Boolean =
        context.dataStore.data.map { it[HAS_RUN_BEFORE] ?: false }.first()

    suspend fun getMeasurementDuration(context: Context): Long =
        context.dataStore.data.map { it[MEASUREMENT_DURATION_MS] ?: 0 }.first()

    suspend fun setMeasurementDuration(context: Context, durationMillis: Long) {
        context.dataStore.edit { it[MEASUREMENT_DURATION_MS] = durationMillis }
    }

    suspend fun setMeasurementType(context: Context, type: String) {
        context.dataStore.edit { it[MEASUREMENT_TYPE] = type }
    }

    suspend fun getMeasurementType(context: Context): String? =
        context.dataStore.data.map { it[MEASUREMENT_TYPE] }.first()
}
