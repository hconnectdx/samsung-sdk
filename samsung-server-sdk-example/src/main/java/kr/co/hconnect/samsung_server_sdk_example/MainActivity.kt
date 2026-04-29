package kr.co.hconnect.samsung_server_sdk_example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kr.co.hconnect.samsung_server_sdk.SamsungServerSdk
import kr.co.hconnect.samsung_server_sdk.callback.ServerSdkCallback
import kr.co.hconnect.samsung_server_sdk.proto.SensorSamples
import kr.co.hconnect.samsung_server_sdk.proto.SensorType
import kr.co.hconnect.samsung_server_sdk_example.ui.theme.SamsungSDKTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val logs = mutableStateListOf<String>()
    private val connectionState = mutableStateOf("연결 안됨")
    private val sessionState = mutableStateOf("세션 없음")
    private val storagePath = mutableStateOf<String?>(null)
    private val isRunning = mutableStateOf(false)
    private val isInitialized = mutableStateOf(false)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val denied = results.filter { !it.value }.keys
            if (denied.isEmpty()) {
                appendLog("권한 모두 승인됨")
            } else {
                appendLog("거부된 권한: $denied")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestPermissions()

        setContent {
            SamsungSDKTheme {
                MainScreen(
                    logs = logs,
                    connectionState = connectionState.value,
                    sessionState = sessionState.value,
                    storagePath = storagePath.value,
                    isRunning = isRunning.value,
                    isInitialized = isInitialized.value,
                    onInitSdk = ::initSdk,
                    onStart = ::startService,
                    onStop = ::stopService,
                    onClearLog = ::clearLog,
                )
            }
        }
    }

    // ── SDK 기능 ──────────────────────────────────────────────────────────────

    private fun initSdk() {
        SamsungServerSdk.init(object : ServerSdkCallback {

            override fun onConnected(deviceName: String) {
                runOnUiThread {
                    connectionState.value = "연결됨: $deviceName"
                    appendLog("[연결] $deviceName")
                }
            }

            override fun onDisconnected() {
                runOnUiThread {
                    connectionState.value = "연결 안됨"
                    appendLog("[연결 해제]")
                }
            }

            override fun onTrackingStarted(sessionId: String) {
                runOnUiThread {
                    sessionState.value = "기록 중: $sessionId"
                    appendLog("[세션 시작] $sessionId")
                }
            }

            override fun onTrackingFinished(sessionId: String) {
                runOnUiThread {
                    sessionState.value = "세션 없음"
                    appendLog("[세션 종료] $sessionId")
                }
            }

            override fun onSensorData(
                sessionId: String,
                sensorType: SensorType,
                samples: List<SensorSamples>
            ) {
                val summary = buildSensorSummary(sensorType, samples)
                runOnUiThread {
                    appendLog("[데이터] $sensorType × ${samples.size} — $summary")
                }
            }

            override fun onStoragePath(path: String?) {
                runOnUiThread {
                    storagePath.value = path
                    if (path != null) {
                        appendLog("[저장] $path")
                    } else {
                        appendLog("[저장] 종료")
                    }
                }
            }

            override fun onError(message: String) {
                runOnUiThread {
                    appendLog("[오류] $message")
                }
            }
        })

        isInitialized.value = true
        appendLog("SDK 초기화 완료")
    }

    private fun startService() {
        if (!isInitialized.value) {
            appendLog("먼저 SDK 초기화를 해주세요")
            return
        }
        SamsungServerSdk.start(this)
        isRunning.value = true
        appendLog("서비스 시작 요청")
    }

    private fun stopService() {
        SamsungServerSdk.stop(this)
        isRunning.value = false
        sessionState.value = "세션 없음"
        appendLog("서비스 정지 요청")
    }

    private fun clearLog() {
        logs.clear()
    }

    // ── 센서 데이터 요약 ─────────────────────────────────────────────────────

    private fun buildSensorSummary(type: SensorType, samples: List<SensorSamples>): String {
        val first = samples.firstOrNull() ?: return ""
        return when (type) {
            SensorType.ACC -> {
                val d = first.acc25Data
                "x=${d.x} y=${d.y} z=${d.z}"
            }
            SensorType.ECG -> {
                val d = first.ecgData
                "val=${d.value} leadOff=${d.leadOff}"
            }
            SensorType.PPG_GREEN_25 -> {
                val d = first.ppgGreen25Data
                "g=${d.green25} ir=${d.ir25} r=${d.red25}"
            }
            SensorType.PPG_GREEN_100 -> {
                val d = first.ppgGreen100Data
                "g=${d.green100} ir=${d.ir100} r=${d.red100}"
            }
            else -> ""
        }
    }

    // ── 유틸 ──────────────────────────────────────────────────────────────────

    private fun appendLog(message: String) {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        logs.add(0, "[$time] $message")
        if (logs.size > 500) logs.removeRange(500, logs.size)
    }

    private fun requestPermissions() {
        val needed = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            needed.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        needed.add(Manifest.permission.ACCESS_COARSE_LOCATION)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val notGranted = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isNotEmpty()) {
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }
}

