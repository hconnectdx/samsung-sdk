package kr.co.hconnect.samsung_server_sdk

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi
import kr.co.hconnect.samsung_server_sdk.callback.ServerSdkCallback
import kr.co.hconnect.samsung_server_sdk.service.WatchReceiverService
import kr.co.hconnect.samsung_server_sdk.util.Constants

/**
 * samsung-server-sdk 진입점.
 *
 * ## 사용 순서
 * 1. [init] — 이벤트 콜백 등록 (서비스 시작 전에 반드시 호출)
 * 2. [start] — Galaxy Watch BLE 연결 및 데이터 수신 시작
 * 3. [stop]  — 서비스 중지 및 BLE 연결 해제
 *
 * ## 예시
 * ```kotlin
 * SamsungServerSdk.init(object : ServerSdkCallback {
 *     override fun onConnected(deviceName: String) { ... }
 *     override fun onDisconnected() { ... }
 *     override fun onTrackingStarted(sessionId: String) { ... }
 *     override fun onTrackingFinished(sessionId: String) { ... }
 *     override fun onSensorData(sessionId, sensorType, samples) { ... }
 *     override fun onError(message: String) { ... }
 * })
 *
 * SamsungServerSdk.start(context)
 * // ...
 * SamsungServerSdk.stop(context)
 * ```
 */
object SamsungServerSdk {

    @Volatile private var callback: ServerSdkCallback? = null
    @Volatile private var running: Boolean = false

    /**
     * SDK를 초기화하고 이벤트 콜백을 등록한다.
     * [start] 호출 전에 반드시 한 번 호출해야 한다.
     */
    fun init(callback: ServerSdkCallback) {
        this.callback = callback
    }

    /**
     * Galaxy Watch 탐색 후 BLE 연결 및 데이터 수신을 시작한다.
     *
     * [WatchReceiverService]를 포그라운드 서비스로 실행한다.
     * [init]이 호출되지 않은 상태라면 예외를 던진다.
     *
     * @throws IllegalStateException [init] 미호출 시
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun start(context: Context) {
        checkNotNull(callback) {
            "SamsungServerSdk.init()이 호출되지 않았습니다. start() 전에 init()을 호출하세요."
        }
        val intent = Intent(context, WatchReceiverService::class.java)
            .setAction(Constants.ACTION_START_SERVICE)
        context.startForegroundService(intent)
        running = true
    }

    /**
     * BLE 연결을 해제하고 [WatchReceiverService]를 중지한다.
     */
    fun stop(context: Context) {
        val intent = Intent(context, WatchReceiverService::class.java)
            .setAction(Constants.ACTION_STOP_SERVICE)
        context.startService(intent)
        running = false
    }

    /** SDK가 실행 중인지 여부. */
    fun isRunning(): Boolean = running

    /** 내부용: 서비스에서 콜백을 참조하기 위해 사용. */
    internal fun getCallback(): ServerSdkCallback? = callback
}
