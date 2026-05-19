package kr.co.hconnect.samsung_server_sdk.ble

/**
 * 워치가 BLE 로 보내는 측정 타입 알림 메시지의 분류.
 *
 * 워치는 측정 시작 / 종료 시점에 다음 형식의 UTF-8 텍스트를 BLE 로 전송한다.
 *  - `"MEASUREMENT_TYPE:ECG"`   — 일상(ECG) 측정 시작
 *  - `"MEASUREMENT_TYPE:SLEEP"` — 수면 측정 시작
 *  - `"MEASUREMENT_TYPE:STOP"`  — 측정 종료
 *
 * 일반 센서 데이터(`SensorBufferProto`) 는 [4B big-endian length][protobuf bytes]
 * 형태의 바이너리이므로, 위 형식의 UTF-8 텍스트인지 여부로 메시지를 구분한다.
 */
internal enum class MeasurementType(val rawValue: String) {
    ECG("ECG"),
    SLEEP("SLEEP"),
    STOP("STOP");

    companion object {
        const val MESSAGE_PREFIX = "MEASUREMENT_TYPE:"

        /**
         * 들어온 바이트가 `MEASUREMENT_TYPE:<VALUE>` 형식이면 해당 enum 으로,
         * 그렇지 않으면 `null` 을 반환한다.
         *
         * 너무 짧거나 ASCII 가 아닌 패킷, prefix 가 다른 패킷은 모두 `null`.
         */
        fun parseOrNull(bytes: ByteArray): MeasurementType? {
            if (bytes.size < MESSAGE_PREFIX.length || bytes.size > 64) return null

            // prefix 만 먼저 확인 (전체 디코딩 비용 줄이기 위해)
            for (i in MESSAGE_PREFIX.indices) {
                if (bytes[i].toInt().toChar() != MESSAGE_PREFIX[i]) return null
            }

            val text = try {
                String(bytes, Charsets.UTF_8).trim()
            } catch (_: Throwable) {
                return null
            }
            if (!text.startsWith(MESSAGE_PREFIX)) return null

            val value = text.substring(MESSAGE_PREFIX.length).trim().uppercase()
            return entries.firstOrNull { it.rawValue == value }
        }
    }
}