// ── Compose UI ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    logs: List<String>,
    connectionState: String,
    sessionState: String,
    storagePath: String?,
    isRunning: Boolean,
    isInitialized: Boolean,
    onInitSdk: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onClearLog: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Samsung Server SDK Example") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── 상태 표시 ────────────────────────────────────────────────────
            StatusSection(connectionState, sessionState, storagePath, isRunning)

            HorizontalDivider()

            // ── 버튼 ─────────────────────────────────────────────────────────
            ButtonSection(isInitialized, isRunning, onInitSdk, onStart, onStop, onClearLog)

            HorizontalDivider()

            // ── 로그 ─────────────────────────────────────────────────────────
            Text(
                text = "로그",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            LogSection(logs, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
fun StatusSection(
    connectionState: String,
    sessionState: String,
    storagePath: String?,
    isRunning: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "상태",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        StatusRow("서비스", if (isRunning) "실행 중" else "정지", isRunning)
        StatusRow("BLE 연결", connectionState, connectionState.startsWith("연결됨"))
        StatusRow("세션", sessionState, sessionState.startsWith("기록 중"))
        StatusRow("저장 경로", storagePath ?: "없음", storagePath != null)
    }
}

@Composable
fun StatusRow(label: String, value: String, isActive: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, color = Color.Gray, fontSize = 14.sp)
        Text(
            text = value,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = if (isActive) Color(0xFF2E7D32) else Color(0xFFB71C1C),
        )
    }
}

@Composable
fun ButtonSection(
    isInitialized: Boolean,
    isRunning: Boolean,
    onInitSdk: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onClearLog: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "제어",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ActionButton(
                text = "SDK 초기화",
                onClick = onInitSdk,
                enabled = !isInitialized,
                modifier = Modifier.weight(1f),
            )
            ActionButton(
                text = "서비스 시작",
                onClick = onStart,
                enabled = isInitialized && !isRunning,
                modifier = Modifier.weight(1f),
                color = Color(0xFF2E7D32),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ActionButton(
                text = "서비스 정지",
                onClick = onStop,
                enabled = isRunning,
                modifier = Modifier.weight(1f),
                color = Color(0xFFB71C1C),
            )
            ActionButton(
                text = "로그 지우기",
                onClick = onClearLog,
                modifier = Modifier.weight(1f),
                color = Color(0xFF616161),
            )
        }
    }
}

@Composable
fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color),
    ) {
        Text(text = text, fontSize = 14.sp)
    }
}

@Composable
fun LogSection(logs: List<String>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.animateScrollToItem(0)
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
            .padding(8.dp),
    ) {
        if (logs.isEmpty()) {
            item {
                Text(
                    text = "로그가 없습니다",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        items(logs) { log ->
            Text(
                text = log,
                color = when {
                    "[오류]" in log -> Color(0xFFEF5350)
                    "[연결]" in log -> Color(0xFF66BB6A)
                    "[연결 해제]" in log -> Color(0xFFFF7043)
                    "[세션 시작]" in log -> Color(0xFF42A5F5)
                    "[세션 종료]" in log -> Color(0xFFFFA726)
                    "[데이터]" in log -> Color(0xFFAB47BC)
                    else -> Color(0xFFCCCCCC)
                },
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(vertical = 1.dp),
            )
        }
    }
}
