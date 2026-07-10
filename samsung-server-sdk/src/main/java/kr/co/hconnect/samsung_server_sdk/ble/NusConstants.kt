package kr.co.hconnect.samsung_server_sdk.ble

/** Nordic UART Service (NUS) UUID 상수. BioTracker 워치 앱과 반드시 일치해야 한다. */
internal object NusConstants {

    /** NUS 서비스 UUID */
    const val SERVICE_UUID = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"

    /** 워치 → 폰 (Notify) */
    const val CHAR_NOTIFY_UUID = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"

    /** 폰 → 워치 (Write) */
    const val CHAR_WRITE_UUID = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"

    /** Galaxy Watch 기기명 필터 키워드 */
    const val WATCH_NAME_FILTER = "Galaxy Watch"

    /**
     * 단일 Protobuf 메시지 최대 허용 크기.
     * 이보다 큰 길이 헤더를 수신하면 스트림 동기화가 깨진 것으로 판단한다.
     */
    const val MAX_PROTO_BYTES = 256 * 1024

    /** 폰 서비스가 시작됨을 워치에 알리는 명령 문자열 */
    const val CMD_SERVICE_RUNNING = "MOBILE_BLE_SERVICE_STATUS:RUNNING"

    /** 폰 서비스가 중지됨을 워치에 알리는 명령 문자열 */
    const val CMD_SERVICE_STOPPED = "MOBILE_BLE_SERVICE_STATUS:STOPPED"

    /** 워치에 수면측정 종료를 요청하는 명령 문자열 */
    const val CMD_MEASUREMENT_CONTROL_STOP_SLEEP = "MEASUREMENT_CONTROL:STOP_SLEEP"

    /**
     * 워치 청크 크기 프로브 접두 (peripheral SDK 1.0.1+).
     * 프레임: `PROBE:<seq>:<N>:` ASCII + 0xA5 패딩, 길이 헤더 없는 raw notify 1건.
     * 절단 도착해도 앞 20B 안에 접두·seq가 보존되므로 항상 식별 가능하다.
     */
    const val PROBE_PREFIX = "PROBE:"

    /** 프로브 응답 접두 — `PROBE_ACK:<seq>:<실제 수신 바이트수>` 를 RX write로 회신 */
    const val PROBE_ACK_PREFIX = "PROBE_ACK:"
}
