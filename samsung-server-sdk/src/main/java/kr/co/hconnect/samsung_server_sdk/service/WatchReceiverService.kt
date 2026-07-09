package kr.co.hconnect.samsung_server_sdk.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothGatt
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kr.co.hconnect.bluetooth_sdk_android.gatt.BLEState
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "WatchReceiverService"

/** 요청 MTU. 협상 성공 시 청크당 페이로드 = MTU - 3 바이트. */
private const val DESIRED_MTU = 512

/** MTU 협상 응답(onMtuChanged) 대기 시간. 초과 시 재요청한다. */
private const val MTU_TIMEOUT_MS = 3_000L

/** MTU 협상 최대 시도 횟수. */
private const val MTU_MAX_ATTEMPTS = 3

/** 데이터 유휴 판정 시간. 이 시간 동안 수신이 없으면 connection priority를 BALANCED로 복귀한다. */
private const val PRIORITY_IDLE_TIMEOUT_MS = 15_000L

/** 유휴 감시 주기. */
private const val PRIORITY_IDLE_CHECK_MS = 5_000L

@SuppressLint("MissingPermission")
class WatchReceiverService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * BLE 데이터 처리 전용 단일 스레드 디스패처.
     * BLE Notification 순서를 반드시 보장하기 위해 병렬도를 1로 제한한다.
     */
    private val bleDispatcher = Dispatchers.IO.limitedParallelism(1)

    private var connectedDeviceAddress: String? = null

    /** 현재 connection priority가 HIGH로 요청된 상태인지. */
    @Volatile
    private var connectionPriorityHigh = false

    /** 마지막 BLE 데이터 수신 시각 (elapsedRealtime 기준). */
    @Volatile
    private var lastDataAtMs = 0L

    private var priorityIdleWatchdog: Job? = null

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
        reassembler = PacketReassembler(
            onMessage = { proto -> sessionManager.process(proto) },
            onMeasurementType = { type -> sessionManager.onMeasurementType(type) },
        )

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

        if (intent?.action == Constants.ACTION_STOP_SLEEP_MEASUREMENT) {
            writeToWatch(NusConstants.CMD_MEASUREMENT_CONTROL_STOP_SLEEP)
            return START_STICKY
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

        if (::sessionManager.isInitialized && sessionManager.isRecording) {
            sessionManager.finishSession()
        }

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
                        // HIGH 인터벌에서 MTU 협상과 초기 셋업이 빨리 끝난다.
                        // 이후 데이터 유휴가 지속되면 워치독이 BALANCED로 복귀시킨다.
                        requestConnectionPriority(address, BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                        negotiateMtu(address)
                        SamsungServerSdk.getCallback()?.onConnected(device.name ?: address)
                    }

                    BLEState.STATE_DISCONNECTED -> {
                        Log.d(TAG, "워치 연결 해제: $address")
                        serviceScope.launch(bleDispatcher) { reassembler.reset() }
                        if (sessionManager.isRecording) sessionManager.finishSession()
                        connectedDeviceAddress = null
                        connectionPriorityHigh = false
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
                onDataActivity(address)
                serviceScope.launch(bleDispatcher) {
                    reassembler.feed(data)
                }
            },

            useBondingChangeState = false,
            maxRetries = 3
        )
    }

    // ── MTU 협상 ──────────────────────────────────────────────────────────────

    /**
     * MTU 협상을 요청하고, [MTU_TIMEOUT_MS] 안에 응답이 없거나 실패로 응답하면
     * 최대 [MTU_MAX_ATTEMPTS]회까지 재시도한다.
     *
     * GATT가 busy일 때 요청이 유실되거나 onMtuChanged가 도착하지 않으면
     * MTU가 기본값(23)으로 남아 전송이 극단적으로 느려지므로 재시도가 필수다.
     */
    private fun negotiateMtu(address: String, attempt: Int = 1) {
        val controller = HCBle.getGattController(address) ?: run {
            Log.e(TAG, "GATTController 취득 실패 — MTU 협상 불가: $address")
            return
        }

        // 결과 콜백과 타임아웃 중 먼저 도달한 쪽만 처리한다.
        val settled = AtomicBoolean(false)

        val queued = controller.requestMtu(DESIRED_MTU) { mtu, success ->
            if (!settled.compareAndSet(false, true)) return@requestMtu
            if (success) {
                Log.d(TAG, "MTU 협상 성공: mtu=$mtu (시도 $attempt/$MTU_MAX_ATTEMPTS)")
            } else {
                Log.w(TAG, "MTU 협상 실패 응답: mtu=$mtu (시도 $attempt/$MTU_MAX_ATTEMPTS)")
                retryMtu(address, attempt)
            }
        }
        Log.d(TAG, "MTU 협상 요청 ($DESIRED_MTU) — 큐잉됨=$queued, 시도=$attempt/$MTU_MAX_ATTEMPTS")

        serviceScope.launch {
            delay(MTU_TIMEOUT_MS)
            if (settled.compareAndSet(false, true)) {
                Log.w(TAG, "MTU 협상 응답 타임아웃 (시도 $attempt/$MTU_MAX_ATTEMPTS)")
                retryMtu(address, attempt)
            }
        }
    }

    private fun retryMtu(address: String, attempt: Int) {
        if (connectedDeviceAddress != address) return
        if (attempt >= MTU_MAX_ATTEMPTS) {
            Log.e(TAG, "MTU 협상 ${MTU_MAX_ATTEMPTS}회 모두 실패 — 기본 MTU로 동작합니다 (전송 저속 예상)")
            return
        }
        negotiateMtu(address, attempt + 1)
    }

    // ── Connection Priority ──────────────────────────────────────────────────

    /**
     * 연결 인터벌 우선순위를 요청한다.
     *
     * HIGH(11~15ms)는 처리량을 높이지만 배터리를 소모하므로 데이터 수신 중에만 유지하고,
     * [PRIORITY_IDLE_TIMEOUT_MS] 동안 수신이 없으면 워치독이 BALANCED로 복귀시킨다.
     * API 31 미만에서는 결과 콜백이 없어 요청 수락 여부(반환값)만 확인 가능하다.
     */
    private fun requestConnectionPriority(address: String, priority: Int) {
        val gatt = HCBle.getGattController(address)?.bluetoothGatt ?: run {
            Log.w(TAG, "BluetoothGatt 취득 실패 — connection priority 요청 불가: $address")
            return
        }
        val accepted = gatt.requestConnectionPriority(priority)
        connectionPriorityHigh = priority == BluetoothGatt.CONNECTION_PRIORITY_HIGH
        val name = if (connectionPriorityHigh) "HIGH" else "BALANCED"
        Log.d(TAG, "Connection priority 요청: $name, 스택 수락=$accepted")
        if (connectionPriorityHigh) startPriorityIdleWatchdog(address)
    }

    /**
     * BLE 데이터 수신 시마다 호출. 유휴 타이머를 갱신하고,
     * BALANCED로 내려가 있었다면 HIGH로 재요청한다 (워치가 절전 인터벌을
     * 요청해 느려진 상태에서 전송이 시작되는 경우를 복구).
     */
    private fun onDataActivity(address: String) {
        lastDataAtMs = SystemClock.elapsedRealtime()
        if (!connectionPriorityHigh) {
            requestConnectionPriority(address, BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        }
    }

    private fun startPriorityIdleWatchdog(address: String) {
        if (priorityIdleWatchdog?.isActive == true) return
        lastDataAtMs = SystemClock.elapsedRealtime()
        priorityIdleWatchdog = serviceScope.launch {
            while (connectionPriorityHigh && connectedDeviceAddress == address) {
                delay(PRIORITY_IDLE_CHECK_MS)
                if (SystemClock.elapsedRealtime() - lastDataAtMs >= PRIORITY_IDLE_TIMEOUT_MS) {
                    Log.d(TAG, "데이터 유휴 ${PRIORITY_IDLE_TIMEOUT_MS}ms 경과 — BALANCED 복귀")
                    requestConnectionPriority(address, BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
                }
            }
        }
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
