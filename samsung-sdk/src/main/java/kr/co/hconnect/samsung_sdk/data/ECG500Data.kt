package kr.co.hconnect.samsung_sdk.data

data class ECG500Data(
    val timestamp: Long,
    val mv: Float,
    val leadOff: Int,
    val maxThresholdMV: Float,
    val minThresholdMV: Float,
    val sequence: Int
)
