package kr.co.hconnect.samsung_sdk.tracker

import android.content.Context
import kr.co.hconnect.samsung_sdk.data.ACCData
import kr.co.hconnect.samsung_sdk.data.ECGData
import kr.co.hconnect.samsung_sdk.data.PPGGreen25Data
import kr.co.hconnect.samsung_sdk.data.PPGGreen100Data
import com.samsung.android.service.health.tracking.HealthTrackingService

interface TrackerManager {
    suspend fun connectService(context: Context): HealthTrackingService
    fun disConnectService(healthTrackingService: HealthTrackingService, context: Context)

    fun startACCTracking(service: HealthTrackingService, appContext: Context, onDataReceived: (List<ACCData>) -> Unit)
    fun startPPG25Tracking(service: HealthTrackingService, appContext: Context, onDataReceived: (List<PPGGreen25Data>) -> Unit)
    fun startPPG100Tracking(service: HealthTrackingService, appContext: Context, onDataReceived: (List<PPGGreen100Data>) -> Unit)
    fun startECGTracking(service: HealthTrackingService, appContext: Context, onDataReceived: (List<ECGData>) -> Unit)

    fun resetBatchCounters()
    fun unsetTracker()
}
