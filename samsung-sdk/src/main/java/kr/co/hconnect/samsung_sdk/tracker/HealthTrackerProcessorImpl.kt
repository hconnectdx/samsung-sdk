package kr.co.hconnect.samsung_sdk.tracker

import android.content.Context
import android.util.Log
import com.samsung.android.service.health.tracking.HealthTrackingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kr.co.hconnect.samsung_sdk.buffer.SensorBufferStorage
import kr.co.hconnect.samsung_sdk.data.SdkTrackingState
import kr.co.hconnect.samsung_sdk.proto.SensorType
import kr.co.hconnect.samsung_sdk.state.TrackingStateSender
import java.util.concurrent.atomic.AtomicBoolean

class HealthTrackerProcessorImpl(
    private val healthTrackerManager: TrackerManager,
) : TrackerProcessor {

    override lateinit var context: Context
    override lateinit var coroutineScope: CoroutineScope

    private val _trackingState = MutableStateFlow<SdkTrackingState>(SdkTrackingState.Idle)
    override val trackingState: StateFlow<SdkTrackingState> = _trackingState.asStateFlow()

    private var trackingJob: Job? = null
    private var flushWatcherJob: Job? = null
    private val finishedOnce = AtomicBoolean(false)
    private val isFinishing = AtomicBoolean(false)
    private var currentSensorTypes: IntArray = intArrayOf()

    private val finishScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun startTracking(
        service: HealthTrackingService,
        durationMillis: Long,
        slotMinute: Int?,
        measurementType: Set<SensorType>
    ) {
        finishedOnce.set(false)

        if (trackingJob?.isActive == true) {
            Log.d(TAG, "이미 실행 중, 새로 시작 안 함")
            return
        }

        withContext(Dispatchers.IO) { SensorBufferStorage.clear() }

        val selected: Set<SensorType> = measurementType
        currentSensorTypes = selected.map { it.number }.toIntArray()

        val totalSeconds = durationMillis / 1000

        fun expectedBatchesFor(type: SensorType): Int {
            val (fs, batch) = when (type) {
                SensorType.ACC           -> 25 to 300
                SensorType.PPG_GREEN_25  -> 25 to 200
                SensorType.PPG_GREEN_100 -> 100 to 1200
                SensorType.ECG           -> 500 to 6000
                else                     -> 25 to 300
            }
            return ((totalSeconds * fs) / batch).toInt()
        }

        val expectedByType: Map<SensorType, Int> =
            selected.associateWith { expectedBatchesFor(it) }

        SensorBufferStorage.resetFlushedBatchCounts()
        healthTrackerManager.resetBatchCounters()

        val adjustedDurationMillis = durationMillis + 2_000L

        _trackingState.tryEmit(SdkTrackingState.Start)
        trackingJob = coroutineScope.launch {
            flushWatcherJob = launch {
                SensorBufferStorage.flushEvents.collect {
                    try {
                        val allReached = expectedByType.all { (t, expected) ->
                            SensorBufferStorage.getFlushedBatchCount(t) == expected
                        }
                        if (allReached) {
                            finishBatchCounting(expectedByType)
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "flushWatcher error", t)
                    }
                }
            }

            try {
                if (slotMinute != null) {
                    TrackingStateSender.sendStarted(slotMinute)
                }
                if (SensorType.ACC in measurementType) {
                    healthTrackerManager.startACCTracking(service, context) { }
                }
                if (SensorType.PPG_GREEN_25 in measurementType) {
                    healthTrackerManager.startPPG25Tracking(service, context) { }
                }
                if (SensorType.PPG_GREEN_100 in measurementType) {
                    healthTrackerManager.startPPG100Tracking(service, context) { }
                }

                delay(adjustedDurationMillis)
                withTimeoutOrNull(2_000L) { while (isActive) delay(50) }

            } catch (e: Exception) {
                Log.e(TAG, "측정 루프 예외 발생: ${e.message}", e)
            } finally {
                doFinishOnce()
            }
        }
    }

    private fun finishBatchCounting(expectedByType: Map<SensorType, Int>) {
        val result = expectedByType.mapValues { (t, _) ->
            SensorBufferStorage.getFlushedBatchCount(t)
        }
        val ok = result.all { (t, cnt) -> cnt == expectedByType[t] }
        if (ok) {
            Log.d(TAG, "목표 배치 달성: $result / expected=$expectedByType")
        } else {
            Log.w(TAG, "목표 배치 미달: $result / expected=$expectedByType")
        }
    }

    override suspend fun startTracking(
        service: HealthTrackingService,
        timestamp: Long?,
        measurementType: Set<SensorType>
    ) {
        finishedOnce.set(false)

        if (trackingJob?.isActive == true) {
            Log.d(TAG, "[On-Demand] 이미 실행 중이므로 새로 시작하지 않음")
            return
        }

        withContext(Dispatchers.IO) { SensorBufferStorage.clear() }

        val selected: Set<SensorType> = measurementType
        currentSensorTypes = selected.map { it.number }.toIntArray()

        if (timestamp != null) {
            TrackingStateSender.sendStartedWithoutSlot(timestamp)
        }
        _trackingState.tryEmit(SdkTrackingState.Start)

        trackingJob = coroutineScope.launch {
            try {
                if (SensorType.ACC in measurementType) {
                    healthTrackerManager.startACCTracking(service, context) { }
                }
                if (SensorType.PPG_GREEN_25 in selected) {
                    healthTrackerManager.startPPG25Tracking(service, context) { }
                }
                if (SensorType.PPG_GREEN_100 in selected) {
                    healthTrackerManager.startPPG100Tracking(service, context) { }
                }
                if (SensorType.ECG in selected) {
                    delay(5_000L)
                    healthTrackerManager.startECGTracking(service, context) { }
                }

                while (isActive) { delay(200L) }

            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "[On-Demand] 예외 발생: ${e.message}", e)
            }
        }
    }

    override fun finishTracking() {
        if (!isFinishing.compareAndSet(false, true)) {
            Log.d(TAG, "finishTracking() 이미 진행 중 — 무시")
            return
        }

        finishScope.launch {
            try {
                val job = trackingJob
                trackingJob = null
                if (job != null) {
                    job.cancel()
                    try { job.join() } catch (_: Throwable) {}
                }

                runCatching { healthTrackerManager.unsetTracker() }

                runCatching { SensorBufferStorage.shutdown() }

                sendFinishEventWithRetry()

                _trackingState.value = SdkTrackingState.Idle
            } finally {
                isFinishing.set(false)
                finishedOnce.set(false)
            }
        }
    }

    private fun sendFinishEventWithRetry(maxAttempts: Int = 5) {
        var attempts = 0
        var ok = false
        while (attempts < maxAttempts && !ok) {
            ok = runCatching { TrackingStateSender.sendFinished() }.getOrDefault(false)
            if (!ok) {
                attempts++
                Log.w(TAG, "FINISH 전송 실패 — 재시도 ($attempts/$maxAttempts)")
                Thread.sleep(200L)
            }
        }
        if (!ok) {
            Log.e(TAG, "FINISH 전송 최종 실패")
        }
        _trackingState.tryEmit(SdkTrackingState.Finish)
    }

    private fun doFinishOnce() {
        if (!finishedOnce.compareAndSet(false, true)) return
        finishTracking()
    }

    companion object {
        private const val TAG = "TrackerProcessorImpl"
    }
}
