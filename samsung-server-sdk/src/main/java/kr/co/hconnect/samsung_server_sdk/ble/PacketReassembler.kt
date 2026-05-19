package kr.co.hconnect.samsung_server_sdk.ble

import android.util.Log
import com.google.protobuf.InvalidProtocolBufferException
import kr.co.hconnect.samsung_server_sdk.proto.SensorBufferProto
import java.io.ByteArrayOutputStream

private const val TAG = "PacketReassembler"

/**
 * BLE Notification으로 분할 수신된 데이터를 완성된 메시지로 조립한다.
 *
 * 워치(BioTracker)의 프레이밍 형식:
 *   [ 4바이트 big-endian 전체 길이 ] + [ 페이로드 ]
 *
 * 페이로드는 두 가지 종류:
 *  1) `SensorBufferProto` 바이너리 — 일반 센서 데이터
 *  2) `MEASUREMENT_TYPE:<VALUE>` UTF-8 텍스트 — 측정 시작/종료 알림
 *
 * BLE MTU 크기에 따라 여러 청크로 분할되어 수신되므로,
 * 완성된 메시지가 될 때까지 내부 버퍼에 누적한다.
 *
 * 스레드 안전성: 호출자(WatchReceiverService)가 코루틴 Mutex로 직렬화하므로
 * 이 클래스 자체는 동기화를 보장하지 않는다.
 */
internal class PacketReassembler(
    private val onMessage: (SensorBufferProto) -> Unit,
    private val onMeasurementType: (MeasurementType) -> Unit = {},
) {

    private val buffer = ByteArrayOutputStream()

    /** BLE Notification으로 수신된 원시 바이트를 공급한다. */
    fun feed(data: ByteArray) {
        val hex = data.take(20).joinToString(" ") { "%02X".format(it) }
        val ascii = data.take(32).map { b ->
            val c = b.toInt() and 0xFF
            if (c in 0x20..0x7E) c.toChar() else '·'
        }.joinToString("")
        Log.d(TAG, "[raw] ${data.size}B hex=$hex ascii=\"$ascii\" bufSize=${buffer.size()}")

        // ── 텍스트 알림(MEASUREMENT_TYPE:*) 우선 판정 ─────────────────────────
        // 워치는 측정 시작/종료 시 단독 UTF-8 텍스트 패킷을 보낸다.
        // "MEASUREMENT_TYPE:" prefix 가 매우 specific 하므로 protobuf 스트림과 우연 충돌할 일은 거의 없다.
        // 따라서 buffer 상태와 무관하게 항상 텍스트 검사를 우선한다 — 그래야
        // protobuf 조립이 미완료인 상태에서도 SLEEP/STOP 알림을 놓치지 않는다.
        MeasurementType.parseOrNull(data)?.let { type ->
            Log.d(TAG, "[text] MEASUREMENT_TYPE:${type.rawValue}  (bufSize=${buffer.size()})")
            onMeasurementType(type)
            return
        }

        buffer.write(data)
        process()
    }

    /** 버퍼와 조립 상태를 초기화한다. */
    fun reset() {
        buffer.reset()
        Log.d(TAG, "버퍼 초기화")
    }

    // ── 내부 처리 ─────────────────────────────────────────────────────────────

    private fun process() {
        val buf = buffer.toByteArray()
        var offset = 0

        while (offset + 4 <= buf.size) {
            val messageLength = readBigEndianInt(buf, offset)

            if (messageLength <= 0 || messageLength > NusConstants.MAX_PROTO_BYTES) {
                Log.w(TAG, "비정상 길이=$messageLength offset=$offset → 1바이트 스킵")
                offset++
                continue
            }

            val dataStart = offset + 4
            val dataEnd = dataStart + messageLength

            if (dataEnd > buf.size) {
                Log.d(TAG, "누적 대기 need=${messageLength}B 현재=${buf.size - dataStart}B")
                break
            }

            val messageBytes = buf.copyOfRange(dataStart, dataEnd)

            // ── 1) 텍스트 알림(MEASUREMENT_TYPE:*) 우선 검사 ───────────────────
            // 워치는 텍스트도 [length][UTF-8 bytes] 프레임으로 보낸다.
            val measurementType = MeasurementType.parseOrNull(messageBytes)
            if (measurementType != null) {
                Log.d(
                    TAG,
                    "✓ 텍스트 메시지 len=${messageLength}B " +
                            "type=MEASUREMENT_TYPE:${measurementType.rawValue}"
                )
                onMeasurementType(measurementType)
                offset = dataEnd
                continue
            }

            // ── 2) protobuf 메시지 파싱 ────────────────────────────────────────
            try {
                val proto = SensorBufferProto.parseFrom(messageBytes)
                Log.d(TAG, "✓ 파싱 성공 len=${messageLength}B samples=${proto.samplesCount}")
                onMessage(proto)
                offset = dataEnd
            } catch (e: InvalidProtocolBufferException) {
                Log.w(TAG, "파싱 실패(len=${messageLength}B) → 메시지 전체 스킵, 재동기화")
                offset = dataEnd
            }
        }

        // 미완성 데이터를 버퍼에 보존
        buffer.reset()
        val remaining = buf.size - offset
        if (remaining > 0) {
            if (remaining > NusConstants.MAX_PROTO_BYTES) {
                // 버퍼 초과 → 앞 절반 폐기하여 빠르게 재동기화
                val drop = remaining / 2
                Log.w(TAG, "버퍼 초과(${remaining}B) → 앞 ${drop}B 폐기 후 재동기화")
                buffer.write(buf, offset + drop, remaining - drop)
            } else {
                buffer.write(buf, offset, remaining)
            }
        }
    }

    private fun readBigEndianInt(buf: ByteArray, offset: Int): Int =
        ((buf[offset].toInt()     and 0xFF) shl 24) or
        ((buf[offset + 1].toInt() and 0xFF) shl 16) or
        ((buf[offset + 2].toInt() and 0xFF) shl  8) or
        ((buf[offset + 3].toInt() and 0xFF))
}
