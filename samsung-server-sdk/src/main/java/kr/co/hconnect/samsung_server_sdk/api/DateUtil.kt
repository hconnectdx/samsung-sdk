package kr.co.hconnect.samsung_server_sdk.api

import android.os.Build
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale

/**
 * 측정 결과 전송 시 reqDate(yyyyMMddHHmmss) 포맷팅 유틸.
 */
internal object DateUtil {

    private const val PATTERN = "yyyyMMddHHmmss"

    fun getCurrentDateTime(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            LocalDateTime.now().format(DateTimeFormatter.ofPattern(PATTERN))
        } else {
            SimpleDateFormat(PATTERN, Locale.getDefault()).format(Calendar.getInstance().time)
        }
    }
}
