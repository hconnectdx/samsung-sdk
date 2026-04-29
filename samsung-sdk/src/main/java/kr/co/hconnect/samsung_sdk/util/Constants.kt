package kr.co.hconnect.samsung_sdk.util

object Constants {

    val PERIODIC_ALARM_MINUTE: IntArray = intArrayOf(1, 31)
    const val ACTION_SLOT_ALARM_REQ_CODE = 7001
    const val ACTION_SLOT_ALARM = "kr.co.hconnect.samsung_sdk.ACTION_SLOT_ALARM"

    const val DISCONNECT_NOTIFICATION_ID = 999
    const val DISCONNECT_NOTIFICATION_CHANNEL_ID = "disconnect_alert"
    const val DISCONNECT_NOTIFICATION_CHANNEL_NAME = "연결 끊김 알림"
    const val DISCONNECT_NOTIFICATION_CHANNEL_DESCRIPTION = "센서 연결이 끊어졌을 때 알림"
    const val VIBRATION_DURATION_MS = 2000L
    const val VIBRATION_AMPLITUDE = 255
}
