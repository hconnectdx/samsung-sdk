package kr.co.hconnect.samsung_sdk.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import kr.co.hconnect.samsung_sdk.tracker.TrackingService
import kr.co.hconnect.samsung_sdk.util.Constants
import kr.co.hconnect.samsung_sdk.util.PreferencesUtil
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private const val TAG = "AlarmScheduler"

object AlarmScheduler {

    fun scheduleNext(context: Context, sensorType: IntArray) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            Log.w(TAG, "정확한 알람 권한 없음 — 일반 알람으로 대체")
            scheduleInexact(context, am, sensorType)
            return
        }

        val slotMinutes = runBlocking { PreferencesUtil.getAlarmSlotMinutes(context) }

        val now = System.currentTimeMillis()
        val triggerAt = CalSlotMinute.computeNextSlotUtc(now, slotMinutes)
        val cal = Calendar.getInstance().apply { timeInMillis = triggerAt }
        val slotMinute = cal.get(Calendar.MINUTE)

        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        Log.d(TAG, "[스케줄] slots=${slotMinutes.contentToString()} → 다음 알람: ${sdf.format(Date(triggerAt))} (slot=$slotMinute)")

        val pi = buildPendingIntent(context, slotMinute, sensorType)

        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } catch (e: SecurityException) {
            Log.w(TAG, "setExactAndAllowWhileIdle 실패 — 일반 알람으로 대체", e)
            scheduleInexact(context, am, sensorType)
        }
    }

    private fun scheduleInexact(context: Context, am: AlarmManager, sensorType: IntArray) {
        val slotMinutes = runBlocking { PreferencesUtil.getAlarmSlotMinutes(context) }

        val now = System.currentTimeMillis()
        val triggerAt = CalSlotMinute.computeNextSlotUtc(now, slotMinutes)
        val cal = Calendar.getInstance().apply { timeInMillis = triggerAt }
        val slotMinute = cal.get(Calendar.MINUTE)

        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        Log.d(TAG, "[스케줄-inexact] slots=${slotMinutes.contentToString()} → 다음 알람: ${sdf.format(Date(triggerAt))} (slot=$slotMinute)")

        val pi = buildPendingIntent(context, slotMinute, sensorType)
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
    }

    private fun buildPendingIntent(context: Context, slotMinute: Int, sensorType: IntArray): PendingIntent {
        val intent = Intent(context, ScheduleReceiver::class.java).apply {
            action = Constants.ACTION_SLOT_ALARM
            putExtra(TrackingService.EXTRA_SLOT_MINUTE, slotMinute)
            putExtra(TrackingService.EXTRA_SENSOR_TYPE, sensorType)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, Constants.ACTION_SLOT_ALARM_REQ_CODE, intent, flags)
    }

    fun cancel(context: Context) {
        val intent = Intent(context, ScheduleReceiver::class.java).apply {
            action = Constants.ACTION_SLOT_ALARM
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pi = PendingIntent.getBroadcast(context, Constants.ACTION_SLOT_ALARM_REQ_CODE, intent, flags)
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pi)
    }
}
