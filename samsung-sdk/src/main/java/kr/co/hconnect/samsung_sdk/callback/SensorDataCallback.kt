package kr.co.hconnect.samsung_sdk.callback

/**
 * 센서 데이터 전송 콜백.
 * BioTracker의 BluetoothSender를 대체하여, 직렬화된 Protobuf 바이트를
 * 소비 앱이 원하는 방식(BLE, 네트워크 등)으로 전송할 수 있도록 한다.
 */
fun interface SensorDataCallback {
    /**
     * 직렬화된 SensorBufferProto 바이트가 전송 준비되었을 때 호출된다.
     *
     * @param data Protobuf로 직렬화된 바이트 배열
     * @return 전송 성공 여부
     */
    fun onDataReady(data: ByteArray): Boolean
}
