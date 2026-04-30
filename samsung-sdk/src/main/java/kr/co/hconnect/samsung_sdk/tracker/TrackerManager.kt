package kr.co.hconnect.samsung_sdk.tracker

import android.content.Context
import com.samsung.android.service.health.tracking.HealthTrackingService
import kr.co.hconnect.samsung_sdk.data.ACCData
import kr.co.hconnect.samsung_sdk.data.ECG500Data
import kr.co.hconnect.samsung_sdk.data.PPGGreen100Data
import kr.co.hconnect.samsung_sdk.data.PPGGreen25Data

interface TrackerManager {
    suspend fun connectService(context: Context): HealthTrackingService
    fun disConnectService(healthTrackingService: HealthTrackingService, context: Context)

    fun startACCTracking(service: HealthTrackingService, appContext: Context, onDataReceived: (List<ACCData>) -> Unit)
    fun startPPG25Tracking(service: HealthTrackingService, appContext: Context, onDataReceived: (List<PPGGreen25Data>) -> Unit)
    fun startPPG100Tracking(service: HealthTrackingService, appContext: Context, onDataReceived: (List<PPGGreen100Data>) -> Unit)
    fun startECGTracking(service: HealthTrackingService, appContext: Context, onDataReceived: (List<ECG500Data>) -> Unit)

    fun resetBatchCounters()
    fun unsetTracker()
}
