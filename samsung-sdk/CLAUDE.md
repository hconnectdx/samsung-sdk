# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 모듈 역할

Galaxy Watch 앱에서 Samsung Health Sensor API로 센서 데이터를 수집하는 Android 라이브러리.
수집한 데이터를 Protobuf로 직렬화해 `SensorDataCallback.onDataReady(ByteArray)`로 소비자에 전달한다.
소비자(예제 앱)가 이 바이트를 BLE로 폰에 보내는 구조.

## 공개 API

`SamsungHealthSdk` (object) — 유일한 공개 진입점.

```kotlin
// 필수: 앱 시작 시 한 번
SamsungHealthSdk.init(callback: SensorDataCallback)

// 설정 (DataStore 저장, 재부팅 후 ScheduleReceiver가 읽음)
SamsungHealthSdk.setMeasurementDuration(context, durationMs)
SamsungHealthSdk.setAlarmSlotMinutes(context, intArrayOf(0, 30))   // 기본: [1, 31]
SamsungHealthSdk.setAlarmSensorTypes(context, setOf(SensorType.ACC))

// 주기 측정 (1회 실행)
SamsungHealthSdk.startPeriodicTracking(context, durationMs, slotMinute, sensorTypes)

// 주기 알람 스케줄링 (반복 자동 재스케줄)
SamsungHealthSdk.schedulePeriodicAlarm(context, sensorTypes)
SamsungHealthSdk.cancelPeriodicAlarm(context)

// 즉시 측정 (수동 시작/정지)
SamsungHealthSdk.startOnDemandTracking(context, sensorTypes, timestamp)
SamsungHealthSdk.stopOnDemandTracking(context)

// 상태 관찰
SamsungHealthSdk.trackingState: StateFlow<SdkTrackingState>  // Idle/Start/Finish
```

## 아키텍처

```
SamsungHealthSdk (object)
  ├── TrackerManager (HealthTrackerManager)
  │     └── Samsung Health Sensor API 연결 / 센서별 startTracking
  ├── TrackerProcessor (HealthTrackerProcessorImpl)
  │     ├── 측정 코루틴 관리 (duration timer, flush watcher)
  │     └── SdkTrackingState StateFlow
  └── [static singletons]
        ├── SensorBufferStorage — 센서별 배치 버퍼 → flush → onDataReady
        └── TrackingStateSender — START/FINISH 상태 Protobuf → onDataReady
```

`TrackingService` (ForegroundService) — 측정 생명주기 관리. `ACTION_START` / `ACTION_START_ON_DEMAND` / `ACTION_STOP_ON_DEMAND` Intent로 제어.

`AlarmScheduler` — `AlarmManager.setExactAndAllowWhileIdle`로 슬롯 알람 설정. 권한 없으면 `setAndAllowWhileIdle`로 fallback.

`ScheduleReceiver` (BroadcastReceiver) — 알람 수신 후 `TrackingService.start()` 호출, 다음 슬롯 재스케줄.

`PreferencesUtil` — DataStore 기반 설정 저장 (measurementDuration, alarmSlotMinutes, alarmSensorTypes).

## 센서 배치 크기 (SensorBufferStorage)

| SensorType | 배치 크기 | 샘플링 주파수 |
|-----------|----------|--------------|
| ACC | 300 | 25 Hz |
| PPG_GREEN_25 | 200 | 25 Hz |
| PPG_GREEN_100 | 1200 | 100 Hz |
| ECG | 1500 | 500 Hz |

`HealthTrackerProcessorImpl`은 측정 시간(`durationMs`)에서 각 센서별 예상 배치 수를 계산해, 모든 배치가 도달하면 조기 종료할 수 있다.

## 주의사항

- 측정 시간에 2초 여유(`adjustedDurationMillis = durationMs + 2_000L`)를 추가해 마지막 배치 도달 대기.
- `SensorBufferStorage.shutdown()` 호출 후에는 `appendAll()`이 차단됨 — 측정 종료 시 잔여 데이터를 일괄 flush 후 차단.
- ECG는 온디맨드 측정 시 5초 지연 후 시작 (센서 워밍업).
- `TrackingService`는 `FOREGROUND_SERVICE_TYPE_HEALTH` 필요 (Android 14+).
- `AlarmScheduler`가 정확한 알람 권한(`SCHEDULE_EXACT_ALARM`)을 요구함.
