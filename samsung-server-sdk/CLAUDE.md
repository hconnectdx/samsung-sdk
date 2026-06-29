# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 모듈 역할

폰 앱에서 Galaxy Watch와 BLE로 연결해 Protobuf 센서 데이터를 수신하고, CSV 파일로 저장 후 HealthOn 서버에 HTTP 업로드하는 Android 라이브러리.

## 공개 API

`SamsungServerSdk` (object) — 유일한 공개 진입점.

```kotlin
// 1) 초기화 (start 전 필수)
SamsungServerSdk.init(
    baseUrl      = "https://mapi-stg.health-on.co.kr/",
    clientId     = "...",
    clientSecret = "...",
    callback     = object : ServerSdkCallback { ... }
)

// 2) 사용자 정보 (protocol2-1 전송 시 사용)
SamsungServerSdk.setHealthOnUser(userSno = 1234, userAge = 30)

// 3) 연결 시작 / 종료
SamsungServerSdk.start(context)   // WatchReceiverService 포그라운드 시작 (API 26+)
SamsungServerSdk.stop(context)    // BLE 해제 + 서비스 중지
```

## 아키텍처

```
SamsungServerSdk (object)
  └── WatchReceiverService (ForegroundService)
        ├── WatchFinder — 페어링된 Galaxy Watch 탐색 (이름 prefix "Galaxy Watch")
        ├── HCBle (bluetooth-sdk-android-v2) — BLE 연결 / MTU 512 협상 / NUS 알림
        ├── PacketReassembler — BLE 청크 조립 → SensorBufferProto or MeasurementType
        ├── SessionManager — 세션 생명주기, 샘플 라우팅, API 전송
        │     ├── DataWriter — CSV 파일 저장
        │     └── HealthOnClient (OkHttp) → Protocol2_1API / Protocol8_1API
        │                                 → SleepStartAPI / SleepStopAPI
        └── ServerSdkCallback — 앱에 이벤트 전달
```

## BLE 프레이밍 (PacketReassembler)

워치가 보내는 프레임 형식:
```
[ 4바이트 big-endian 전체 길이 ] [ 페이로드 ]
```

페이로드는 두 종류:
1. `SensorBufferProto` 바이너리 — 센서 샘플 배치
2. `MEASUREMENT_TYPE:<ECG|SLEEP|STOP>` UTF-8 텍스트 — 측정 시작/종료 알림

텍스트 알림은 `buffer` 상태와 무관하게 **항상 먼저** 판정한다 (Protobuf 조립 미완 상태에서도 SLEEP/STOP을 놓치지 않기 위해).

`bleDispatcher = Dispatchers.IO.limitedParallelism(1)` — BLE Notification 순서 보장을 위해 단일 스레드로 직렬 처리.

## 측정 타입과 API 매핑

| MeasurementType | 세션 종료 시 호출 API | sessionId 형식 |
|-----------------|----------------------|----------------|
| `ECG` (일상) | `POST /poli/day/protocol2-1` | `yyyyMMdd_HHmmss` |
| `SLEEP` (수면) | `POST /poli/sleep/protocol8-1` (1분 청크마다) | `yyyyMMdd_HHmmss` (15자 고정) |
| null (미수신) | protocol2-1 fallback | — |

수면 세션은 워치에서 1분마다 `TrackingState.FINISH`가 발생 → 청크 파일 전송 후 새 청크 시작.
수면 종료 시 `SleepStopAPI` 호출 후 마지막 청크 전송.

## DataWriter 저장 경로

```
Download/SamsungServerSdk/           ← MANAGE_EXTERNAL_STORAGE 권한 있을 때
  └── 2026-04-29/
      └── 15_30_00/
          ├── ACC.csv         (timestamp,x,y,z)
          ├── ECG.csv         (value)
          ├── PPG_GREEN_25.csv  (green,ir,red)
          └── PPG_GREEN_100.csv (green,ir,red)
```

권한 없으면 앱 전용 외부 저장소 → 앱 내부 저장소 순으로 fallback.
`MANAGE_EXTERNAL_STORAGE` 요청은 예제 앱에서 처리.

## HealthOnClient 주의사항

`HttpLoggingInterceptor`는 **반드시 `HEADERS` 레벨만 사용**. `BODY` 레벨로 설정하면 대용량 멀티파트 업로드 시 logcat 출력이 Azure Gateway의 idle timeout(≈20초)을 초과해 504 오류 발생.

응답 바디는 내부 `responseBodyLogInterceptor`가 최대 2,000자까지만 출력.

## ServerSdkCallback 주요 메서드

| 메서드 | 호출 시점 |
|-------|----------|
| `onConnected(deviceName)` | BLE 연결 수립 |
| `onDisconnected()` | BLE 연결 해제 |
| `onTrackingStarted(sessionId)` | 측정 세션 시작 |
| `onTrackingFinished(sessionId)` | 측정 세션 종료 |
| `onMeasurementStarted(sessionId, type)` | `MEASUREMENT_TYPE:*` 텍스트 수신 시 |
| `onSensorData(sessionId, sensorType, samples)` | 샘플 배치 수신 |
| `onStoragePath(path)` | CSV 저장 경로 결정/해제 |
| `onSleepFinished(sessionId, sleepQuality)` | `/poli/sleep/stop` 응답 수신 |
| `onProtocol2_1Result(...)` | `/poli/day/protocol2-1` 전송 결과 |
| `onProtocol8_1Result(...)` | `/poli/sleep/protocol8-1` 전송 결과 |
| `onError(message)` | SDK 내부 오류 |
