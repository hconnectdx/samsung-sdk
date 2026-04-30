package kr.co.hconnect.samsung_sdk.util

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.sdkDataStore by preferencesDataStore(name = "samsung_sdk_preferences")

object PreferencesUtil {
    private val HAS_RUN_BEFORE = booleanPreferencesKey("has_run_before")
    private val MEASUREMENT_DURATION_MS = longPreferencesKey("measurement_duration_ms")
    private val MEASUREMENT_TYPE = stringPreferencesKey("measurement_type")
    private val ALARM_SLOT_MINUTES = stringPreferencesKey("alarm_slot_minutes")
    private val ALARM_SENSOR_TYPES = stringPreferencesKey("alarm_sensor_types")

    suspend fun setHasRunBefore(context: Context, value: Boolean) {
        context.sdkDataStore.edit { it[HAS_RUN_BEFORE] = value }
    }

    suspend fun getHasRunBefore(context: Context): Boolean =
        context.sdkDataStore.data.map { it[HAS_RUN_BEFORE] ?: false }.first()

    suspend fun getMeasurementDuration(context: Context): Long =
        context.sdkDataStore.data.map { it[MEASUREMENT_DURATION_MS] ?: 0 }.first()

    suspend fun setMeasurementDuration(context: Context, durationMillis: Long) {
        context.sdkDataStore.edit { it[MEASUREMENT_DURATION_MS] = durationMillis }
    }

    suspend fun setMeasurementType(context: Context, type: String) {
        context.sdkDataStore.edit { it[MEASUREMENT_TYPE] = type }
    }

    suspend fun getMeasurementType(context: Context): String? =
        context.sdkDataStore.data.map { it[MEASUREMENT_TYPE] }.first()

    // ── 주기 알람 슬롯 분 ──

    suspend fun setAlarmSlotMinutes(context: Context, minutes: IntArray) {
        val value = minutes.joinToString(",")
        context.sdkDataStore.edit { it[ALARM_SLOT_MINUTES] = value }
    }

    suspend fun getAlarmSlotMinutes(context: Context): IntArray {
        val raw = context.sdkDataStore.data.map { it[ALARM_SLOT_MINUTES] }.first()
            ?: return Constants.DEFAULT_ALARM_MINUTES
        return try {
            raw.split(",").map { it.trim().toInt() }.toIntArray()
        } catch (_: Exception) {
            Constants.DEFAULT_ALARM_MINUTES
        }
    }

    // ── 주기 알람 센서 타입 ──

    suspend fun setAlarmSensorTypes(context: Context, sensorTypes: IntArray) {
        val value = sensorTypes.joinToString(",")
        context.sdkDataStore.edit { it[ALARM_SENSOR_TYPES] = value }
    }

    suspend fun getAlarmSensorTypes(context: Context): IntArray? {
        val raw = context.sdkDataStore.data.map { it[ALARM_SENSOR_TYPES] }.first()
            ?: return null
        return try {
            raw.split(",").map { it.trim().toInt() }.toIntArray()
        } catch (_: Exception) {
            null
        }
    }
}
