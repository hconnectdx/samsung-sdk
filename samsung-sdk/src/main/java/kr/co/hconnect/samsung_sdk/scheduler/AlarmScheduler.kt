package kr.co.hconnect.samsung_sdk.scheduler

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import kr.co.hconnect.samsung_sdk.tracker.TrackingService
import kr.co.hconnect.samsung_sdk.util.Constants
import java.util.Calendar

object AlarmScheduler {

    @SuppressLint("ScheduleExactAlarm")
    fun scheduleNext(context: Context, sensorType: IntArray) {
        val now = System.currentTimeMillis()
        val triggerAt = CalSlotMinute.computeNextSlotUtc(now, Constants.PERIODIC_ALARM_MINUTE)
        val cal = Calendar.getInstance().apply { timeInMillis = triggerAt }
        val slotMinute = cal.get(Calendar.MINUTE)

        val intent = Intent(context, ScheduleReceiver::class.java).apply {
            action = Constants.ACTION_SLOT_ALARM
            putExtra(TrackingService.EXTRA_SLOT_MINUTE, slotMinute)
            putExtra(TrackingService.EXTRA_SENSOR_TYPE, sensorType)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pi = PendingIntent.getBroadcast(context, Constants.ACTION_SLOT_ALARM_REQ_CODE, intent, flags)

        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
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
