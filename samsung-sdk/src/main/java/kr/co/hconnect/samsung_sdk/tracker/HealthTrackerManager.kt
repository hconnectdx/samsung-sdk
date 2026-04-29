package kr.co.hconnect.samsung_sdk.tracker

import android.content.Context
import android.util.Log
import kr.co.hconnect.samsung_sdk.data.ACCData
import kr.co.hconnect.samsung_sdk.data.ECGData
import kr.co.hconnect.samsung_sdk.data.PPGGreen25Data
import kr.co.hconnect.samsung_sdk.data.PPGGreen100Data
import kr.co.hconnect.samsung_sdk.buffer.SensorBufferStorage
import kr.co.hconnect.samsung_sdk.proto.*
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.ValueKey
import com.samsung.android.service.health.tracking.data.PpgType
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Singleton
class HealthTrackerManager @Inject constructor() : TrackerManager {

    private lateinit var accTracker: HealthTracker
    private lateinit var ppg25Tracker: HealthTracker
    private lateinit var ppg100Tracker: HealthTracker
    private lateinit var ecgTracker: HealthTracker

    @Volatile private var ppgBatchCount = 0
    @Volatile private var accBatchCount = 0
    @Volatile private var ppg100BatchCount = 0

    override fun resetBatchCounters() {
        ppgBatchCount = 0
        accBatchCount = 0
        ppg100BatchCount = 0
        Log.d(TAG, "Batch counters reset")
    }

    override suspend fun connectService(
        context: Context,
    ): HealthTrackingService = suspendCancellableCoroutine { cont ->

        var healthTrackingService: HealthTrackingService? = null

        val listener = object : ConnectionListener {
            override fun onConnectionSuccess() {
                Log.i(TAG, "Connection Success")
                if (cont.isActive) {
                    healthTrackingService?.let { service -> cont.resume(service) }
                }
            }
            override fun onConnectionEnded() { Log.i(TAG, "Connection Ended") }

            override fun onConnectionFailed(e: HealthTrackerException?) {
                Log.i(TAG, "Connection Failed")
                if (cont.isActive) cont.resumeWithException(e ?: Exception("Unknown Error"))
            }
        }

        healthTrackingService = HealthTrackingService(listener, context).apply {
            connectService()
        }
    }

    override fun disConnectService(
        healthTrackingService: HealthTrackingService,
        context: Context
    ) {
        healthTrackingService.disconnectService()
        Log.e(TAG, "disConnect HealthTrackingService")
    }

    override fun startPPG25Tracking(
        service: HealthTrackingService,
        appContext: Context,
        onDataReceived: (List<PPGGreen25Data>) -> Unit
    ) {
        ppg25Tracker = getPPG25Tracker(service)

        val listener = object : HealthTracker.TrackerEventListener {
            override fun onDataReceived(dataPoints: MutableList<DataPoint>) {
                val samples = ArrayList<SensorSamples>(dataPoints.size)
                for (dp in dataPoints) {
                    samples.add(
                        SensorSamples.newBuilder()
                            .setSensorType(SensorType.PPG_GREEN_25)
                            .setPpgGreen25Data(PpgGreen25.newBuilder()
                                .setTimestamp(dp.timestamp)
                                .setGreen25(dp.getValue(ValueKey.PpgSet.PPG_GREEN))
                                .setIr25(dp.getValue(ValueKey.PpgSet.PPG_IR))
                                .setRed25(dp.getValue(ValueKey.PpgSet.PPG_RED))
                                .build())
                            .build()
                    )
                }
                if (samples.isNotEmpty()) {
                    SensorBufferStorage.appendAll(appContext, SensorType.PPG_GREEN_25, samples)
                }
            }

            override fun onFlushCompleted() { Log.i(TAG, "PPG25 Tracker Flush Completed") }
            override fun onError(e: HealthTracker.TrackerError?) { Log.i(TAG, "PPG25 Tracker Error") }
        }
        ppg25Tracker.setEventListener(listener)
    }

