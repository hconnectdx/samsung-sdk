package kr.co.hconnect.samsung_sdk.state

import android.util.Log
import kr.co.hconnect.samsung_sdk.callback.SensorDataCallback
import kr.co.hconnect.samsung_sdk.data.SdkTrackingState
import kr.co.hconnect.samsung_sdk.proto.*

object TrackingStateSender {

    private const val TAG = "TrackingStateSender"
    private var dataCallback: SensorDataCallback? = null

    fun init(callback: SensorDataCallback) {
        dataCallback = callback
    }

    fun sendStarted(slotMinute: Int): Boolean =
        sendInternal(SdkTrackingState.Start, slotMinute)

    fun sendStartedWithoutSlot(timestamp: Long): Boolean =
        sendInternal(SdkTrackingState.Start, timestamp)

    fun sendFinished(): Boolean =
        sendInternal(SdkTrackingState.Finish, null)

    private fun sendInternal(state: SdkTrackingState, payload: Any?): Boolean {
        val callback = dataCallback
        if (callback == null) {
            Log.e(TAG, "SensorDataCallback not initialized. Call init() first.")
            return false
        }

        val protoState = when (state) {
            is SdkTrackingState.Idle   -> TrackingState.IDLE
            is SdkTrackingState.Start  -> TrackingState.START
            is SdkTrackingState.Finish -> TrackingState.FINISH
        }

        val metaBuilder = Metadata.newBuilder()
            .setTrackingState(protoState)

        if (protoState == TrackingState.START && payload is Int) {
            metaBuilder.setPeriodStartMinute(payload)
        }

        if (protoState == TrackingState.START && payload is Long) {
            metaBuilder.setOnDemandStartTimestamp(payload)
        }

        val proto = SensorBufferProto.newBuilder()
            .setMetadata(metaBuilder.build())
            .build()

        val bytes = proto.toByteArray()
        Log.d(TAG, "[STATE SEND] 상태=$protoState payload=$payload 크기=${bytes.size}B")
        val ok = callback.onDataReady(bytes)
        if (ok) {
            Log.d(TAG, "[STATE SEND OK] $protoState 전송 성공")
        } else {
            Log.e(TAG, "[STATE SEND FAIL] $protoState 전송 실패")
        }
        return ok
    }
}
