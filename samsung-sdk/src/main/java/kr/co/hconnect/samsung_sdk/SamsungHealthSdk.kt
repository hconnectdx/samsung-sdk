package kr.co.hconnect.samsung_sdk

import kr.co.hconnect.samsung_sdk.buffer.SensorBufferStorage
import kr.co.hconnect.samsung_sdk.callback.SensorDataCallback
import kr.co.hconnect.samsung_sdk.state.TrackingStateSender

/**
 * samsung-sdk 라이브러리의 진입점.
 * Application.onCreate()에서 초기화해야 합니다.
 *
 * ```
 * SamsungHealthSdk.init(object : SensorDataCallback {
 *     override fun onDataReady(data: ByteArray): Boolean {
 *         // 센서 데이터 수신 처리
 *         return true
 *     }
 * })
 * ```
 */
object SamsungHealthSdk {

    @Volatile
    private var initialized = false

    fun init(callback: SensorDataCallback) {
        TrackingStateSender.init(callback)
        SensorBufferStorage.initialize(callback)
        initialized = true
    }

    fun isInitialized(): Boolean = initialized
}
