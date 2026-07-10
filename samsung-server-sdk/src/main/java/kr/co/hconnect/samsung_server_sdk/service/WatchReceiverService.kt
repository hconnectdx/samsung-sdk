package kr.co.hconnect.samsung_server_sdk.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
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

private const val TAG = "WatchReceiverService"

/**
 * 프로브 윈도우: CCCD 구독 직후 워치가 프로브 사다리(3발 × 700ms)를 돌리는 시간.
 * 이 동안 GATT write 큐는 PROBE_ACK 전용으로 비워둬야 한다 — 다른 write가
 * 워치에서 거부되면(status=133) 내부 재시도 40회가 큐를 수 초 점유해
 * ACK가 700ms 데드라인을 전부 놓친다 (2026-07-10 실측).
 */
private const val PROBE_WINDOW_MS = 3_500L

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
                        // HIGH 인터벌에서 초기 셋업(CCCD 구독·프로브)이 빨리 끝난다.
                        // 이후 데이터 유휴가 지속되면 워치독이 BALANCED로 복귀시킨다.
                        requestConnectionPriority(address, BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                        // MTU 협상은 하지 않는다: EATT 연결에서 레거시 MTU 교환은 스택이
                        // 전송하지 않아 onMtuChanged가 영원히 오지 않고(btsnoop 실측),
                        // 그 MtuReq가 GATT 작업 큐 head를 잠가 CCCD 구독·프로브 ACK 등
                        // 모든 후속 write를 막는다. 실효 청크 크기는 워치 프로브가 결정한다.
                        Log.d(TAG, "MTU 협상 생략 — 청크 크기는 워치 프로브로 결정")
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
                if (isChunkProbe(data)) {
                    // 프로브는 프레임 스트림이 아니므로 재조립기에 넣지 않는다 (오염 방지).
                    replyChunkProbe(data)
                } else {
                    serviceScope.launch(bleDispatcher) {
                        reassembler.feed(data)
                    }
                }
            },

            useBondingChangeState = false,
            maxRetries = 3
        )
    }

    // ── 청크 크기 프로브 응답 ─────────────────────────────────────────────────

    /**
     * 워치 청크 프로브(`PROBE:<seq>:<N>:` + 0xA5 패딩) 여부.
     * 스택 절단으로 일부만 도착해도 접두는 앞 20B 안에 보존된다.
     */
    private fun isChunkProbe(data: ByteArray): Boolean {
        val prefix = NusConstants.PROBE_PREFIX
        if (data.size < prefix.length) return false
        for (i in prefix.indices) {
            if (data[i] != prefix[i].code.toByte()) return false
        }
        return true
    }

    /**
     * 프로브에 실제 수신 바이트 수를 회신한다.
     * 워치는 이 값을 요청 크기(N)와 비교해 실효 청크 크기를 채택/기각하므로,
     * 반드시 "이 notify 한 건으로 도착한 크기"를 그대로 보내야 한다
     * (절단되어 20B만 왔으면 20 — 그래야 워치가 절단을 검출한다).
     *
     * ACK는 WRITE_TYPE_NO_RESPONSE로 보낸다 (2-14 요구):
     * - 페이로드가 MTU 23에서도 단일 PDU에 들어가는 크기(≤17B)라 prepared write를 타지 않고,
     * - 응답 대기가 없어 워치 GATT 서버의 write 응답 경로(133 거부) 영향을 받지 않는다.
     */
    private fun replyChunkProbe(data: ByteArray) {
        val address = connectedDeviceAddress ?: return
        // 패딩(0xA5)은 UTF-8 비정상 바이트라 ISO_8859_1로 무손실 해석한다.
        val text = String(data, Charsets.ISO_8859_1)
        val seq = text.split(":").getOrNull(1)?.toIntOrNull()
        if (seq == null) {
            Log.w(TAG, "프로브 헤더 파싱 실패 — 무응답 (수신 ${data.size}B)")
            return
        }
        val ack = "${NusConstants.PROBE_ACK_PREFIX}$seq:${data.size}"
        Log.d(TAG, "청크 프로브 수신 ${data.size}B (seq=$seq) → \"$ack\" 회신 (no-response write)")
        HCBle.writeCharacteristic(
            address,
            ack.toByteArray(Charsets.UTF_8),
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        )
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

        // CCCD 구독이 워치에 도착하는 순간 프로브가 시작되므로, 프로브 윈도우 동안
        // write 큐를 PROBE_ACK 전용으로 비워둔다 (PROBE_WINDOW_MS 주석 참조).
        serviceScope.launch {
            delay(PROBE_WINDOW_MS)
            if (connectedDeviceAddress == address) {
                writeToWatch(NusConstants.CMD_SERVICE_RUNNING)
            }
        }
        Log.d(TAG, "NUS 설정 완료, 데이터 수신 대기 중 (SERVICE_RUNNING은 프로브 윈도우 후 전송)")
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
