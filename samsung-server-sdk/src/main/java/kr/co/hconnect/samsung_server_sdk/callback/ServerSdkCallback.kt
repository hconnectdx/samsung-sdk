package kr.co.hconnect.samsung_server_sdk.callback

import kr.co.hconnect.samsung_server_sdk.proto.SensorSamples
import kr.co.hconnect.samsung_server_sdk.proto.SensorType

/**
 * samsung-server-sdk 이벤트 콜백 인터페이스.
 *
 * WatchReceiverService가 워치로부터 받은 데이터를 파싱한 결과를
 * 소비 앱에 전달하기 위해 사용된다.
 */
interface ServerSdkCallback {

    /** Galaxy Watch와 BLE 연결이 수립되었을 때 호출된다. */
    fun onConnected(deviceName: String)

    /** Galaxy Watch와 BLE 연결이 끊어졌을 때 호출된다. */
    fun onDisconnected()

    /**
     * 워치에서 측정 시작(TrackingState.START) 신호를 수신했을 때 호출된다.
     *
     * @param sessionId 세션 식별자
     *   - 주기 측정: "yyyyMMdd_HHmm"
     *   - 즉시 측정: "on_demand_<timestamp>"
     */
    fun onTrackingStarted(sessionId: String)

    /**
     * 워치에서 측정 종료(TrackingState.FINISH) 신호를 수신했을 때 호출된다.
     *
     * @param sessionId 종료되는 세션 식별자
     */
    fun onTrackingFinished(sessionId: String)

    /**
     * 센서 샘플 배치가 수신되었을 때 호출된다.
     * isRecording 상태일 때만 호출되며, 센서 타입별로 묶여서 전달된다.
     *
     * @param sessionId 현재 세션 식별자
     * @param sensorType ACC, ECG, PPG_GREEN_25, PPG_GREEN_100 중 하나
     * @param samples 해당 타입의 샘플 리스트
     */
    fun onSensorData(
        sessionId: String,
        sensorType: SensorType,
        samples: List<SensorSamples>
    )

    /** SDK 내부 오류가 발생했을 때 호출된다. */
    fun onError(message: String)
}
