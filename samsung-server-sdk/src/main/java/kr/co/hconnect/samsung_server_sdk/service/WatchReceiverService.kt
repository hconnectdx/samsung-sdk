package kr.co.hconnect.samsung_server_sdk.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import kr.co.hconnect.bluetooth_sdk_android_v2.HCBle
import kr.co.hconnect.samsung_server_sdk.SamsungServerSdk
import kr.co.hconnect.samsung_server_sdk.ble.NusConstants
import kr.co.hconnect.samsung_server_sdk.ble.PacketReassembler
import kr.co.hconnect.samsung_server_sdk.ble.WatchFinder
import kr.co.hconnect.samsung_server_sdk.session.SessionManager
import kr.co.hconnect.samsung_server_sdk.util.Constants
import kr.co.hconnect.samsung_server_sdk.write.DataWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kr.co.hconnect.bluetooth_sdk_android.gatt.BLEState

private const val TAG = "WatchReceiverService"

@SuppressLint("MissingPermission")
class WatchReceiverService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * BLE 데이터 처리 전용 단일 스레드 디스패처.
     * BLE Notification 순서를 반드시 보장하기 위해 병렬도를 1로 제한한다.
     */
    private val bleDispatcher = Dispatchers.IO.limitedParallelism(1)

    private var connectedDeviceAddress: String? = null

    private lateinit var sessionManager: SessionManager
    private lateinit var dataWriter: DataWriter
    private lateinit var reassembler: PacketReassembler

    // ── 생명주기 ───────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")

        val callback = SamsungServerSdk.getCallback()
        if (callback == null) {
            Log.e(TAG, "SamsungServerSdk.init() 미호출 상태에서 서비스 시작됨")
            stopSelf()
            return
        }

        dataWriter = DataWriter(this)
        sessionManager = SessionManager(callback, dataWriter)
        reassembler = PacketReassembler { proto ->
            sessionManager.process(proto)
        }

        HCBle.init(this)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand action=${intent?.action}")

        if (intent?.action == Constants.ACTION_STOP_SERVICE) {
            writeToWatch(NusConstants.CMD_SERVICE_STOPPED)
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground()

        val watch = WatchFinder.find(this)
        if (watch == null) {
            Log.e(TAG, "연결 가능한 Galaxy Watch를 찾을 수 없음")
            SamsungServerSdk.getCallback()?.onError("Galaxy Watch를 찾을 수 없습니다. 페어링 상태를 확인하세요.")
            stopSelf()
            return START_NOT_STICKY
        }

        connectWatch(watch.address)

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")

        if (sessionManager.isRecording) sessionManager.finishSession()

        connectedDeviceAddress?.let { HCBle.disconnect(it) }
        connectedDeviceAddress = null

        SamsungServerSdk.getCallback()?.onDisconnected()

        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── BLE 연결 ──────────────────────────────────────────────────────────────

    private fun connectWatch(address: String) {
        Log.d(TAG, "워치 연결 시도: $address")

        val device = HCBle.getDevice(address) ?: run {
            Log.e(TAG, "BluetoothDevice 취득 실패: $address")
            SamsungServerSdk.getCallback()?.onError("BluetoothDevice를 가져올 수 없습니다: $address")
            stopSelf()
            return
        }

        HCBle.connectToDevice(
            sessionId = "watch_receiver",
            device = device,

            onConnState = { state ->
                when (state) {
                    BLEState.STATE_CONNECTED -> {
                        connectedDeviceAddress = address
                        Log.d(TAG, "워치 연결됨: $address")
                        SamsungServerSdk.getCallback()?.onConnected(device.name ?: address)
                    }

                    BLEState.STATE_DISCONNECTED -> {
                        Log.d(TAG, "워치 연결 해제: $address")
                        serviceScope.launch(bleDispatcher) { reassembler.reset() }
                        if (sessionManager.isRecording) sessionManager.finishSession()
                        connectedDeviceAddress = null
                        SamsungServerSdk.getCallback()?.onDisconnected()
                    }

                    else -> Unit
                }
            },

            onGattServiceState = { status, _ ->
                if (status == 0 /* GATT_SUCCESS */) {
                    setupNus(address)
                }
            },

            onReceive = { characteristic ->
                @Suppress("DEPRECATION")
                val data = characteristic.value?.copyOf() ?: return@connectToDevice
                serviceScope.launch(bleDispatcher) {
                    reassembler.feed(data)
                }
            },

            useBondingChangeState = false,
            maxRetries = 3
        )
    }

    // ── NUS 설정 ──────────────────────────────────────────────────────────────

    private fun setupNus(address: String) {
        HCBle.setTargetServiceUUID(address, NusConstants.SERVICE_UUID)
        HCBle.setTargetReadCharacteristicUUID(address, NusConstants.CHAR_NOTIFY_UUID)
        HCBle.setTargetWriteCharacteristicUUID(address, NusConstants.CHAR_WRITE_UUID)
        HCBle.setCharacteristicNotification(address, isEnable = true)

        writeToWatch(NusConstants.CMD_SERVICE_RUNNING)
        Log.d(TAG, "NUS 설정 완료, 데이터 수신 대기 중")
    }

    // ── 워치에 명령 전송 ──────────────────────────────────────────────────────

    private fun writeToWatch(message: String) {
        val address = connectedDeviceAddress ?: return
        HCBle.writeCharacteristic(address, message.toByteArray(Charsets.UTF_8))
    }

    // ── 포그라운드 알림 ───────────────────────────────────────────────────────

    @RequiresApi(Build.VERSION_CODES.O)
    private fun startForeground() {
        val channel = NotificationChannel(
            Constants.NOTIFICATION_CHANNEL_ID,
            Constants.NOTIFICATION_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("워치 데이터 수신 중")
            .setContentText("Galaxy Watch와 BLE로 연결하여 데이터를 수신합니다.")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()

        startForeground(Constants.NOTIFICATION_ID, notification)
    }
}
