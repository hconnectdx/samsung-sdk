package kr.co.hconnect.samsung_sdk.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.launch
import kr.co.hconnect.samsung_sdk.SamsungHealthSdk
import kr.co.hconnect.samsung_sdk.callback.SensorDataCallback
import kr.co.hconnect.samsung_sdk.example.databinding.ActivityMainBinding
import kr.co.hconnect.samsung_sdk.proto.SensorType
import kr.co.hconnect.samsung_sdk.scheduler.AlarmScheduler
import kr.co.hconnect.samsung_sdk.scheduler.CalSlotMinute
import kr.co.hconnect.samsung_sdk.tracker.TrackingService
import kr.co.hconnect.samsung_sdk.util.Constants
import kr.co.hconnect.samsung_sdk.util.PreferencesUtil

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val denied = result.filterValues { !it }.keys
        if (denied.isNotEmpty()) {
            appendLog("권한 거부: ${denied.joinToString()}")
        } else {
            appendLog("필수 권한 허용됨")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.title = getString(R.string.app_name)

        requestNeededPermissions()
        bindActions()
    }

    private fun requestNeededPermissions() {
        val needs = buildList {
            add(Manifest.permission.BODY_SENSORS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needs.isNotEmpty()) {
            permissionLauncher.launch(needs.toTypedArray())
        }
    }

    private fun bindActions() {
        binding.btnSdkInit.setOnClickListener { onSdkInit() }

        binding.btnPrefSaveDuration.setOnClickListener {
            lifecycleScope.launch {
                PreferencesUtil.setMeasurementDuration(this@MainActivity, 60_000L)
                appendLog("Preferences: 측정 시간 60초(60000ms) 저장 완료")
            }
        }
        binding.btnPrefReadDuration.setOnClickListener {
            lifecycleScope.launch {
                val ms = PreferencesUtil.getMeasurementDuration(this@MainActivity)
                appendLog("Preferences: 측정 시간 읽기 = ${ms}ms")
            }
        }
        binding.btnPrefSaveType.setOnClickListener {
            lifecycleScope.launch {
                PreferencesUtil.setMeasurementType(this@MainActivity, "PPG25,ACC")
                appendLog("Preferences: 측정 타입 문자열 저장 완료")
            }
        }
        binding.btnPrefReadType.setOnClickListener {
            lifecycleScope.launch {
                val t = PreferencesUtil.getMeasurementType(this@MainActivity)
                appendLog("Preferences: 측정 타입 읽기 = $t")
            }
        }

        binding.btnSlotCompute.setOnClickListener {
            val now = System.currentTimeMillis()
            val nextUtc = CalSlotMinute.computeNextSlotUtc(now, Constants.PERIODIC_ALARM_MINUTE)
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            appendLog("슬롯 계산: now=${now} → 다음=${sdf.format(Date(nextUtc))} (${nextUtc})")
        }

        binding.btnAlarmSchedule.setOnClickListener {
            if (!ensureSdkReady()) return@setOnClickListener
            try {
                AlarmScheduler.scheduleNext(
                    this,
                    intArrayOf(SensorType.PPG_GREEN_25.number, SensorType.ACC.number)
                )
                appendLog("알람 예약: 다음 슬롯에 PPG25+ACC 로 TrackingService 시작")
            } catch (e: SecurityException) {
                appendLog("알람 예약 실패(SecurityException): ${e.message}")
                maybeShowExactAlarmSettings()
            }
        }

        binding.btnAlarmCancel.setOnClickListener {
            AlarmScheduler.cancel(this)
            appendLog("알람 취소 완료")
        }

        binding.btnTrackPeriodicPpg25Acc.setOnClickListener {
            if (!ensureSdkReady()) return@setOnClickListener
            TrackingService.start(
                this,
                durationMs = 30_000L,
                slotMinute = 1,
                sensorType = setOf(SensorType.PPG_GREEN_25, SensorType.ACC)
            )
            appendLog("주기 측정 시작: 30초, 슬롯=1, PPG25+ACC")
        }

        binding.btnTrackPeriodicPpg100.setOnClickListener {
            if (!ensureSdkReady()) return@setOnClickListener
            TrackingService.start(
                this,
                durationMs = 45_000L,
                slotMinute = 31,
                sensorType = setOf(SensorType.PPG_GREEN_100)
            )
            appendLog("주기 측정 시작: 45초, 슬롯=31, PPG100")
        }

        binding.btnTrackPeriodicAccOnly.setOnClickListener {
            if (!ensureSdkReady()) return@setOnClickListener
            TrackingService.start(
                this,
                durationMs = 20_000L,
                slotMinute = 1,
                sensorType = setOf(SensorType.ACC)
            )
            appendLog("주기 측정 시작: 20초, 슬롯=1, ACC만")
        }

        binding.btnTrackOndemandPpgAcc.setOnClickListener {
            if (!ensureSdkReady()) return@setOnClickListener
            val ts = System.currentTimeMillis()
            TrackingService.startOnDemand(
                this,
                timestamp = ts,
                sensorTypes = intArrayOf(SensorType.PPG_GREEN_25.number, SensorType.ACC.number)
            )
            appendLog("온디맨드 시작: PPG25+ACC, ts=$ts")
        }

        binding.btnTrackOndemandEcgAcc.setOnClickListener {
            if (!ensureSdkReady()) return@setOnClickListener
            val ts = System.currentTimeMillis()
            TrackingService.startOnDemand(
                this,
                timestamp = ts,
                sensorTypes = intArrayOf(SensorType.ECG.number, SensorType.ACC.number)
            )
            appendLog("온디맨드 시작: ECG+ACC (SDK 내부에서 ECG 5초 지연), ts=$ts")
        }

        binding.btnTrackOndemandPpg100.setOnClickListener {
            if (!ensureSdkReady()) return@setOnClickListener
            val ts = System.currentTimeMillis()
            TrackingService.startOnDemand(
                this,
                timestamp = ts,
                sensorTypes = intArrayOf(SensorType.PPG_GREEN_100.number)
            )
            appendLog("온디맨드 시작: PPG100, ts=$ts")
        }

        binding.btnServiceStop.setOnClickListener {
            TrackingService.stop(this)
            appendLog("TrackingService.stop() 호출")
        }
    }

    private fun onSdkInit() {
        if (SamsungHealthSdk.isInitialized()) {
            Toast.makeText(this, R.string.toast_sdk_already_init, Toast.LENGTH_SHORT).show()
            appendLog("SDK 이미 초기화됨")
            return
        }
        SamsungHealthSdk.init(
            SensorDataCallback { data ->
                appendLogFromBackground("SensorDataCallback: ${data.size} bytes")
                true
            }
        )
        appendLog("SDK 초기화 완료 — 이제 측정·알람 기능 사용 가능")
    }

    private fun ensureSdkReady(): Boolean {
        if (SamsungHealthSdk.isInitialized()) return true
        Toast.makeText(this, R.string.toast_need_sdk_init, Toast.LENGTH_SHORT).show()
        appendLog("⚠ 먼저 「SDK 초기화」를 실행하세요.")
        return false
    }

    private fun appendLogFromBackground(line: String) {
        runOnUiThread { appendLog(line) }
    }

    private fun appendLog(line: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val cur = binding.logOutput.text?.toString().orEmpty()
        val next = "[$ts] $line\n$cur"
        val max = 12_000
        binding.logOutput.text = if (next.length > max) next.take(max) else next
    }

    private fun maybeShowExactAlarmSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val am = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        if (am.canScheduleExactAlarms()) return
        MaterialAlertDialogBuilder(this)
            .setTitle("정확한 알람")
            .setMessage("슬롯 알람 예약에 시스템 설정에서 정확한 알람이 필요할 수 있습니다.")
            .setPositiveButton("설정 열기") { _, _ ->
                startActivity(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                        data = android.net.Uri.parse("package:$packageName")
                    }
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
