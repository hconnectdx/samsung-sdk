package kr.co.hconnect.samsung_server_sdk.session

import android.util.Log
import kr.co.hconnect.samsung_server_sdk.api.HealthOnClient
import kr.co.hconnect.samsung_server_sdk.api.Protocol2_1API
import kr.co.hconnect.samsung_server_sdk.api.Protocol8_1API
import kr.co.hconnect.samsung_server_sdk.api.SleepStartAPI
import kr.co.hconnect.samsung_server_sdk.api.SleepStopAPI
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private const val TAG = "SessionManager"

/**
 * ECG 세션의 안전장치용 최대 지속시간. 워치 앱의 자체 타임아웃(90초)보다 여유를 두되,
 * STOP 알림이 유실됐을 때 세션이 무한히 누적되는 걸 막기 위한 하드 캡이다.
 */
private const val MAX_ECG_SESSION_DURATION_MS = 100_000L

/**
 * 워치에서 수신한 [SensorBufferProto]를 처리하여 세션 상태를 관리하고
 * 파싱된 이벤트를 [ServerSdkCallback]으로 전달하며,
 * [DataWriter]를 통해 센서 데이터를 CSV 파일로 저장하고 API 로 전송한다.
 *
 * ## 파일 기반 전송
 * 인메모리 ByteArray 대신 DataWriter 가 저장한 CSV 파일을 직접 스트리밍 전송한다.
 * 대용량 데이터에서 발생하던 GC 일시정지 → Azure Gateway idle timeout(504) 문제를 해소한다.
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

    /** API 전송용 비동기 스코프. WatchReceiverService 가 죽어도 전송은 완료되도록 SupervisorJob 사용. */
    private val apiScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 세션별 센서 타입별 누적 샘플 수. */
    private val sampleCounts = mutableMapOf<SensorType, Int>()

    /** 현재 세션이 시작된 시각 (ms) — ECG 세션의 비정상 장기화 감지용. */
    @Volatile private var currentSessionStartedAtMs: Long = 0L

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
                if (currentMeasurementType == MeasurementType.SLEEP) {
                    // 수면은 1분 단위 청크마다 FINISH 가 발생한다.
                    // ① DataWriter 를 닫아 현재 청크 파일을 완성한다 (동기, bleDispatcher 에서 실행)
                    // ② 새 청크를 위해 DataWriter 를 다시 시작한다
                    // ③ 파일을 protocol8-1 로 비동기 전송한다
                    val sessionId = currentSessionId
                    if (sessionId != null && sampleCounts.isNotEmpty()) {
                        val chunkTotal = sampleCounts.entries
                            .joinToString(" | ") { (t, n) -> "$t=$n" }.ifEmpty { "없음" }
                        val chunkDir = dataWriter.closeAll()
                        sampleCounts.clear()
                        // 다음 청크를 위한 새 DataWriter 세션 시작
                        dataWriter.beginSession(sessionId)
                        if (chunkDir != null) {
                            Log.d(TAG, "SLEEP 청크 FINISH — protocol8-1 파일 전송 (session=$sessionId dir=${chunkDir.name}) 청크샘플 [$chunkTotal]")
                            apiScope.launch { sendProtocol8_1Files(sessionId, chunkDir) }
                        }
                    } else if (sessionId != null) {
                        // 직전 청크 시작 이후 샘플이 하나도 없다 — 중복/재전송된 FINISH 로 판단하고 무시한다.
                        // (그렇지 않으면 closeAll()+beginSession() 이 다시 실행되어 빈 청크 폴더가 1초 이내 간격으로 하나 더 생성된다)
                        Log.w(TAG, "SLEEP FINISH 무시 — 직전 청크 시작 이후 샘플 없음(중복 FINISH 추정) session=$sessionId")
                    }
                } else {
                    finishSession()
                }
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
                        currentSessionId?.let { callback.onMeasurementStarted(it, type) }
                        return
                    }

                    // 같은 타입으로 재시작 알림이 온 경우 — 정상적으로는 워치가 이미 아는 세션을
                    // 이어가는 것뿐이라 무시해야 한다. 하지만 ECG는 워치가 자체 타임아웃(90초)으로
                    // 멈췄는데 STOP 알림이 BLE로 전달되지 못했을 가능성이 있다 — 그 경우 세션이
                    // 비정상적으로 길게 살아남아 다음 측정 데이터가 옛 세션에 계속 누적된다.
                    // 그래서 세션 지속시간이 안전 캡을 넘겼으면 강제로 종료 후 새로 시작한다.
                    if (type == MeasurementType.ECG) {
                        val elapsed = System.currentTimeMillis() - currentSessionStartedAtMs
                        if (elapsed > MAX_ECG_SESSION_DURATION_MS) {
                            Log.w(
                                TAG,
                                "ECG 세션이 ${elapsed}ms 동안 지속됨(비정상) — STOP 알림 유실로 추정, " +
                                        "세션 강제 재시작 (session=$currentSessionId)"
                            )
                            finishSession()
                            startMeasurementSession(type)
                        }
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
        sampleCounts.clear()

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
        sampleCounts.clear()

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
        currentSessionStartedAtMs = System.currentTimeMillis()
        sampleCounts.clear()

        dataWriter.beginSession(sessionId)
        callback.onStoragePath(dataWriter.currentStoragePath)

        Log.d(TAG, "측정 세션 시작: type=$type sessionId=$sessionId")
        callback.onTrackingStarted(sessionId)
        callback.onMeasurementStarted(sessionId, type)

        if (type == MeasurementType.SLEEP) {
            apiScope.launch { notifySleepStart() }
        }
    }

    /** 수면 측정 종료 후 서버에 stop 알림을 전송하고, sleepQuality 를 콜백으로 전달한다. */
    private fun notifySleepStop(sessionId: String) {
        try {
            val response = SleepStopAPI.requestPost(sessionId = sessionId)
            if (response.success) {
                Log.d(
                    TAG,
                    "sleep/stop 전송 성공 status=${response.httpCode} sleepQuality=${response.sleepQuality}"
                )
                callback.onSleepFinished(sessionId, response.sleepQuality)
            } else {
                Log.e(TAG, "sleep/stop 전송 실패 status=${response.httpCode} body=${response.body}")
                callback.onError("sleep/stop 전송 실패 (status=${response.httpCode})")
                callback.onSleepFinished(sessionId, null)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "sleep/stop 전송 중 예외", t)
            callback.onError("sleep/stop 전송 중 예외: ${t.message}")
            callback.onSleepFinished(sessionId, null)
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

        val total = sampleCounts.entries.joinToString(" | ") { (t, n) -> "$t=$n" }
            .ifEmpty { "없음" }
        Log.d(TAG, "세션 종료: $sessionId (type=$type) 누적샘플 [$total]")
        isRecording = false
        currentSessionId = null

        // DataWriter 를 닫아 파일을 완성하고 경로를 받아온다.
        val sessionDir = dataWriter.closeAll()
        callback.onStoragePath(null)
        callback.onTrackingFinished(sessionId)

        when (type) {
            MeasurementType.SLEEP -> {
                apiScope.launch {
                    // ① 수면 종료 즉시 stop 호출 (8시간 경과 or 사용자 종료 시점)
                    notifySleepStop(sessionId)
                    // ② 마지막 청크 데이터 업로드 (파일이 있는 경우에만)
                    if (sessionDir != null) {
                        sendProtocol8_1Files(sessionId, sessionDir)
                    }
                }
            }
            MeasurementType.ECG -> {
                if (sessionDir != null) {
                    sendProtocol2_1Files(sessionId, sessionDir)
                } else {
                    Log.w(TAG, "sessionDir=null — protocol2-1 전송 생략 (session=$sessionId)")
                }
            }
            else -> {
                // 워치의 MEASUREMENT_TYPE:* 알림을 한 번도 받지 못한 레거시/예외 경로.
                Log.w(
                    TAG,
                    "currentMeasurementType=null — protocol2-1(일상)로 fallback 합니다. session=$sessionId"
                )
                if (sessionDir != null) {
                    sendProtocol2_1Files(sessionId, sessionDir)
                }
            }
        }
        currentMeasurementType = null
    }

    // ── 센서 샘플 라우팅 ──────────────────────────────────────────────────────

    private fun routeSamples(sessionId: String, samples: List<SensorSamples>) {
        samples.groupBy { it.sensorType }.forEach { (type: SensorType, list) ->
            dataWriter.appendBatch(type, list)
            callback.onSensorData(sessionId, type, list)
            sampleCounts[type] = (sampleCounts[type] ?: 0) + list.size
        }
    }

    // ── protocol2-1 전송 (일상/ECG) ──────────────────────────────────────────

    /**
     * 일상(ECG) 세션 종료 후 DataWriter 가 닫은 CSV 파일을 직접 스트리밍 전송한다.
     * `POST /poli/day/protocol2-1`
     */
    private fun sendProtocol2_1Files(sessionId: String, sessionDir: File) {
        val ppgFile = dataWriter.getFile(sessionDir, SensorType.PPG_GREEN_25)
            ?: dataWriter.getFile(sessionDir, SensorType.PPG_GREEN_100)
        val ecgFile = dataWriter.getFile(sessionDir, SensorType.ECG)

        if (ppgFile == null || ecgFile == null) {
            Log.w(
                TAG,
                "PPG/ECG 파일 없음 — protocol2-1 전송 생략 " +
                        "(session=$sessionId ppg=${ppgFile?.name} ecg=${ecgFile?.name})"
            )
            return
        }

        apiScope.launch {
            try {
                Log.d(
                    TAG,
                    "protocol2-1 파일 전송 시작 session=$sessionId " +
                            "ppg=${ppgFile.length() / 1024}KB ecg=${ecgFile.length() / 1024}KB"
                )

                val response = Protocol2_1API.requestPost(
                    ppgFile = ppgFile,
                    ecgFile = ecgFile,
                )

                if (response.success) {
                    Log.d(TAG, "protocol2-1 전송 성공 status=${response.httpCode}")
                } else {
                    Log.e(TAG, "protocol2-1 전송 실패 status=${response.httpCode} body=${response.body}")
                    callback.onError("protocol2-1 전송 실패 (status=${response.httpCode})")
                }

                callback.onProtocol2_1Result(
                    sessionId = sessionId,
                    success = response.success,
                    httpCode = response.httpCode,
                    body = response.body,
                )
            } catch (t: Throwable) {
                Log.e(TAG, "protocol2-1 전송 중 예외", t)
                callback.onError("protocol2-1 전송 중 예외: ${t.message}")
                callback.onProtocol2_1Result(
                    sessionId = sessionId,
                    success = false,
                    httpCode = -1,
                    body = t.message.orEmpty(),
                )
            }
        }
    }

    // ── protocol8-1 전송 (수면/SLEEP) ────────────────────────────────────────

    /**
     * 수면(SLEEP) 청크 또는 최종 데이터를 CSV 파일로 직접 스트리밍 전송한다.
     * `POST /poli/sleep/protocol8-1`
     *
     * sessionId 는 protocol8-1 스펙(`yyyyMMdd_HHmmss`, 15자) 을 따른다.
     */
    private fun sendProtocol8_1Files(sessionId: String, chunkDir: File) {
        if (sessionId.length != 15) {
            Log.e(
                TAG,
                "protocol8-1 sessionId 형식 불일치 — 전송 생략 (session=$sessionId, 길이=${sessionId.length})"
            )
            callback.onError("protocol8-1 sessionId 형식 오류: $sessionId")
            return
        }

        val ppgFile = dataWriter.getFile(chunkDir, SensorType.PPG_GREEN_25)
            ?: dataWriter.getFile(chunkDir, SensorType.PPG_GREEN_100)
        val imuFile = dataWriter.getFile(chunkDir, SensorType.ACC)

        if (ppgFile == null || imuFile == null) {
            Log.w(
                TAG,
                "PPG/IMU 파일 없음 — protocol8-1 전송 생략 " +
                        "(session=$sessionId ppg=${ppgFile?.name} imu=${imuFile?.name})"
            )
            return
        }

        try {
            Log.d(
                TAG,
                "protocol8-1 파일 전송 시작 session=$sessionId dir=${chunkDir.name} " +
                        "ppg=${ppgFile.length() / 1024}KB imu=${imuFile.length() / 1024}KB"
            )

            val response = Protocol8_1API.requestPost(
                sessionId = sessionId,
                ppgFile = ppgFile,
                imuFile = imuFile,
            )

            if (response.success) {
                Log.d(TAG, "protocol8-1 전송 성공 status=${response.httpCode}")
            } else {
                Log.e(TAG, "protocol8-1 전송 실패 status=${response.httpCode} body=${response.body}")
                callback.onError("protocol8-1 전송 실패 (status=${response.httpCode})")
            }

            callback.onProtocol8_1Result(
                sessionId = sessionId,
                success = response.success,
                httpCode = response.httpCode,
                body = response.body,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "protocol8-1 전송 중 예외", t)
            callback.onError("protocol8-1 전송 중 예외: ${t.message}")
            callback.onProtocol8_1Result(
                sessionId = sessionId,
                success = false,
                httpCode = -1,
                body = t.message.orEmpty(),
            )
        }
    }
}
