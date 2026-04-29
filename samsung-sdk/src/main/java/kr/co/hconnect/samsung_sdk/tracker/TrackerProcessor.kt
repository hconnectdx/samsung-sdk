package kr.co.hconnect.samsung_sdk.tracker

import android.content.Context
import kr.co.hconnect.samsung_sdk.data.TrackingState
import kr.co.hconnect.samsung_sdk.proto.SensorType
import com.samsung.android.service.health.tracking.HealthTrackingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

interface TrackerProcessor {
    var context: Context
    var coroutineScope: CoroutineScope
    val trackingState: StateFlow<TrackingState>

    suspend fun startTracking(
        service: HealthTrackingService,
        durationMillis: Long,
        slotMinute: Int?,
        measurementType: Set<SensorType>
    )

    suspend fun startTracking(
        service: HealthTrackingService,
        timestamp: Long?,
        measurementType: Set<SensorType>
    )

    fun finishTracking()
}
