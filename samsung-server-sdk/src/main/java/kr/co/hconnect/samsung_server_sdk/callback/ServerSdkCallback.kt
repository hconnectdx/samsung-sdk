package kr.co.hconnect.samsung_server_sdk.callback

import kr.co.hconnect.samsung_server_sdk.ble.MeasurementType
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
     * 워치가 `MEASUREMENT_TYPE:<VALUE>` 알림으로 측정 타입을 통보한 시점에 호출된다.
     *
     * - protobuf START 보다 텍스트가 먼저 도착하면 [onTrackingStarted] 와 거의 동시에 호출된다.
     * - 텍스트가 뒤늦게 도착해 측정 타입이 갱신된 경우에도 호출된다.
     *
     * @param sessionId 현재 세션 식별자
     * @param type      [MeasurementType.ECG] (일상) 또는 [MeasurementType.SLEEP] (수면)
     */
    fun onMeasurementStarted(sessionId: String, type: MeasurementType) {}

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

    /**
     * 세션 데이터 저장 경로가 결정되었을 때 호출된다.
     *
     * @param path CSV 파일이 저장되는 폴더 경로 (null이면 저장 종료)
     */
    fun onStoragePath(path: String?) {}

    /**
     * 수면 측정이 완전히 종료되어 `/poli/sleep/stop` 응답을 수신했을 때 호출된다.
     *
     * @param sessionId    종료된 세션 식별자 (yyyyMMdd_HHmmss)
     * @param sleepQuality 수면 품질 점수. 서버가 값을 내려주지 않으면 null.
     */
    fun onSleepFinished(sessionId: String, sleepQuality: Int?) {}

    /**
     * `/poli/day/protocol2-1` (일상/ECG) 전송 결과를 수신했을 때 호출된다.
     *
     * @param sessionId 전송 대상 세션 식별자
     * @param success   HTTP 2xx 여부
     * @param httpCode  HTTP 응답 코드 (네트워크 예외 시 -1)
     * @param body      서버 응답 바디 (예외 시 에러 메시지)
     */
    fun onProtocol2_1Result(
        sessionId: String,
        success: Boolean,
        httpCode: Int,
        body: String,
    ) {}

    /**
     * `/poli/sleep/protocol8-1` (수면) 전송 결과를 수신했을 때 호출된다.
     * 수면 세션은 1분 청크 단위로 여러 번 호출될 수 있다.
     *
     * @param sessionId 전송 대상 세션 식별자 (yyyyMMdd_HHmmss)
     * @param success   HTTP 2xx 여부
     * @param httpCode  HTTP 응답 코드 (네트워크 예외 시 -1)
     * @param body      서버 응답 바디 (예외 시 에러 메시지)
     */
    fun onProtocol8_1Result(
        sessionId: String,
        success: Boolean,
        httpCode: Int,
        body: String,
    ) {}

    /** SDK 내부 오류가 발생했을 때 호출된다. */
    fun onError(message: String)
}
