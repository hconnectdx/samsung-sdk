package kr.co.hconnect.samsung_sdk.state

import android.util.Log
import kr.co.hconnect.samsung_sdk.callback.SensorDataCallback
import kr.co.hconnect.samsung_sdk.data.TrackingState as ServiceState
import kr.co.hconnect.samsung_sdk.proto.*

object TrackingStateSender {

    private const val TAG = "TrackingStateSender"
    lateinit var dataCallback: SensorDataCallback

    fun init(callback: SensorDataCallback) { dataCallback = callback }

    fun sendStarted(slotMinute: Int): Boolean =
        sendInternal(ServiceState.Start, slotMinute)

    fun sendStartedWithoutSlot(timestamp: Long): Boolean =
        sendInternal(ServiceState.Start, timestamp)

    fun sendFinished(): Boolean =
        sendInternal(ServiceState.Finish, null)

    private fun sendInternal(state: ServiceState, payload: Any?): Boolean {
        check(TrackingStateSender::dataCallback.isInitialized) {
            "TrackingStateSender not initialized. Call init() first."
        }

        val protoState = when (state) {
            is ServiceState.Idle   -> TrackingState.IDLE
            is ServiceState.Start  -> TrackingState.START
            is ServiceState.Finish -> TrackingState.FINISH
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
        val ok = dataCallback.onDataReady(bytes)
        if (ok) {
            Log.d(TAG, "[STATE SEND OK] $protoState 전송 성공")
        } else {
            Log.e(TAG, "[STATE SEND FAIL] $protoState 전송 실패")
        }
        return ok
    }
}
