package kr.co.hconnect.samsung_sdk.data

sealed interface SdkTrackingState {
    data object Idle : SdkTrackingState
    data object Start : SdkTrackingState
    data object Finish : SdkTrackingState
}
