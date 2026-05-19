package kr.co.hconnect.samsung_server_sdk.session

import android.util.Log
import kr.co.hconnect.samsung_server_sdk.api.CsvBuilder
import kr.co.hconnect.samsung_server_sdk.api.HealthOnClient
import kr.co.hconnect.samsung_server_sdk.api.Protocol2_1API
import kr.co.hconnect.samsung_server_sdk.callback.ServerSdkCallback
import kr.co.hconnect.samsung_server_sdk.proto.SensorBufferProto
import kr.co.hconnect.samsung_server_sdk.proto.SensorSamples
import kr.co.hconnect.samsung_server_sdk.proto.SensorType
import kr.co.hconnect.samsung_server_sdk.proto.TrackingState
import kr.co.hconnect.samsung_server_sdk.write.DataWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Collections
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

    /**
     * 측정 종료(TrackingState.FINISH) 시 protocol2-1 으로 전송할
     * PPG / ECG 샘플 누적 버퍼.
     */
    private val ppgBuffer: MutableList<SensorSamples> =
        Collections.synchronizedList(ArrayList<SensorSamples>(8_192))
    private val ecgBuffer: MutableList<SensorSamples> =
        Collections.synchronizedList(ArrayList<SensorSamples>(8_192))

    /** API 전송용 비동기 스코프. WatchReceiverService 가 죽어도 전송은 완료되도록 SupervisorJob 사용. */
    private val apiScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        resetMeasurementBuffers()

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
        resetMeasurementBuffers()

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

        sendProtocol2_1Result(sessionId)
    }

    // ── 센서 샘플 라우팅 ──────────────────────────────────────────────────────

    private fun routeSamples(sessionId: String, samples: List<SensorSamples>) {
        samples.groupBy { it.sensorType }.forEach { (type: SensorType, list) ->
            dataWriter.appendBatch(type, list)
            callback.onSensorData(sessionId, type, list)
            collectForProtocol2_1(type, list)
        }
    }

    // ── PPG / ECG 누적 ───────────────────────────────────────────────────────

    private fun collectForProtocol2_1(type: SensorType, samples: List<SensorSamples>) {
        when (type) {
            SensorType.PPG_GREEN_25,
            SensorType.PPG_GREEN_100 -> ppgBuffer.addAll(samples)
            SensorType.ECG -> ecgBuffer.addAll(samples)
            else -> Unit
        }
    }

    private fun resetMeasurementBuffers() {
        synchronized(ppgBuffer) { ppgBuffer.clear() }
        synchronized(ecgBuffer) { ecgBuffer.clear() }
    }

    private fun snapshotPpg(): List<SensorSamples> = synchronized(ppgBuffer) { ppgBuffer.toList() }
    private fun snapshotEcg(): List<SensorSamples> = synchronized(ecgBuffer) { ecgBuffer.toList() }

    // ── protocol2-1 전송 ─────────────────────────────────────────────────────

    /**
     * 세션이 끝나는 시점에 누적된 PPG / ECG 데이터를 CSV 로 변환해
     * `POST /poli/day/protocol2-1` 으로 전송한다.
     *
     * HealthOnClient.init() 이 호출되지 않았거나 PPG/ECG 중 한쪽이라도
     * 비어 있으면 전송을 건너뛴다.
     */
    private fun sendProtocol2_1Result(sessionId: String) {
        val ppg = snapshotPpg()
        val ecg = snapshotEcg()

        // 버퍼는 다음 세션을 위해 비워둔다.
        resetMeasurementBuffers()

        if (ppg.isEmpty() || ecg.isEmpty()) {
            Log.w(
                TAG,
                "PPG/ECG 데이터 부족 — protocol2-1 전송 생략 " +
                        "(session=$sessionId, ppg=${ppg.size}, ecg=${ecg.size})"
            )
            return
        }

        apiScope.launch {
            try {
                val ppgCsv = CsvBuilder.buildPpgCsv(ppg)
                val ecgCsv = CsvBuilder.buildEcgCsv(ecg)

                Log.d(
                    TAG,
                    "protocol2-1 전송 시작 session=$sessionId " +
                            "ppgSamples=${ppg.size} ecgSamples=${ecg.size} " +
                            "ppgBytes=${ppgCsv.size} ecgBytes=${ecgCsv.size}"
                )

                val response = Protocol2_1API.requestPost(
                    ppgCsv = ppgCsv,
                    ecgCsv = ecgCsv,
                )

                if (response.success) {
                    Log.d(TAG, "protocol2-1 전송 성공 status=${response.httpCode}")
                } else {
                    Log.e(
                        TAG,
                        "protocol2-1 전송 실패 status=${response.httpCode} body=${response.body}"
                    )
                    callback.onError("protocol2-1 전송 실패 (status=${response.httpCode})")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "protocol2-1 전송 중 예외", t)
                callback.onError("protocol2-1 전송 중 예외: ${t.message}")
            }
        }
    }
}
