package kr.co.hconnect.samsung_server_sdk.session

import android.util.Log
import kr.co.hconnect.samsung_server_sdk.api.CsvBuilder
import kr.co.hconnect.samsung_server_sdk.api.HealthOnClient
import kr.co.hconnect.samsung_server_sdk.api.Protocol2_1API
import kr.co.hconnect.samsung_server_sdk.api.Protocol8_1API
import kr.co.hconnect.samsung_server_sdk.api.SleepStartAPI
import kr.co.hconnect.samsung_server_sdk.ble.MeasurementType
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
import java.util.Date
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
     * 현재 진행 중인 측정의 타입.
     * 워치의 `MEASUREMENT_TYPE:ECG / SLEEP` 텍스트 알림으로 결정되며,
     * 측정 종료(STOP / FINISH) 시 어떤 protocol(2-1 vs 8-1) 로 전송할지를 결정한다.
     */
    @Volatile private var currentMeasurementType: MeasurementType? = null

    /**
     * 측정 종료 시 API 전송용으로 누적되는 샘플 버퍼.
     *  - protocol2-1(ECG/일상): ppgBuffer + ecgBuffer
     *  - protocol8-1(SLEEP/수면): ppgBuffer + accBuffer(IMU)
     */
    private val ppgBuffer: MutableList<SensorSamples> =
        Collections.synchronizedList(ArrayList<SensorSamples>(8_192))
    private val ecgBuffer: MutableList<SensorSamples> =
        Collections.synchronizedList(ArrayList<SensorSamples>(8_192))
    private val accBuffer: MutableList<SensorSamples> =
        Collections.synchronizedList(ArrayList<SensorSamples>(8_192))

    /** API 전송용 비동기 스코프. WatchReceiverService 가 죽어도 전송은 완료되도록 SupervisorJob 사용. */
    private val apiScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun process(proto: SensorBufferProto) {
        val metadata = if (proto.hasMetadata()) proto.metadata else null
        val state = metadata?.trackingState ?: TrackingState.NONE

        Log.d(
            TAG,
            "samples=${proto.samplesCount} state=$state " +
                    "type=$currentMeasurementType isRec=$isRecording"
        )

        when (state) {
            TrackingState.START -> {
                if (metadata != null && !isRecording) {
                    // 텍스트 MEASUREMENT_TYPE 알림이 누락된 경우의 백워드 호환
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

    /**
     * 워치가 BLE 로 `MEASUREMENT_TYPE:<VALUE>` 텍스트 알림을 보냈을 때 호출된다.
     *
     *  - [MeasurementType.ECG]   → 일상 측정 세션 시작 (protocol2-1 대상)
     *  - [MeasurementType.SLEEP] → 수면 측정 세션 시작 (protocol8-1 대상)
     *  - [MeasurementType.STOP]  → 현재 세션 종료 + 측정 타입별 API 전송
     */
    fun onMeasurementType(type: MeasurementType) {
        Log.d(TAG, "▶ onMeasurementType=$type currentType=$currentMeasurementType isRec=$isRecording")
        when (type) {
            MeasurementType.ECG, MeasurementType.SLEEP -> {
                if (isRecording) {
                    // protobuf START 메타데이터가 텍스트보다 먼저 도착해서 세션이 이미 시작된 경우 —
                    // 세션을 종료/재시작하지 말고 측정 타입만 갱신한다(중간 버퍼 손실 방지).
                    if (currentMeasurementType != type) {
                        Log.w(
                            TAG,
                            "이미 기록 중 — 측정 타입 갱신: $currentMeasurementType → $type " +
                                    "(session=$currentSessionId)"
                        )
                        currentMeasurementType = type
                    }
                    return
                }
                startMeasurementSession(type)
            }
            MeasurementType.STOP -> {
                if (!isRecording) {
                    Log.w(TAG, "STOP 수신했으나 활성 세션 없음 — 무시")
                    return
                }
                finishSession()
            }
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
        // 측정 타입은 워치의 `MEASUREMENT_TYPE:*` 텍스트 알림으로 결정된다.
        // 텍스트가 protobuf START 보다 늦게 도착하는 경우 onMeasurementType() 에서 갱신된다.
        resetMeasurementBuffers()

        dataWriter.beginSession(sessionId)
        callback.onStoragePath(dataWriter.currentStoragePath)

        Log.d(TAG, "즉시 세션 시작: $sessionId (type=$currentMeasurementType / 미정이면 추후 갱신)")
        callback.onTrackingStarted(sessionId)
    }

    /**
     * 워치의 `MEASUREMENT_TYPE:ECG / SLEEP` 알림 수신 시 호출되는 세션 시작 경로.
     *
     * - sessionId 는 protocol8-1 스펙(15자: yyyyMMdd_HHmmss) 에 맞춰 생성한다.
     * - 일상(ECG) / 수면(SLEEP) 측정 모두 동일 포맷을 사용한다.
     */
    private fun startMeasurementSession(type: MeasurementType) {
        val sessionId = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        currentSessionId = sessionId
        currentMeasurementType = type
        isRecording = true
        resetMeasurementBuffers()

        dataWriter.beginSession(sessionId)
        callback.onStoragePath(dataWriter.currentStoragePath)

        Log.d(TAG, "측정 세션 시작: type=$type sessionId=$sessionId")
        callback.onTrackingStarted(sessionId)

        if (type == MeasurementType.SLEEP) {
            apiScope.launch { notifySleepStart() }
        }
    }

    /** 수면 측정 시작 시 서버에 start 알림을 전송한다. */
    private fun notifySleepStart() {
        try {
            val response = SleepStartAPI.requestPost()
            if (response.success) {
                Log.d(TAG, "sleep/start 전송 성공 status=${response.httpCode}")
            } else {
                Log.e(TAG, "sleep/start 전송 실패 status=${response.httpCode} body=${response.body}")
                callback.onError("sleep/start 전송 실패 (status=${response.httpCode})")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "sleep/start 전송 중 예외", t)
            callback.onError("sleep/start 전송 중 예외: ${t.message}")
        }
    }

    // ── 세션 종료 ─────────────────────────────────────────────────────────────

    fun finishSession() {
        val sessionId = currentSessionId ?: return
        val type = currentMeasurementType

        Log.d(TAG, "세션 종료: $sessionId (type=$type)")
        isRecording = false
        currentSessionId = null
        dataWriter.closeAll()
        callback.onStoragePath(null)
        callback.onTrackingFinished(sessionId)

        when (type) {
            MeasurementType.SLEEP -> sendProtocol8_1Result(sessionId)
            MeasurementType.ECG -> sendProtocol2_1Result(sessionId)
            else -> {
                // 워치의 MEASUREMENT_TYPE:* 알림을 한 번도 받지 못한 레거시/예외 경로.
                // 데이터가 어느 쪽인지 알 수 없으므로 일상(protocol2-1) 로 fallback 한다.
                Log.w(
                    TAG,
                    "currentMeasurementType=null — protocol2-1(일상)로 fallback 합니다. session=$sessionId"
                )
                sendProtocol2_1Result(sessionId)
            }
        }
        currentMeasurementType = null
    }

    // ── 센서 샘플 라우팅 ──────────────────────────────────────────────────────

    private fun routeSamples(sessionId: String, samples: List<SensorSamples>) {
        samples.groupBy { it.sensorType }.forEach { (type: SensorType, list) ->
            dataWriter.appendBatch(type, list)
            callback.onSensorData(sessionId, type, list)
            collectForApi(type, list)
        }
    }

    // ── 측정 결과 누적 ────────────────────────────────────────────────────────

    private fun collectForApi(type: SensorType, samples: List<SensorSamples>) {
        when (type) {
            SensorType.PPG_GREEN_25,
            SensorType.PPG_GREEN_100 -> ppgBuffer.addAll(samples)
            SensorType.ECG -> ecgBuffer.addAll(samples)
            SensorType.ACC -> accBuffer.addAll(samples)
            else -> Unit
        }
    }

    private fun resetMeasurementBuffers() {
        synchronized(ppgBuffer) { ppgBuffer.clear() }
        synchronized(ecgBuffer) { ecgBuffer.clear() }
        synchronized(accBuffer) { accBuffer.clear() }
    }

    private fun snapshotPpg(): List<SensorSamples> = synchronized(ppgBuffer) { ppgBuffer.toList() }
    private fun snapshotEcg(): List<SensorSamples> = synchronized(ecgBuffer) { ecgBuffer.toList() }
    private fun snapshotAcc(): List<SensorSamples> = synchronized(accBuffer) { accBuffer.toList() }

    // ── protocol2-1 전송 (일상/ECG) ──────────────────────────────────────────

    /**
     * 일상(ECG) 세션 종료 시 누적된 PPG / ECG 데이터를 CSV 로 변환해
     * `POST /poli/day/protocol2-1` 으로 전송한다.
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

    // ── protocol8-1 전송 (수면/SLEEP) ────────────────────────────────────────

    /**
     * 수면(SLEEP) 세션 종료 시 누적된 PPG / IMU(ACC) 데이터를 CSV 로 변환해
     * `POST /poli/sleep/protocol8-1` 으로 전송한다.
     *
     * sessionId 는 protocol8-1 스펙(`yyyyMMdd_HHmmss`, 15자) 을 따른다.
     */
    private fun sendProtocol8_1Result(sessionId: String) {
        val ppg = snapshotPpg()
        val acc = snapshotAcc()

        resetMeasurementBuffers()

        if (sessionId.length != 15) {
            Log.e(
                TAG,
                "protocol8-1 sessionId 형식 불일치 — 전송 생략 (session=$sessionId, 길이=${sessionId.length})"
            )
            callback.onError("protocol8-1 sessionId 형식 오류: $sessionId")
            return
        }

        if (ppg.isEmpty() || acc.isEmpty()) {
            Log.w(
                TAG,
                "PPG/IMU 데이터 부족 — protocol8-1 전송 생략 " +
                        "(session=$sessionId, ppg=${ppg.size}, acc=${acc.size})"
            )
            return
        }

        apiScope.launch {
            try {
                val ppgCsv = CsvBuilder.buildPpgCsv(ppg)
                val imuCsv = CsvBuilder.buildImuCsv(acc)

                Log.d(
                    TAG,
                    "protocol8-1 전송 시작 session=$sessionId " +
                            "ppgSamples=${ppg.size} accSamples=${acc.size} " +
                            "ppgBytes=${ppgCsv.size} imuBytes=${imuCsv.size}"
                )

                val response = Protocol8_1API.requestPost(
                    sessionId = sessionId,
                    ppgCsv = ppgCsv,
                    imuCsv = imuCsv,
                )

                if (response.success) {
                    Log.d(TAG, "protocol8-1 전송 성공 status=${response.httpCode}")
                } else {
                    Log.e(
                        TAG,
                        "protocol8-1 전송 실패 status=${response.httpCode} body=${response.body}"
                    )
                    callback.onError("protocol8-1 전송 실패 (status=${response.httpCode})")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "protocol8-1 전송 중 예외", t)
                callback.onError("protocol8-1 전송 중 예외: ${t.message}")
            }
        }
    }
}
