package kr.co.hconnect.samsung_sdk.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object TimeFormatUtil {

    private val dateFormatter by lazy {
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).apply {
            timeZone = TimeZone.getDefault()
        }
    }

    private val timeFormatter by lazy {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).apply {
            timeZone = TimeZone.getDefault()
        }
    }

    private val dateTimeFormatter by lazy {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).apply {
            timeZone = TimeZone.getDefault()
        }
    }

    private val displayFormatter by lazy {
        SimpleDateFormat("yyyy년 MM월 dd일 HH시 mm분", Locale.getDefault()).apply {
            timeZone = TimeZone.getDefault()
        }
    }

    fun formatDate(timestampMillis: Long): String {
        return dateFormatter.format(Date(timestampMillis))
    }

    fun formatTime(timestampMillis: Long): String {
        return timeFormatter.format(Date(timestampMillis))
    }

    fun formatDateTime(timestampMillis: Long): String {
        return dateTimeFormatter.format(Date(timestampMillis))
    }

    fun formatDisplay(timestampMillis: Long): String {
        return displayFormatter.format(Date(timestampMillis))
    }

    fun formatDate(date: Date): String = dateFormatter.format(date)
    fun formatTime(date: Date): String = timeFormatter.format(date)
    fun formatDisplay(date: Date): String = displayFormatter.format(date)
}
