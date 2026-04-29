package kr.co.hconnect.samsung_sdk.data

sealed interface TrackingState {
    data object Idle : TrackingState
    data object Start : TrackingState
    data object Finish : TrackingState
}
