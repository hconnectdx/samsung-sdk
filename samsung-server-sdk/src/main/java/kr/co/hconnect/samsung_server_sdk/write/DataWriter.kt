package kr.co.hconnect.samsung_server_sdk.write

import android.content.Context
import android.os.Environment
import android.util.Log
import kr.co.hconnect.samsung_server_sdk.proto.SensorSamples
import kr.co.hconnect.samsung_server_sdk.proto.SensorType
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "DataWriter"
private const val ROOT_DIR = "SamsungServerSdk"

/**
 * 센서 데이터를 시간대별 폴더 구조로 CSV 파일에 저장한다.
 *
 * ## 폴더 구조
 * ```
 * Documents/SamsungServerSdk/
 *   └── 2026-04-29/
 *       └── 15_30_00/
 *           ├── ACC.csv
 *           ├── ECG.csv
 *           ├── PPG_GREEN_25.csv
 *           └── PPG_GREEN_100.csv
 * ```
 */
internal class DataWriter(private val context: Context) {

    private val writers = mutableMapOf<SensorType, BufferedWriter>()
    private var sessionDir: File? = null

    val currentStoragePath: String?
        get() = sessionDir?.absolutePath

    /**
     * 새 세션을 시작하고 시간대별 폴더를 생성한다.
     *
     * @param sessionId 세션 식별자 (로그 용도)
     */
    fun beginSession(sessionId: String) {
        closeAll()

        val now = Date()
        val dateFolder = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)
        val timeFolder = SimpleDateFormat("HH_mm_ss", Locale.US).format(now)

        val baseDir = resolveBaseDir()
        val dir = File(baseDir, "$dateFolder/$timeFolder")

        if (!dir.exists() && !dir.mkdirs()) {
            Log.e(TAG, "폴더 생성 실패: ${dir.absolutePath}")
            return
        }

        sessionDir = dir
        Log.d(TAG, "세션 저장 시작: ${dir.absolutePath} ($sessionId)")
    }

    /**
     * 센서 샘플 배치를 해당 타입의 CSV 파일에 추가한다.
     */
    fun appendBatch(sensorType: SensorType, samples: List<SensorSamples>) {
        val dir = sessionDir ?: return
        val writer = writers.getOrPut(sensorType) {
            createWriter(dir, sensorType)
        }

        for (sample in samples) {
            val line = toCsvLine(sensorType, sample) ?: continue
            writer.write(line)
            writer.newLine()
        }
        writer.flush()
    }

    /**
     * 현재 세션의 모든 CSV writer를 닫는다.
     */
    fun closeAll() {
        writers.values.forEach { writer ->
            try {
                writer.flush()
                writer.close()
            } catch (e: Exception) {
                Log.e(TAG, "Writer close 실패: ${e.message}")
            }
        }
        writers.clear()
        sessionDir = null
        Log.d(TAG, "모든 writer 종료")
    }

    // ── CSV Writer 생성 ──────────────────────────────────────────────────────

    private fun createWriter(dir: File, type: SensorType): BufferedWriter {
        val fileName = "${type.name}.csv"
        val file = File(dir, fileName)
        val isNew = !file.exists()

        val writer = BufferedWriter(FileWriter(file, true))
        if (isNew) {
            writer.write(getCsvHeader(type))
            writer.newLine()
            writer.flush()
        }
        Log.d(TAG, "CSV 생성: ${file.absolutePath}")
        return writer
    }

    // ── CSV 헤더 ──────────────────────────────────────────────────────────────

    private fun getCsvHeader(type: SensorType): String = when (type) {
        SensorType.ACC -> "timestamp,x,y,z"
        SensorType.ECG -> "timestamp,value,lead_off,max_threshold_mv,min_threshold_mv,seq"
        SensorType.PPG_GREEN_25 -> "timestamp,green25,ir25,red25"
        SensorType.PPG_GREEN_100 -> "timestamp,green100,ir100,red100"
        else -> "timestamp,data"
    }

    // ── CSV 행 변환 ──────────────────────────────────────────────────────────

    private fun toCsvLine(type: SensorType, sample: SensorSamples): String? = when (type) {
        SensorType.ACC -> {
            val d = sample.acc25Data
            "${d.timestamp},${d.x},${d.y},${d.z}"
        }
        SensorType.ECG -> {
            val d = sample.ecgData
            "${d.timestamp},${d.value},${d.leadOff},${d.maxThresholdMv},${d.minThresholdMv},${d.seq}"
        }
        SensorType.PPG_GREEN_25 -> {
            val d = sample.ppgGreen25Data
            "${d.timestamp},${d.green25},${d.ir25},${d.red25}"
        }
        SensorType.PPG_GREEN_100 -> {
            val d = sample.ppgGreen100Data
            "${d.timestamp},${d.green100},${d.ir100},${d.red100}"
        }
        else -> null
    }

    // ── 저장 경로 결정 ───────────────────────────────────────────────────────

    private fun resolveBaseDir(): File {
        // 1순위: Documents/SamsungServerSdk
        val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val primary = File(documentsDir, ROOT_DIR)
        if (documentsDir.canWrite() || primary.mkdirs()) return primary

        // 2순위: 앱 외부 저장소
        val externalDir = context.getExternalFilesDir(null)
        if (externalDir != null) {
            val secondary = File(externalDir, ROOT_DIR)
            if (secondary.mkdirs() || secondary.exists()) return secondary
        }

        // 3순위: 앱 내부 저장소
        return File(context.filesDir, ROOT_DIR).also { it.mkdirs() }
    }
}
