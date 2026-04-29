package kr.co.hconnect.samsung_server_sdk.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log

private const val TAG = "WatchFinder"

/**
 * 시스템에서 Galaxy Watch를 탐색하는 유틸리티.
 *
 * 탐색 순서:
 * 1) 현재 GATT로 연결된 기기 중 이름이 [NusConstants.WATCH_NAME_FILTER]를 포함하는 기기
 * 2) 페어링(본딩) 목록 중 동일 조건의 기기
 */
@SuppressLint("MissingPermission")
internal object WatchFinder {

    fun find(context: Context): BluetoothDevice? {
        val bluetoothManager =
            context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                ?: return null

        val adapter = bluetoothManager.adapter ?: return null

        // 1. 현재 GATT 연결된 기기에서 탐색
        val connected = bluetoothManager
            .getConnectedDevices(BluetoothProfile.GATT)
            .find { it.isWatch() }

        if (connected != null) {
            Log.d(TAG, "연결된 워치 발견: ${connected.name} (${connected.address})")
            return connected
        }

        // 2. 페어링 목록에서 탐색
        val bonded = adapter.bondedDevices?.find { it.isWatch() }
        if (bonded != null) {
            Log.d(TAG, "페어링된 워치 발견: ${bonded.name} (${bonded.address})")
            return bonded
        }

        Log.e(TAG, "Galaxy Watch를 찾을 수 없음 (연결된 기기 / 페어링 목록 모두 없음)")
        return null
    }

    private fun BluetoothDevice.isWatch(): Boolean =
        name?.contains(NusConstants.WATCH_NAME_FILTER) == true
}
