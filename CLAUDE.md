# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Multi-module Android Gradle project with two library SDKs and two example apps. The SDKs implement a Galaxy Watch biometric data pipeline:

- **`samsung-sdk`** — Galaxy Watch 앱 라이브러리. Samsung Health Sensor API로 센서 데이터를 수집해 Protobuf 바이트로 직렬화, `SensorDataCallback`을 통해 소비자(BLE 전송 등)에 전달.
- **`samsung-server-sdk`** — 폰 앱 라이브러리. Galaxy Watch와 BLE로 연결해 Protobuf 데이터를 수신, CSV 파일로 저장 후 HealthOn 서버에 HTTP 업로드.
- `samsung_sdk_example` / `samsung-server-sdk-example` — 예제 앱 (라이브러리 사용 예시).

## Build Commands

```bash
# 전체 빌드
./gradlew build

# 모듈별 AAR 빌드
./gradlew :samsung-sdk:assembleRelease
./gradlew :samsung-server-sdk:assembleRelease

# 테스트
./gradlew :samsung-sdk:test
./gradlew :samsung-server-sdk:test

# 단일 테스트
./gradlew :samsung-sdk:test --tests "kr.co.hconnect.samsung_sdk.ExampleUnitTest"
```

## local.properties 설정

`bluetooth-sdk-android-v2` Maven 패키지를 GitHub Packages에서 받으므로 필수:

```
githubUsername=<GitHub 사용자명>
githubAccessToken=<GitHub PAT (read:packages 권한)>
```

## 데이터 흐름

```
[Samsung Health API]
        ↓
HealthTrackerManager (samsung-sdk)
        ↓
SensorBufferStorage (배치 누적 → flush)
        ↓
SensorDataCallback.onDataReady(ByteArray)   ← 소비자가 구현 (예: BLE 전송)
        ↓ (BLE NUS 프레이밍: [4B big-endian 길이][Protobuf 바이트])
PacketReassembler (samsung-server-sdk)
        ↓
SessionManager
        ↓
DataWriter (CSV) + ServerSdkCallback + HealthOnClient (HTTP 업로드)
```

## Protobuf 스키마

두 모듈이 동일한 스키마를 각자 보유:
- `samsung-sdk/src/main/proto/sensor_data.proto`
- `samsung-server-sdk/src/main/proto/sensor_data.proto`

스키마 변경 시 **두 파일을 동시에 수정**해야 한다.

핵심 메시지:
- `SensorBufferProto` — `Metadata`(TrackingState + 세션 정보) + `SensorSamples` 목록
- `SensorSamples` — oneof: `Acc25`, `PpgGreen25`, `PpgGreen100`, `Ecg`

## 측정 세션 ID 규칙

| 경로 | sessionId 형식 | 비고 |
|------|----------------|------|
| 주기 측정 | `yyyyMMdd_HHmm` | 슬롯 시각 기준 |
| 즉시 측정 | `on_demand_<timestamp>` | 시작 ms 기준 |
| 수면(protocol8-1) | `yyyyMMdd_HHmmss` (15자) | 반드시 15자 고정 |

## 주요 의존성 (libs.versions.toml)

| 라이브러리 | 용도 |
|-----------|------|
| `samsung-health-sensor-api-1.4.1.aar` | Galaxy Watch 센서 (로컬 AAR) |
| `bluetooth-sdk-android-v2` (GitHub Packages) | BLE 연결 관리 (HCBle) |
| Protobuf Lite | 센서 데이터 직렬화 |
| OkHttp 4.x | HTTP 업로드 |
| DataStore Preferences | SDK 설정 저장 |
| Hilt | 의존성 주입 (예제 앱) |
| Coroutines | 비동기 처리 |
