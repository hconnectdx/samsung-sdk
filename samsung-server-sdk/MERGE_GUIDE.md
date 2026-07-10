# samsung-server-sdk 로컬 변경 및 머지 가이드

## 문서 목적

`feature/ecg-ppg-data-count-modify` 브랜치에서 추가된 아래 3개 커밋의 변경 범위와 동작을 설명한다.
다른 브랜치의 BLE 연결·MTU·NUS 설정 변경과 합칠 때 같은 코드를 중복 적용하거나, 한쪽의 복구 경로를
실수로 제거하지 않기 위한 머지 참고 문서다.

| 커밋 | 핵심 변경 |
|---|---|
| `3e5709f` | 워치 청크 프로브 응답, 큐 기반 MTU 협상 제거, 재조립 정체 리셋 |
| `71094ee` | `PROBE_ACK`를 no-response write로 변경, 프로브 시간 동안 write 큐 확보 |
| `7542226` | 연결 직후 raw MTU 요청을 선제 발사하고 실패 시 프로브로 폴백 |

이번 3개 커밋이 수정한 파일은 다음 4개뿐이다.

- `samsung-server-sdk/build.gradle.kts`
- `samsung-server-sdk/src/main/java/kr/co/hconnect/samsung_server_sdk/ble/NusConstants.kt`
- `samsung-server-sdk/src/main/java/kr/co/hconnect/samsung_server_sdk/ble/PacketReassembler.kt`
- `samsung-server-sdk/src/main/java/kr/co/hconnect/samsung_server_sdk/service/WatchReceiverService.kt`

`SessionManager`, `DataWriter`, 센서 샘플 저장 개수, CSV 생성 및 서버 전송 로직은 이 세 커밋에서
수정하지 않았다.

## 전체 동작 흐름

세 커밋이 모두 적용된 로컬 브랜치 기준 연결 및 청크 결정 흐름은 다음과 같다.

1. 폰이 워치와 연결되면 `BluetoothGatt.requestMtu(512)`를 GATTController 작업 큐 밖에서 한 번 호출한다.
2. 선제 MTU 요청이 성공하면 큰 notification을 정상 수신한다.
3. 요청이 스택에 흡수되거나 실패해도 GATT 작업 큐를 점유하지 않으므로 서비스 검색과 CCCD 구독은 계속된다.
4. CCCD 구독 후 워치 Peripheral SDK가 `PROBE:<seq>:<N>:` raw notification을 보낸다.
5. 폰은 프로브를 일반 센서 프레임으로 넘기지 않고, 실제 수신한 바이트 수를
   `PROBE_ACK:<seq>:<수신 바이트 수>`로 회신한다.
6. ACK는 `WRITE_TYPE_NO_RESPONSE`로 전송한다. 워치는 요청 크기와 실제 수신 크기가 같은 후보만 청크 크기로 채택한다.
7. 프로브가 진행되는 3.5초 동안 `MOBILE_BLE_SERVICE_STATUS:RUNNING` 명령을 지연해 ACK가 write 큐에서 밀리지 않게 한다.
8. 센서 프레임이 중간에 끊겨 미완성 버퍼가 남은 뒤 5초 이상 수신 공백이 생기면 버퍼를 폐기하고 다음 프레임부터 재동기화한다.

선제 MTU와 프로브는 서로 대체 관계가 아니라 함께 유지해야 하는 성공 경로와 폴백 경로다.

## 커밋별 상세 변경

### 커밋 `3e5709f`

#### `NusConstants.kt`

- `PROBE_PREFIX = "PROBE:"` 추가
- `PROBE_ACK_PREFIX = "PROBE_ACK:"` 추가
- 프로브 프레임은 4바이트 길이 헤더가 없는 raw notification이다.
- 스택에서 데이터가 20바이트로 절단되어도 접두사와 sequence를 읽을 수 있도록 설계되었다.

#### `WatchReceiverService.kt`

- 기존 `GATTController.requestMtu()` 기반 MTU 요청과 타임아웃 재시도를 제거했다.
- 큐 기반 MTU 요청은 `onMtuChanged`가 오지 않으면 큐 head에 영구 잔류해 CCCD write와 이후 작업을 모두 막을 수 있었다.
- `onReceive`에서 `PROBE:` 패킷을 먼저 판별한다.
- 프로브는 `PacketReassembler`에 전달하지 않아 센서 프레임 버퍼가 오염되지 않도록 한다.
- sequence를 추출하고 실제 notification 수신 크기로 `PROBE_ACK`를 생성한다.

#### `PacketReassembler.kt`

- 마지막 `feed()` 시각을 `SystemClock.elapsedRealtime()`으로 기록한다.
- 미완성 버퍼가 있는 상태에서 5초 이상 수신 공백이 발생하면 기존 버퍼를 폐기한다.
- `reset()`에서도 마지막 수신 시각을 함께 초기화한다.
- 목적은 일부 청크가 유실된 뒤 다음 프레임을 이전 미완성 프레임에 계속 이어 붙이는 영구 어긋남을 막는 것이다.

### 커밋 `71094ee`

#### `PROBE_ACK` 전송 방식

- 일반 `writeToWatch()` 대신 `HCBle.writeCharacteristic(..., WRITE_TYPE_NO_RESPONSE)`를 직접 사용한다.
- ACK는 MTU 23에서도 한 PDU에 들어가는 짧은 문자열이므로 no-response write에 적합하다.
- write-with-response가 워치에서 status 133으로 거부되거나 응답 경로가 지연돼도 ACK가 막히지 않게 한다.

#### 프로브 write 큐 보호