    override fun startACCTracking(
        service: HealthTrackingService,
        appContext: Context,
        onDataReceived: (List<ACCData>) -> Unit
    ) {
        accTracker = getACCTracker(service)

        val listener = object : HealthTracker.TrackerEventListener {
            override fun onDataReceived(dataPoints: MutableList<DataPoint>) {
                val samples = ArrayList<SensorSamples>(dataPoints.size)
                for (dp in dataPoints) {
                    samples.add(
                        SensorSamples.newBuilder()
                            .setSensorType(SensorType.ACC)
                            .setAcc25Data(Acc25.newBuilder()
                                .setTimestamp(dp.timestamp)
                                .setX(dp.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_X))
                                .setY(dp.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_Y))
                                .setZ(dp.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_Z))
                                .build())
                            .build()
                    )
                }
                if (samples.isNotEmpty()) {
                    SensorBufferStorage.appendAll(appContext, SensorType.ACC, samples)
                }
            }

            override fun onFlushCompleted() { Log.i(TAG, "ACC Tracker Flush Completed") }
            override fun onError(e: HealthTracker.TrackerError?) { Log.i(TAG, "ACC Tracker Error: $e") }
        }
        accTracker.setEventListener(listener)
    }

    override fun startPPG100Tracking(
        service: HealthTrackingService,
        appContext: Context,
        onDataReceived: (List<PPGGreen100Data>) -> Unit
    ) {
        ppg100Tracker = getPPG100Tracker(service)

        val listener = object : HealthTracker.TrackerEventListener {
            override fun onDataReceived(dataPoints: MutableList<DataPoint>) {
                val samples = ArrayList<SensorSamples>(dataPoints.size)
                for (dp in dataPoints) {
                    samples.add(
                        SensorSamples.newBuilder()
                            .setSensorType(SensorType.PPG_GREEN_100)
                            .setPpgGreen100Data(PpgGreen100.newBuilder()
                                .setTimestamp(dp.timestamp)
                                .setGreen100(dp.getValue(ValueKey.PpgSet.PPG_GREEN))
                                .setIr100(dp.getValue(ValueKey.PpgSet.PPG_IR))
                                .setRed100(dp.getValue(ValueKey.PpgSet.PPG_RED))
                                .build())
                            .build()
                    )
                }
                if (samples.isNotEmpty()) {
                    SensorBufferStorage.appendAll(appContext, SensorType.PPG_GREEN_100, samples)
                }
            }

            override fun onFlushCompleted() { Log.i(TAG, "PPG100 Tracker Flush Completed") }
            override fun onError(e: HealthTracker.TrackerError?) { Log.i(TAG, "PPG100 Tracker Error") }
        }
        ppg100Tracker.setEventListener(listener)
    }

    override fun startECGTracking(
        service: HealthTrackingService,
        appContext: Context,
        onDataReceived: (List<ECGData>) -> Unit
    ) {
        ecgTracker = getECGTracker(service)

        val listener = object : HealthTracker.TrackerEventListener {
            override fun onDataReceived(dataPoints: MutableList<DataPoint>) {
                val samples = ArrayList<SensorSamples>(dataPoints.size)
                for (dp in dataPoints) {
                    samples.add(
                        SensorSamples.newBuilder()
                            .setSensorType(SensorType.ECG)
                            .setEcgData(Ecg.newBuilder()
                                .setTimestamp(dp.timestamp)
                                .setValue(dp.getValue(ValueKey.EcgSet.ECG_MV))
                                .setLeadOff(dp.getValue(ValueKey.EcgSet.LEAD_OFF))
                                .setMaxThresholdMv(dp.getValue(ValueKey.EcgSet.MAX_THRESHOLD_MV))
                                .setMinThresholdMv(dp.getValue(ValueKey.EcgSet.MIN_THRESHOLD_MV))
                                .setSeq(dp.getValue(ValueKey.EcgSet.SEQUENCE))
                                .build())
                            .build()
                    )
                }
                if (samples.isNotEmpty()) {
                    SensorBufferStorage.appendAll(appContext, SensorType.ECG, samples)
                }
            }

            override fun onFlushCompleted() { Log.i(TAG, "ECG Tracker Flush Completed") }
            override fun onError(e: HealthTracker.TrackerError?) { Log.i(TAG, "ECG Tracker Error") }
        }
        ecgTracker.setEventListener(listener)
    }

    private fun getACCTracker(service: HealthTrackingService): HealthTracker {
        return service.getHealthTracker(HealthTrackerType.ACCELEROMETER_CONTINUOUS)
    }

    private fun getPPG25Tracker(service: HealthTrackingService): HealthTracker {
        return service.getHealthTracker(HealthTrackerType.PPG_CONTINUOUS,
            setOf(PpgType.GREEN, PpgType.IR, PpgType.RED))
    }

    private fun getPPG100Tracker(service: HealthTrackingService): HealthTracker {
        return service.getHealthTracker(HealthTrackerType.PPG_ON_DEMAND,
            setOf(PpgType.GREEN, PpgType.IR, PpgType.RED))
    }

    private fun getECGTracker(service: HealthTrackingService): HealthTracker {
        return service.getHealthTracker(HealthTrackerType.ECG_ON_DEMAND)
    }

    override fun unsetTracker() {
        if (::ecgTracker.isInitialized) ecgTracker.unsetEventListener()
        if (::ppg100Tracker.isInitialized) ppg100Tracker.unsetEventListener()
        if (::accTracker.isInitialized) accTracker.unsetEventListener()
        if (::ppg25Tracker.isInitialized) ppg25Tracker.unsetEventListener()
        Log.d(TAG, "All trackers stopped (unsetEventListener)")
    }

    companion object {
        private const val TAG = "HealthTrackerManager"
    }
}
