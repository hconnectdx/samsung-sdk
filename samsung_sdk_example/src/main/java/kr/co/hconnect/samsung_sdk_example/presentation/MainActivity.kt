package kr.co.hconnect.samsung_sdk_example.presentation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import kr.co.hconnect.samsung_sdk.SamsungHealthSdk
import kr.co.hconnect.samsung_sdk.data.SdkTrackingState
import kr.co.hconnect.samsung_sdk.proto.SensorType
import kr.co.hconnect.samsung_sdk_example.presentation.theme.SamsungSDKTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "SdkExample"

class MainActivity : ComponentActivity() {

    private val logs = mutableStateListOf<LogEntry>()
    private var sdkInitialized = mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            addLog("권한 모두 승인됨", LogLevel.SUCCESS)
        } else {
            val denied = results.filter { !it.value }.keys.map { it.substringAfterLast('.') }
            addLog("권한 거부: $denied → 설정에서 직접 허용 필요", LogLevel.ERROR)
            openAppSettings()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setTheme(android.R.style.Theme_DeviceDefault)

        requestPermissions()

        setContent {
            SamsungSDKTheme {
                MainScreen(
                    logs = logs,
                    sdkInitialized = sdkInitialized.value,
                    onInitSdk = ::initSdk,
                    onStartOnDemandPpg25 = ::startOnDemandPpg25,
                    onStartOnDemandPpg100Ecg = ::startOnDemandPpg100Ecg,
                    onStopOnDemand = ::stopOnDemand,
                    onStartPeriodic = ::startPeriodic,
                    onScheduleAlarm = ::scheduleAlarm,
                    onCancelAlarm = ::cancelAlarm,
                    onClearLogs = ::clearLogs
                )
            }
        }
    }

    private fun requestPermissions() {
        val perms = mutableListOf(
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.ACTIVITY_RECOGNITION,
            Manifest.permission.HIGH_SAMPLING_RATE_SENSORS,
            "com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA"
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val notGranted = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isNotEmpty()) {
            val shouldOpenSettings = notGranted.any {
                !shouldShowRequestPermissionRationale(it) &&
                    getSharedPreferences("perm_prefs", MODE_PRIVATE)
                        .getBoolean("asked_$it", false)
            }

            if (shouldOpenSettings) {
                addLog("권한이 영구 거부됨 → 설정 화면 이동", LogLevel.WARN)
                openAppSettings()
            } else {
                notGranted.forEach {
                    getSharedPreferences("perm_prefs", MODE_PRIVATE)
                        .edit().putBoolean("asked_$it", true).apply()
                }
                permissionLauncher.launch(notGranted.toTypedArray())
            }
        } else {
            addLog("권한 이미 승인됨", LogLevel.INFO)
        }
    }

    private fun openAppSettings() {
        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }

    private fun initSdk() {
        if (sdkInitialized.value) {
            addLog("이미 초기화됨", LogLevel.WARN)
            return
        }

        SamsungHealthSdk.init { data ->
            addLog("[콜백] 데이터 수신 ${data.size}B", LogLevel.DATA)
            true
        }

        SamsungHealthSdk.setMeasurementDuration(this, 120_000L)
        SamsungHealthSdk.setAlarmSlotMinutes(this, intArrayOf(1,21, 31, 42))
        SamsungHealthSdk.setAlarmSensorTypes(this, setOf(SensorType.ACC, SensorType.PPG_GREEN_25))

        sdkInitialized.value = true

        val slots = SamsungHealthSdk.getAlarmSlotMinutes(this).joinToString(",")
        val duration = SamsungHealthSdk.getMeasurementDuration(this) / 1000
        addLog("SDK 초기화 완료 | 측정=${duration}초 | 슬롯=[${slots}]분", LogLevel.SUCCESS)
    }

    private fun startOnDemandPpg25() {
        if (!checkInit()) return
        val types = setOf(SensorType.ACC, SensorType.PPG_GREEN_25)
        addLog("온디맨드 시작: ACC + PPG25", LogLevel.INFO)
        SamsungHealthSdk.startOnDemandTracking(this, types)
    }

    private fun startOnDemandPpg100Ecg() {
        if (!checkInit()) return
        val types = setOf(SensorType.ACC, SensorType.PPG_GREEN_100, SensorType.ECG)
        addLog("온디맨드 시작: ACC + PPG100 + ECG", LogLevel.INFO)
        SamsungHealthSdk.startOnDemandTracking(this, types)
    }

    private fun stopOnDemand() {
        addLog("온디맨드 중지 요청", LogLevel.INFO)
        SamsungHealthSdk.stopOnDemandTracking(this)
    }

    private fun startPeriodic() {
        if (!checkInit()) return
        val durationMs = SamsungHealthSdk.getMeasurementDuration(this)
        val slots = SamsungHealthSdk.getAlarmSlotMinutes(this)
        val types = SamsungHealthSdk.getAlarmSensorTypes(this)
            ?: setOf(SensorType.ACC, SensorType.PPG_GREEN_25)
        val slotMinute = slots.firstOrNull() ?: 1
        addLog("주기 측정: ${durationMs/1000}초, slot=$slotMinute, 센서=$types", LogLevel.INFO)
        SamsungHealthSdk.startPeriodicTracking(this, durationMs, slotMinute, types)
    }

    private fun scheduleAlarm() {
        if (!checkInit()) return
        val types = SamsungHealthSdk.getAlarmSensorTypes(this)
            ?: setOf(SensorType.ACC, SensorType.PPG_GREEN_25)
        val slots = SamsungHealthSdk.getAlarmSlotMinutes(this).joinToString(",")
        SamsungHealthSdk.schedulePeriodicAlarm(this, types)
        addLog("알람 시작 | 슬롯=[${slots}]분 | 센서=$types", LogLevel.SUCCESS)
    }

    private fun cancelAlarm() {
        SamsungHealthSdk.cancelPeriodicAlarm(this)
        addLog("주기 알람 취소됨", LogLevel.WARN)
    }

    private fun clearLogs() {
        logs.clear()
    }

    private fun checkInit(): Boolean {
        if (!sdkInitialized.value) {
            addLog("SDK를 먼저 초기화하세요", LogLevel.ERROR)
            Toast.makeText(this, "SDK 초기화 필요", Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    private fun addLog(message: String, level: LogLevel = LogLevel.INFO) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val entry = LogEntry(time, message, level)
        logs.add(0, entry)
        if (logs.size > 100) logs.removeRange(100, logs.size)
        Log.d(TAG, "[${level.name}] $message")
    }
}

enum class LogLevel { INFO, SUCCESS, WARN, ERROR, DATA }

data class LogEntry(val time: String, val message: String, val level: LogLevel)

@Composable
fun MainScreen(
    logs: List<LogEntry>,
    sdkInitialized: Boolean,
    onInitSdk: () -> Unit,
    onStartOnDemandPpg25: () -> Unit,
    onStartOnDemandPpg100Ecg: () -> Unit,
    onStopOnDemand: () -> Unit,
    onStartPeriodic: () -> Unit,
    onScheduleAlarm: () -> Unit,
    onCancelAlarm: () -> Unit,
    onClearLogs: () -> Unit
) {
    val listState = rememberScalingLazyListState()
    val trackingState by SamsungHealthSdk.trackingState.collectAsState()

    val stateText = when (trackingState) {
        is SdkTrackingState.Idle -> "대기"
        is SdkTrackingState.Start -> "측정 중"
        is SdkTrackingState.Finish -> "완료"
    }

    val stateColor = when (trackingState) {
        is SdkTrackingState.Idle -> Color.Gray
        is SdkTrackingState.Start -> Color.Green
        is SdkTrackingState.Finish -> Color.Yellow
    }

    SamsungSDKTheme {
        ScalingLazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colors.background),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item { TimeText() }

            item { Spacer(modifier = Modifier.height(8.dp)) }

            // 상태 표시
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = if (sdkInitialized) "SDK: 초기화됨 | $stateText" else "SDK: 미초기화",
                        fontSize = 11.sp,
                        color = if (sdkInitialized) stateColor else Color.Red,
                        textAlign = TextAlign.Center
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(4.dp)) }

            // 초기화 버튼
            item {
                SdkButton(
                    text = "SDK 초기화",
                    color = Color(0xFF4CAF50),
                    onClick = onInitSdk
                )
            }

            // 온디맨드 ACC+PPG25 (워치 폭: 2줄)
            item {
                SdkButton(
                    text = "온디맨드\nACC+PPG25",
                    color = Color(0xFF2196F3),
                    onClick = onStartOnDemandPpg25
                )
            }

            // 온디맨드 ACC+PPG100+ECG
            item {
                SdkButton(
                    text = "온디맨드\nACC·PPG100·ECG",
                    color = Color(0xFF3F51B5),
                    onClick = onStartOnDemandPpg100Ecg
                )
            }

            // 온디맨드 중지
            item {
                SdkButton(
                    text = "온디맨드 중지",
                    color = Color(0xFFF44336),
                    onClick = onStopOnDemand
                )
            }

            // 주기 측정
            item {
                SdkButton(
                    text = "주기 측정 (2분)",
                    color = Color(0xFFFF9800),
                    onClick = onStartPeriodic
                )
            }

            // 알람 스케줄 시작/취소
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Button(
                        onClick = onScheduleAlarm,
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp),
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = Color(0xFF009688)
                        )
                    ) {
                        Text("알람 ON", fontSize = 10.sp, maxLines = 1)
                    }
                    Button(
                        onClick = onCancelAlarm,
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp),
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = Color(0xFF795548)
                        )
                    ) {
                        Text("알람 OFF", fontSize = 10.sp, maxLines = 1)
                    }
                }
            }

            // 로그 제목 + 클리어
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "로그 (${logs.size})",
                        fontSize = 11.sp,
                        color = Color.White
                    )
                    Text(
                        text = "[지우기]",
                        fontSize = 10.sp,
                        color = Color(0xFF9E9E9E),
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .clickable { onClearLogs() }
                    )
                }
            }

            // 로그 항목들
            if (logs.isEmpty()) {
                item {
                    Text(
                        text = "로그 없음",
                        fontSize = 10.sp,
                        color = Color.Gray,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp)
                    )
                }
            } else {
                items(logs) { entry ->
                    LogItem(entry)
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

@Composable
fun SdkButton(
    text: String,
    color: Color,
    onClick: () -> Unit
) {
    Chip(
        onClick = onClick,
        label = {
            Text(
                text = text,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                maxLines = 2,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        colors = ChipDefaults.chipColors(
            backgroundColor = color.copy(alpha = 0.8f)
        )
    )
}

@Composable
fun LogItem(entry: LogEntry) {
    val color = when (entry.level) {
        LogLevel.INFO -> Color(0xFFBBBBBB)
        LogLevel.SUCCESS -> Color(0xFF4CAF50)
        LogLevel.WARN -> Color(0xFFFFC107)
        LogLevel.ERROR -> Color(0xFFF44336)
        LogLevel.DATA -> Color(0xFF00BCD4)
    }
    Text(
        text = "${entry.time} ${entry.message}",
        fontSize = 9.sp,
        color = color,
        maxLines = 2,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 1.dp)
    )
}
