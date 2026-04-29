package kr.co.hconnect.samsung_server_sdk.session

import android.util.Log
import kr.co.hconnect.samsung_server_sdk.callback.ServerSdkCallback
import kr.co.hconnect.samsung_server_sdk.proto.SensorBufferProto
import kr.co.hconnect.samsung_server_sdk.proto.SensorSamples
import kr.co.hconnect.samsung_server_sdk.proto.SensorType
import kr.co.hconnect.samsung_server_sdk.proto.TrackingState
import kr.co.hconnect.samsung_server_sdk.write.DataWriter
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private const val TAG = "SessionManager"

/**
 * 워치에서 수신한 [SensorBufferProto]를 처리하여 세션 상태를 관리하고
 * 파싱된 이벤트를 [ServerSdkCallback]으로 전달하며,
 * [DataWriter]를 통해 센서 데이터를 CSV로 저장한다.
 */
internal class SessionManager(
    private val callback: ServerSdkCallback,
    private val dataWriter: DataWriter,
) {

    @Volatile var currentSessionId: String? = null
        private set

    @Volatile var isRecording: Boolean = false
        private set

    fun process(proto: SensorBufferProto) {
        val metadata = if (proto.hasMetadata()) proto.metadata else null
        val state = metadata?.trackingState ?: TrackingState.NONE

        Log.d(TAG, "samples=${proto.samplesCount} state=$state isRec=$isRecording")

        when (state) {
            TrackingState.START -> {
                if (metadata != null) {
                    if (!metadata.hasPeriodStartMinute()) {
                        startOnDemandSession(metadata.onDemandStartTimestamp)
                    } else {
                        startPeriodicSession(metadata.periodStartMinute)
                    }
                }
            }

            TrackingState.FINISH -> {
                finishSession()
            }

            else -> Unit
        }

        if (proto.samplesCount > 0 && isRecording) {
            val sessionId = currentSessionId ?: return
            routeSamples(sessionId, proto.samplesList)
        }
    }

    // ── 세션 시작 ─────────────────────────────────────────────────────────────

    private fun startPeriodicSession(slotMinute: Int) {
        if (isRecording) finishSession()

        val cal = Calendar.getInstance()
        val datePart = SimpleDateFormat("yyyyMMdd", Locale.US).format(cal.time)
        val hourPart = String.format("%02d", cal.get(Calendar.HOUR_OF_DAY))
        val minPart = String.format("%02d", slotMinute)
        val sessionId = "${datePart}_${hourPart}${minPart}"

        currentSessionId = sessionId
        isRecording = true

        dataWriter.beginSession(sessionId)
        callback.onStoragePath(dataWriter.currentStoragePath)

        Log.d(TAG, "주기 세션 시작: $sessionId")
        callback.onTrackingStarted(sessionId)
    }

    private fun startOnDemandSession(timestamp: Long) {
        if (isRecording) finishSession()

        val sessionId = "on_demand_$timestamp"
        currentSessionId = sessionId
        isRecording = true

        dataWriter.beginSession(sessionId)
        callback.onStoragePath(dataWriter.currentStoragePath)

        Log.d(TAG, "즉시 세션 시작: $sessionId")
        callback.onTrackingStarted(sessionId)
    }

    // ── 세션 종료 ─────────────────────────────────────────────────────────────

    fun finishSession() {
        val sessionId = currentSessionId ?: return
        Log.d(TAG, "세션 종료: $sessionId")
        isRecording = false
        currentSessionId = null
        dataWriter.closeAll()
        callback.onStoragePath(null)
        callback.onTrackingFinished(sessionId)
    }

    // ── 센서 샘플 라우팅 ──────────────────────────────────────────────────────

    private fun routeSamples(sessionId: String, samples: List<SensorSamples>) {
        samples.groupBy { it.sensorType }.forEach { (type: SensorType, list) ->
            dataWriter.appendBatch(type, list)
            callback.onSensorData(sessionId, type, list)
        }
    }
}
