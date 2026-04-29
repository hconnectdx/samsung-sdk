package kr.co.hconnect.samsung_sdk.buffer

import android.content.Context
import android.util.Log
import kr.co.hconnect.samsung_sdk.callback.SensorDataCallback
import kr.co.hconnect.samsung_sdk.proto.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.EnumMap

private const val TAG = "SensorBufferStorage"

object SensorBufferStorage {

    private val _flushEvents = MutableSharedFlow<SensorType>(extraBufferCapacity = 64)
    val flushEvents = _flushEvents.asSharedFlow()
    private val buffers = EnumMap<SensorType, MutableList<SensorSamples>>(SensorType::class.java)

    private var uploadJob: Job? = null
    private var dataCallback: SensorDataCallback? = null
    private val lock = Any()

    @Volatile private var accepting: Boolean = true

    private fun chunkSizeFor(type: SensorType): Int = when (type) {
        SensorType.ACC          -> 300
        SensorType.PPG_GREEN_25 -> 200
        SensorType.PPG_GREEN_100-> 1200
        SensorType.ECG          -> 1500
        else                    -> 300
    }

    fun initialize(callback: SensorDataCallback) {
        dataCallback = callback
    }

    private val flushedBatches = EnumMap<SensorType, Int>(SensorType::class.java)

    fun getFlushedBatchCount(type: SensorType): Int = synchronized(lock) {
        flushedBatches[type] ?: 0
    }

    fun resetFlushedBatchCounts() = synchronized(lock) {
        flushedBatches.clear()
    }

    fun appendAll(context: Context, type: SensorType, samples: List<SensorSamples>) {
        if (samples.isEmpty()) return
        if (!accepting) {
            Log.w(TAG, "[BLOCKED] shutdown 이후 도착한 데이터 무시 [$type] ${samples.size}샘플")
            return
        }

        var shouldFlush = false

        synchronized(lock) {
            val list = buffers.getOrPut(type) { ArrayList(maxOf(samples.size, 512)) }
            list.addAll(samples)
            if (list.size >= chunkSizeFor(type)) { shouldFlush = true }
        }
        if (shouldFlush) { flushType(type) }
    }

    private fun flushType(type: SensorType) {
        val chunkSize = chunkSizeFor(type)

        while (true) {
            val chunk: List<SensorSamples>

            synchronized(lock) {
                val list = buffers[type]
                if (list == null || list.size < chunkSize) return
                chunk = list.take(chunkSize)
                list.subList(0, chunkSize).clear()
            }

            val metadata = Metadata.newBuilder()
                .setTrackingState(TrackingState.NONE)
                .build()

            val payload = SensorBufferProto.newBuilder()
                .setMetadata(metadata)
                .addAllSamples(chunk)
                .build()
                .toByteArray()

            val batchNo = (flushedBatches[type] ?: 0) + 1
            logRawSamples(type, batchNo, chunk)

            val startTime = System.currentTimeMillis()
            val ok = dataCallback?.onDataReady(payload) == true
            val duration = System.currentTimeMillis() - startTime

            if (ok) {
                Log.d(TAG, "[SEND OK] 배치#$batchNo [$type] 전송완료 소요=${duration}ms")
                synchronized(lock) {
                    flushedBatches[type] = batchNo
                }
                _flushEvents.tryEmit(type)
            } else {
                synchronized(lock) {
                    buffers.getOrPut(type) { mutableListOf() }.addAll(0, chunk)
                }
                Log.w(TAG, "[SEND FAIL] 배치#$batchNo [$type] 전송실패 — 버퍼 복원")
                break
            }
        }
    }

    private fun extractTimestamp(sample: SensorSamples): Long = when {
        sample.hasAcc25Data()       -> sample.acc25Data.timestamp
        sample.hasPpgGreen25Data()  -> sample.ppgGreen25Data.timestamp
        sample.hasPpgGreen100Data() -> sample.ppgGreen100Data.timestamp
        sample.hasEcgData()         -> sample.ecgData.timestamp
        else                        -> -1L
    }

    private fun logRawSamples(type: SensorType, batchNo: Int, chunk: List<SensorSamples>) {
        val preview = (chunk.take(5) + chunk.takeLast(1)).distinctBy { extractTimestamp(it) }
        val sb = StringBuilder()
        sb.appendLine("[RAW] 배치#$batchNo [$type] 총 ${chunk.size}샘플 — 미리보기(첫5+끝1):")
        preview.forEachIndexed { i, s ->
            val line = when {
                s.hasAcc25Data() -> with(s.acc25Data) {
                    "  [$i] ts=$timestamp | x=$x y=$y z=$z"
                }
                s.hasPpgGreen25Data() -> with(s.ppgGreen25Data) {
                    "  [$i] ts=$timestamp | green=$green25 ir=$ir25 red=$red25"
                }
                s.hasPpgGreen100Data() -> with(s.ppgGreen100Data) {
                    "  [$i] ts=$timestamp | green=$green100 ir=$ir100 red=$red100"
                }
                s.hasEcgData() -> with(s.ecgData) {
                    "  [$i] ts=$timestamp | ecg=${value}mV leadOff=$leadOff seq=$seq"
                }
                else -> "  [$i] unknown type"
            }
            sb.appendLine(line)
        }
        Log.d(TAG, sb.toString().trimEnd())
    }

    fun clear() {
        accepting = true
        synchronized(lock) {
            buffers.forEach { (_, v) -> v.clear() }
            flushedBatches.clear()
        }
        Log.d(TAG, "Buffers cleared — accepting 재개.")
    }

    fun stopUploadJob() {
        uploadJob?.cancel()
        uploadJob = null
        Log.d(TAG, "Upload job stopped.")
    }

    fun shutdown() {
        accepting = false
        Log.d(TAG, "SensorBufferStorage.shutdown() start — 이후 appendAll() 차단됨")

        val callback = dataCallback
        if (callback == null) {
            Log.e(TAG, "SensorDataCallback is null. Skip final flush.")
            return
        }

        val snapshot: Map<SensorType, List<SensorSamples>> = synchronized(lock) {
            buffers.mapValues { (_, v) -> v.toList() }
        }

        snapshot.forEach { (type, list) ->
            if (list.isNotEmpty()) {
                val firstTs = list.firstOrNull()?.let { extractTimestamp(it) }
                val lastTs  = list.lastOrNull()?.let { extractTimestamp(it) }
                Log.d(TAG, "[SHUTDOWN FLUSH] [$type] 잔여샘플=${list.size} | 시작ts=$firstTs | 끝ts=$lastTs")

                val metadata = Metadata.newBuilder()
                    .setTrackingState(TrackingState.NONE)
                    .build()

                val bytes = SensorBufferProto.newBuilder()
                    .setMetadata(metadata)
                    .addAllSamples(list)
                    .build()
                    .toByteArray()

                val ok = callback.onDataReady(bytes)
                if (ok) {
                    synchronized(lock) { buffers[type]?.clear() }
                    Log.d(TAG, "[SHUTDOWN FLUSH OK] [$type] ${bytes.size}B 전송완료")
                } else {
                    Log.w(TAG, "[SHUTDOWN FLUSH FAIL] [$type] 전송실패 — ${list.size}개 유실 가능")
                }
            }
        }
        Log.d(TAG, "SensorBufferStorage.shutdown() done")
    }
}
