package kr.co.hconnect.samsung_sdk.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kr.co.hconnect.samsung_sdk.tracker.TrackingService
import kr.co.hconnect.samsung_sdk.util.Constants
import kr.co.hconnect.samsung_sdk.util.PreferencesUtil
import kotlinx.coroutines.runBlocking

class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Constants.ACTION_SLOT_ALARM) return

        val slotMinute = intent.getIntExtra(TrackingService.EXTRA_SLOT_MINUTE, -1)
        val durationMillis = runBlocking { PreferencesUtil.getMeasurementDuration(context) }
        val sensorType: IntArray? = intent.getIntArrayExtra(TrackingService.EXTRA_SENSOR_TYPE)

        val start = Intent(context, TrackingService::class.java)
            .setAction(TrackingService.ACTION_START)
            .putExtra(TrackingService.EXTRA_DURATION_MS, durationMillis)
            .putExtra(TrackingService.EXTRA_SLOT_MINUTE, slotMinute)
            .putExtra(TrackingService.EXTRA_SENSOR_TYPE, sensorType)

        ContextCompat.startForegroundService(context, start)
        AlarmScheduler.scheduleNext(context, sensorType ?: intArrayOf())
    }
}
