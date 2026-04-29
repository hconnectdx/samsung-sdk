package kr.co.hconnect.samsung_sdk.tracker

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kr.co.hconnect.samsung_sdk.data.TrackingState
import kr.co.hconnect.samsung_sdk.proto.SensorType
import com.samsung.android.service.health.tracking.HealthTrackingService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "TrackingService"

@AndroidEntryPoint
class TrackingService : Service() {

    private lateinit var service: HealthTrackingService
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var wakeLock: PowerManager.WakeLock

    @Inject lateinit var trackerProcessor: TrackerProcessor
    @Inject lateinit var trackerManager: TrackerManager

    override fun onCreate() {
        super.onCreate()

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SamsungSdk::CpuWakeLock"
        ).apply { setReferenceCounted(false) }

        trackerProcessor.context = this
        trackerProcessor.coroutineScope = scope
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_START -> {
                startForegroundService()
                wakeLock.acquire()

                val durationMs = intent.getLongExtra(EXTRA_DURATION_MS, 0L)
                val slotMinute: Int? =
                    if (intent.hasExtra(EXTRA_SLOT_MINUTE)) {
                        intent.getIntExtra(EXTRA_SLOT_MINUTE, -1).let { if (it >= 0) it else null }
                    } else null
                val typesArray = intent.getIntArrayExtra(EXTRA_SENSOR_TYPE) ?: intArrayOf()
                val measurementType: Set<SensorType> = typesArray.toList()
                    .mapNotNull { SensorType.forNumber(it) }
                    .toSet()

                scope.launch {
                    service = trackerManager.connectService(this@TrackingService)
                    trackerProcessor.startTracking(service, durationMs, slotMinute, measurementType)

                    trackerProcessor.trackingState.collect { state ->
                        if (state == TrackingState.Finish) { stopSelf() }
                    }
                }
            }

            ACTION_START_ON_DEMAND -> {
                startForegroundService()
                wakeLock.acquire()

                val typesArray = intent.getIntArrayExtra(EXTRA_SENSOR_TYPE) ?: intArrayOf()
                val timestamp = intent.getLongExtra(EXTRA_ON_DEMAND_START_TS, -1L)
                    .takeIf { it > 0 }
                val measurementType: Set<SensorType> = typesArray.toList()
                    .mapNotNull { SensorType.forNumber(it) }
                    .toSet()

                scope.launch {
                    service = trackerManager.connectService(this@TrackingService)
                    trackerProcessor.startTracking(service, timestamp, measurementType)
                }
            }

            ACTION_STOP_ON_DEMAND -> {
                scope.launch { stopSelf() }
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runCatching { trackerManager.unsetTracker() }
            .onFailure { Log.w(TAG, "unsetTracker failed", it) }

        trackerProcessor.finishTracking()

        try {
            if (::service.isInitialized) {
                trackerManager.disConnectService(service, this)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "disconnectService failed", t)
        }

        if (wakeLock.isHeld) wakeLock.release()
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)

        super.onDestroy()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun startForegroundService() {
        createNotificationChannel()

        val id = 1001
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Tracking Service")
            .setContentText("Tracking Service is running")
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(id, notification, FOREGROUND_SERVICE_TYPE_HEALTH)
        } else {
            startForeground(id, notification)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(serviceChannel)
    }

    companion object {
        private const val CHANNEL_ID = "TRACKING_SERVICE_CHANNEL"
        private const val CHANNEL_NAME = "Tracking Service Channel"

        const val ACTION_START = "kr.co.hconnect.samsung_sdk.tracker.START"

        const val ACTION_START_ON_DEMAND = "kr.co.hconnect.samsung_sdk.tracker.START_ON_DEMAND"
        const val EXTRA_ON_DEMAND_START_TS = "extra_on_demand_start_ts"
        const val ACTION_STOP_ON_DEMAND  = "kr.co.hconnect.samsung_sdk.tracker.STOP_ON_DEMAND"

        const val EXTRA_DURATION_MS = "extra_duration_ms"
        const val EXTRA_SLOT_MINUTE = "extra_slot_minute"
        const val EXTRA_SENSOR_TYPE = "extra_sensor_type"

        fun start(context: Context, durationMs: Long, slotMinute: Int, sensorType: Set<SensorType>) {
            val i = Intent(context, TrackingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_DURATION_MS, durationMs)
                .putExtra(EXTRA_SLOT_MINUTE, slotMinute)
                .putExtra(EXTRA_SENSOR_TYPE, sensorType.map { it.number }.toIntArray())
            ContextCompat.startForegroundService(context, i)
        }

        fun startOnDemand(context: Context, timestamp: Long, sensorTypes: IntArray) {
            val i = Intent(context, TrackingService::class.java)
                .setAction(ACTION_START_ON_DEMAND)
                .putExtra(EXTRA_ON_DEMAND_START_TS, timestamp)
                .putExtra(EXTRA_SENSOR_TYPE, sensorTypes)
            ContextCompat.startForegroundService(context, i)
        }

        @SuppressLint("ImplicitSamInstance")
        fun stop(context: Context) {
            context.stopService(Intent(context, TrackingService::class.java))
        }
    }
}
