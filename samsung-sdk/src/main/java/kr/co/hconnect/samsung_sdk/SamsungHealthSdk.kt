package kr.co.hconnect.samsung_sdk

import android.content.Context
import kr.co.hconnect.samsung_sdk.buffer.SensorBufferStorage
import kr.co.hconnect.samsung_sdk.callback.SensorDataCallback
import kr.co.hconnect.samsung_sdk.proto.SensorType
import kr.co.hconnect.samsung_sdk.scheduler.AlarmScheduler
import kr.co.hconnect.samsung_sdk.state.TrackingStateSender
import kr.co.hconnect.samsung_sdk.tracker.HealthTrackerManager
import kr.co.hconnect.samsung_sdk.tracker.HealthTrackerProcessorImpl
import kr.co.hconnect.samsung_sdk.tracker.TrackerManager
import kr.co.hconnect.samsung_sdk.tracker.TrackerProcessor
import kr.co.hconnect.samsung_sdk.tracker.TrackingService
import kr.co.hconnect.samsung_sdk.util.Constants
import kr.co.hconnect.samsung_sdk.util.PreferencesUtil
import kotlinx.coroutines.runBlocking

/**
 * samsung-sdk 라이브러리의 공개 진입점.
 *
 * 사용 방법:
 * ```
 * // 1) 초기화 — 앱 시작 시 한 번
 * SamsungHealthSdk.init(callback)
 *
 * // 2) 설정 — 주기 측정 파라미터 지정
 * SamsungHealthSdk.setMeasurementDuration(context, 120_000L)           // 측정 시간
 * SamsungHealthSdk.setAlarmSlotMinutes(context, intArrayOf(0, 30))     // 매 시 0분, 30분
 * SamsungHealthSdk.setAlarmSensorTypes(context, setOf(SensorType.ACC)) // 알람 시 측정할 센서
 *
 * // 3) 주기 측정 시작 (1회)
 * SamsungHealthSdk.startPeriodicTracking(context, durationMs, slotMinute, sensorTypes)
 *
 * // 4) 주기 알람 스케줄링 (반복)
 * SamsungHealthSdk.schedulePeriodicAlarm(context, sensorTypes)
 * SamsungHealthSdk.cancelPeriodicAlarm(context)
 *
 * // 5) 온디맨드 측정
 * SamsungHealthSdk.startOnDemandTracking(context, sensorTypes)
 * SamsungHealthSdk.stopOnDemandTracking(context)
 * ```
 */
object SamsungHealthSdk {

    internal val trackerManager: TrackerManager = HealthTrackerManager()
    internal val trackerProcessor: TrackerProcessor = HealthTrackerProcessorImpl(trackerManager)

    @Volatile
    private var initialized = false

    fun init(callback: SensorDataCallback) {
        SensorBufferStorage.initialize(callback)
        TrackingStateSender.init(callback)
        initialized = true
    }

    val isInitialized: Boolean get() = initialized

    val trackingState get() = trackerProcessor.trackingState

    // ── 주기 측정 (1회) ──

    fun startPeriodicTracking(
        context: Context,
        durationMs: Long,
        slotMinute: Int,
        sensorTypes: Set<SensorType>
    ) {
        check(initialized) { "SamsungHealthSdk.init()을 먼저 호출하세요." }
        TrackingService.start(context, durationMs, slotMinute, sensorTypes)
    }

    // ── 온디맨드 측정 ──

    fun startOnDemandTracking(
        context: Context,
        sensorTypes: Set<SensorType>,
        timestamp: Long = System.currentTimeMillis()
    ) {
        check(initialized) { "SamsungHealthSdk.init()을 먼저 호출하세요." }
        val typesArray = sensorTypes.map { it.number }.toIntArray()
        TrackingService.startOnDemand(context, timestamp, typesArray)
    }

    fun stopOnDemandTracking(context: Context) {
        TrackingService.stop(context)
    }

    // ── 주기 알람 스케줄링 ──

    fun schedulePeriodicAlarm(context: Context, sensorTypes: Set<SensorType>) {
        check(initialized) { "SamsungHealthSdk.init()을 먼저 호출하세요." }
        val typesArray = sensorTypes.map { it.number }.toIntArray()
        runBlocking { PreferencesUtil.setAlarmSensorTypes(context, typesArray) }
        AlarmScheduler.scheduleNext(context, typesArray)
    }

    fun cancelPeriodicAlarm(context: Context) {
        AlarmScheduler.cancel(context)
    }

    // ── 설정: 측정 시간 ──

    fun setMeasurementDuration(context: Context, durationMs: Long) {
        runBlocking { PreferencesUtil.setMeasurementDuration(context, durationMs) }
    }

    fun getMeasurementDuration(context: Context): Long {
        return runBlocking { PreferencesUtil.getMeasurementDuration(context) }
    }

    // ── 설정: 측정 타입 ──

    fun setMeasurementType(context: Context, type: String) {
        runBlocking { PreferencesUtil.setMeasurementType(context, type) }
    }

    fun getMeasurementType(context: Context): String? {
        return runBlocking { PreferencesUtil.getMeasurementType(context) }
    }

    // ── 설정: 알람 슬롯 분 ──

    /**
     * 주기 알람이 울리는 분(minute) 슬롯을 설정한다.
     * 예: intArrayOf(0, 30) → 매 시 0분, 30분에 측정
     * 예: intArrayOf(0, 15, 30, 45) → 매 시 15분 간격으로 측정
     *
     * 미설정 시 기본값: [1, 31] (매 시 1분, 31분)
     */
    fun setAlarmSlotMinutes(context: Context, minutes: IntArray) {
        require(minutes.isNotEmpty()) { "슬롯 분 배열은 비어 있을 수 없습니다." }
        require(minutes.all { it in 0..59 }) { "슬롯 분 값은 0~59 범위여야 합니다." }
        runBlocking { PreferencesUtil.setAlarmSlotMinutes(context, minutes) }
    }

    fun getAlarmSlotMinutes(context: Context): IntArray {
        return runBlocking { PreferencesUtil.getAlarmSlotMinutes(context) }
    }

    // ── 설정: 알람 시 센서 타입 ──

    /**
     * 주기 알람에서 측정할 센서 타입을 설정한다.
     * schedulePeriodicAlarm() 호출 시에도 자동 저장되지만,
     * 미리 설정해 두면 ScheduleReceiver가 재부팅 후에도 사용할 수 있다.
     */
    fun setAlarmSensorTypes(context: Context, sensorTypes: Set<SensorType>) {
        val typesArray = sensorTypes.map { it.number }.toIntArray()
        runBlocking { PreferencesUtil.setAlarmSensorTypes(context, typesArray) }
    }

    fun getAlarmSensorTypes(context: Context): Set<SensorType>? {
        val arr = runBlocking { PreferencesUtil.getAlarmSensorTypes(context) } ?: return null
        return arr.toList().mapNotNull { SensorType.forNumber(it) }.toSet()
    }
}
