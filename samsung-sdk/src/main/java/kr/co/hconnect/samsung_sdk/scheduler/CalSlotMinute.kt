package kr.co.hconnect.samsung_sdk.scheduler

import kr.co.hconnect.samsung_sdk.util.Constants
import java.util.Calendar
import java.util.TimeZone

object CalSlotMinute {

    @JvmStatic
    fun computeNextSlotUtc(
        nowUtc: Long,
        slotMinutes: IntArray = Constants.PERIODIC_ALARM_MINUTE
    ): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = nowUtc
        }
        val curMin = cal.get(Calendar.MINUTE)
        val nextMin = slotMinutes.firstOrNull { it > curMin } ?: slotMinutes.first()

        if (nextMin <= curMin) cal.add(Calendar.HOUR_OF_DAY, 1)
        cal.set(Calendar.MINUTE, nextMin)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
