package kr.co.hconnect.samsung_sdk.callback

fun interface SensorDataCallback {
    fun onDataReady(data: ByteArray): Boolean
}