- `PROBE_WINDOW_MS = 3_500L`을 추가했다.
- CCCD 구독 직후 보내던 `MOBILE_BLE_SERVICE_STATUS:RUNNING`을 3.5초 뒤로 지연했다.
- 해당 명령은 33바이트라 기본 MTU에서 write 실패·재시도가 발생하면 내부 큐를 수 초간 점유할 수 있다.
- 지연 전송 시점에 연결 주소가 여전히 같은지 확인해 끊어진 연결로 명령을 보내지 않는다.

### 커밋 `7542226`

#### 연결 직후 선제 MTU 요청

- `STATE_CONNECTED` 처리 직후 `bluetoothGatt.requestMtu(512)`를 직접 한 번 호출한다.
- EATT 채널이 완전히 수립되기 전 짧은 구간에서 레거시 bearer의 MTU 교환을 먼저 성공시키기 위한 시도다.
- 반드시 `GATTController.requestMtu()`가 아닌 raw `BluetoothGatt` 호출을 유지해야 한다.
- raw 호출은 SDK 작업 큐에 `MtuReq`를 넣지 않으므로 콜백이 오지 않아도 CCCD 구독과 프로브 ACK를 막지 않는다.
- 선제 요청 실패 시 별도 재시도하지 않고 앞선 두 커밋에서 추가한 프로브 경로가 그대로 폴백 역할을 한다.

## 파일별 머지 충돌 주의사항

### `WatchReceiverService.kt` — 충돌 가능성 높음

다른 브랜치에서 연결 콜백, MTU 협상, `onReceive`, `setupNus`, `writeToWatch`를 수정했다면 같은 구간에서
충돌할 가능성이 높다. 머지 결과에서 다음 조건을 모두 확인해야 한다.

- `STATE_CONNECTED`에서 raw `bluetoothGatt.requestMtu(512)`를 호출한다.
- 큐 기반 `controller.requestMtu()`와 MTU 타임아웃 재시도 로직을 다시 넣지 않는다.
- raw MTU 요청 실패가 연결 중단이나 재시도 큐 등록으로 이어지지 않는다.
- `onReceive`에서 프로브 판별이 `PacketReassembler.feed()`보다 먼저 실행된다.
- `PROBE_ACK`는 `WRITE_TYPE_NO_RESPONSE`로 전송한다.
- `CMD_SERVICE_RUNNING`은 CCCD 구독 직후가 아니라 3.5초 뒤에 전송한다.
- connection priority HIGH/유휴 시 BALANCED 복귀 로직은 그대로 유지한다.

부장님 브랜치에도 MTU 수정이 있다면, 아래 방식은 동시에 남기면 안 된다.

```text
금지: raw requestMtu() + GATTController.requestMtu() 큐 등록을 둘 다 수행
유지: raw requestMtu() 1회 + 실패 시 프로브 폴백
```

### `PacketReassembler.kt` — 충돌 가능성 중간

다른 브랜치에 버퍼 타임아웃이나 재동기화 로직이 있다면 중복 타이머를 두지 말고 하나로 합친다.
최종 구현은 다음 의미를 만족해야 한다.

- 완성되지 않은 버퍼가 있을 때만 공백 시간을 검사한다.
- monotonic clock인 `elapsedRealtime()`을 사용한다.
- 5초 이상 공백이면 미완성 버퍼만 초기화하고 새 입력 데이터는 정상 처리한다.
- 명시적 `reset()` 시 버퍼와 시간 상태를 함께 초기화한다.

### `NusConstants.kt` — 충돌 가능성 낮음

동일한 `PROBE_PREFIX`, `PROBE_ACK_PREFIX`가 이미 있다면 중복 선언하지 않는다. 문자열 값은 워치 Peripheral SDK와
프로토콜이므로 임의로 변경하면 안 된다.

### `build.gradle.kts` — 버전 충돌 가능성 높음

세 커밋에는 로컬 빌드 구분을 위한 버전 변경도 포함되어 있다. 이 번호는 실제 배포 버전을 확정한 것이 아니다.
머지할 때 로컬 번호를 그대로 채택하거나 기존 실제 버전을 되돌리지 말고, 부장님 작업 브랜치의 배포 정책에 따라
최종 버전을 별도로 결정한다. 기능 코드와 버전 충돌 해결은 분리해서 검토하는 것이 안전하다.

## 머지 후 권장 검증

- 연결 직후 로그에 raw 선제 MTU 요청 결과가 한 번만 출력되는지 확인
- 성공 경로: 워치와 폰 로그에서 MTU 517 협상 확인
- 폴백 경로: `PROBE` 수신 후 700ms 안에 no-response `PROBE_ACK`가 회신되는지 확인
- `PROBE` 데이터가 `PacketReassembler`의 raw 센서 로그나 버퍼에 포함되지 않는지 확인
- `CMD_SERVICE_RUNNING`이 CCCD 구독 약 3.5초 후 전송되는지 확인
- 일부 청크를 의도적으로 끊은 뒤 5초 후 다음 정상 프레임이 다시 파싱되는지 확인
- BLE 연결, 센서 CSV 저장, FINISH 처리 및 protocol API 전송이 기존대로 동작하는지 회귀 확인

## 이번 문서에 포함하지 않은 작업

- ECG CSV를 30,000개로 제한하는 변경
- `SessionManager.routeSamples()` 변경
- Peripheral SDK의 512바이트 notification 상한 수정
- 워치 앱의 포그라운드 서비스 재시작 정책 변경

위 항목들은 이 브랜치의 세 커밋과 별도 작업이므로 이번 머지 범위와 혼합하지 않는다.
