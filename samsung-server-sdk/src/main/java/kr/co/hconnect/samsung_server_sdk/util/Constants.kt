package kr.co.hconnect.samsung_server_sdk.util

internal object Constants {

    /** WatchReceiverService 시작 Action */
    const val ACTION_START_SERVICE = "kr.co.hconnect.samsung_server_sdk.ACTION_START_SERVICE"

    /** WatchReceiverService 정지 Action */
    const val ACTION_STOP_SERVICE = "kr.co.hconnect.samsung_server_sdk.ACTION_STOP_SERVICE"

    /** 워치에 수면측정 종료 명령 전송 Action */
    const val ACTION_STOP_SLEEP_MEASUREMENT = "kr.co.hconnect.samsung_server_sdk.ACTION_STOP_SLEEP_MEASUREMENT"

    /** 포그라운드 알림 채널 ID */
    const val NOTIFICATION_CHANNEL_ID = "samsung_server_sdk_channel"

    /** 포그라운드 알림 채널 이름 */
    const val NOTIFICATION_CHANNEL_NAME = "Samsung Server SDK"

    /** 포그라운드 알림 ID */
    const val NOTIFICATION_ID = 9002
}
