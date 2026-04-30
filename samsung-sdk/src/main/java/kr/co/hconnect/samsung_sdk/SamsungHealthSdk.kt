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
 * // 2) 주기 측정 시작
 * SamsungHealthSdk.startPeriodicTracking(context, durationMs, slotMinute, sensorTypes)
 *
 * // 3) 온디맨드 측정 시작
 * SamsungHealthSdk.startOnDemandTracking(context, sensorTypes)
 *
 * // 4) 온디맨드 측정 중지
 * SamsungHealthSdk.stopOnDemandTracking(context)
 *
 * // 5) 주기 알람 스케줄링
 * SamsungHealthSdk.schedulePeriodicAlarm(context, sensorTypes)
 * SamsungHealthSdk.cancelPeriodicAlarm(context)
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

    // ── 주기 측정 ──

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
        val typesArray = sensorTypes.map { it.number }.toIntArray()
        AlarmScheduler.scheduleNext(context, typesArray)
    }

    fun cancelPeriodicAlarm(context: Context) {
        AlarmScheduler.cancel(context)
    }

    // ── 설정 ──

    fun setMeasurementDuration(context: Context, durationMs: Long) {
        runBlocking { PreferencesUtil.setMeasurementDuration(context, durationMs) }
    }

    fun getMeasurementDuration(context: Context): Long {
        return runBlocking { PreferencesUtil.getMeasurementDuration(context) }
    }

    fun setMeasurementType(context: Context, type: String) {
        runBlocking { PreferencesUtil.setMeasurementType(context, type) }
    }

    fun getMeasurementType(context: Context): String? {
        return runBlocking { PreferencesUtil.getMeasurementType(context) }
    }
}
