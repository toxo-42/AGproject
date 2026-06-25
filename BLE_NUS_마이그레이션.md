# BLE 통신 구조 변경: 커스텀 GATT → Nordic UART Service (NUS)

> 대상 파일: `app/src/main/java/com/example/agproject/BleService.kt`
> 변경 성격: 통신 프로토콜 골격 교체 + 에러 처리 구조 재설계(예시 1종만 구현)

---

## 1. 왜 바꿨나

기존에는 펌웨어 측에서 정한 **커스텀 UUID 서비스 1개 + 특성 3채널**(`735e` 데이터, `735f` 에러제어)로 통신했다.
이를 표준 **Nordic UART Service(NUS)** 로 옮기면서:

- 간헐적인 제어·경고 정보는 **문자열**로 주고받고(개발 편의),
- 대용량 데이터는 별도 **raw 바이너리 attr** 하나를 추가해 분리했다.

또한 문자열로 들어오는 **에러 처리 구조 자체를 갈아엎을 예정**이라, 이번엔 골격만 잡고 예시 1종(`MODULE_ERR`)만 구현했다.

---

## 2. 채널 매핑 (Before → After)

| 역할 | Before (커스텀) | After (NUS) | 방향 |
|---|---|---|---|
| 서비스 | `d74d5c87-...-917301` | `6E400001-B5A3-F393-E0A9-E50E24DCCA9E` | - |
| 문자열 수신 | `...735e` (notify) | **TX** `6E400003-...` | 기기 → 폰 (Notify) |
| 폰→기기 전송 | `...735e`(시간동기) + `...735f`(0xAA/0xAB) | **RX** `6E400002-...` (단일) | 폰 → 기기 (Write) |
| 대용량 raw | (없음) | **RAW** `6E400004-...` ⚠️임시 | 기기 → 폰 (Notify) |
| CCCD | `00002902-...` | `00002902-...` (동일) | notify on/off |

> ⚠️ `RAW`(`6E400004`)는 **임시 UUID**다. 펌웨어 쪽 실제 값으로 `BleService.kt`의 `companion object`에서 교체해야 한다.

---

## 3. 주요 변경 내역

### 3-1. UUID 상수 통합
흩어져 있던 `UUID.fromString(...)` 하드코딩을 `companion object` 한 곳으로 모았다.
- `NUS_SERVICE`, `NUS_RX`, `NUS_TX`, `RAW_DATA`, `CCCD`

### 3-2. write 함수 단일화
`writeToModule()`(735e) + `writeToErrorCharacteristic()`(735f) → **`writeToRx()` 하나로 통합**.
NUS에서는 폰→기기 전송이 RX 한 채널이므로, 시간 동기화·`0xAA`(에러 해제)·`0xAB`(용량 확인)가 전부 이 함수를 탄다.

### 3-3. notify 구독을 큐 기반 직렬 처리
GATT 작업은 한 번에 하나만 처리되므로, 디스크립터 write를 연속으로 쏘면 뒤 작업이 누락된다.
이를 **구독 큐(`notifyQueue`) + `onDescriptorWrite` 콜백 체이닝**으로 해결.

```
onServicesDiscovered
  → 큐에 [TX, RAW] 적재 → subscribeNext()
      → enableNotification(TX)
        → onDescriptorWrite 콜백 → subscribeNext()
            → enableNotification(RAW)
              → onDescriptorWrite 콜백 → subscribeNext()
                  → 큐 빔 → sendTimeSyncBinary()  // RX로 전송
```

> 기존의 불안정한 `Handler.postDelayed(..., 500)` 시간동기 트리거는 제거됨. 이제 "모든 구독 완료 후"에 정확히 전송된다.

### 3-4. 수신 분기를 특성 UUID 기준으로
`onCharacteristicChanged`에서 어떤 특성에서 온 데이터인지로 분기:

```kotlin
when (characteristic.uuid) {
  NUS_TX   -> handleTextMessage(...)  // 문자열
  RAW_DATA -> handleRawData(...)      // 바이너리 (현재 stub)
}
```

### 3-5. 에러 처리 구조 재설계 (예시 1종)
기존의 `if (contains("MODULE_ERR")) {...} if (contains("PEDAL_ERR")) {...} ...` 나열식을
**`when` 분기 + 핸들러 함수 분리** 형태로 바꿨다.

```kotlin
private fun handleTextMessage(msg: String) {
  when {
    msg.contains("MODULE_ERR") -> onModuleError()   // ← 예시로 구현된 1종
    // TODO: SD_SMALL 등 새 구조에 맞춰 케이스 추가
  }
}
```

- `onModuleError()`: 중복 수신은 사용자 확인 전까지 무시(`isErrorDialogShowing`), 음성·알림·헤드업·브로드캐스트 수행.
- **SD_SMALL 감지 로직은 이번에 제거**됨(새 구조에서 재작성 예정).

---

## 3-6. 엑셀(페달) 오조작: MCU 감지 → 안드로이드 로컬 판단으로 이관

기존엔 **MCU가 엑셀 오조작을 직접 감지**해서 `PEDAL_ERR` 문자열로 보냈고, 앱은 그걸 받아 풀스크린 경고(`CriticalActivity`)를 띄웠다.
새 구조는 **MCU가 ~50Hz 센서 데이터를 raw로 송신**하고, **안드로이드가 로컬에서 오조작을 판단**한다.

이에 따라 BLE 측 엑셀 오류 관련 코드를 **전부 제거**했다:

| 제거 대상 | 위치 |
|---|---|
| `PEDAL_ERR` 문자열 감지 | `handleTextMessage` (이전 단계에서 제거됨) |
| 주석 처리된 `ACTION_DEBUG_PEDAL` 디버그 블록 | `onStartCommand` |
| `showCriticalNotification()` (풀스크린 경고 알림) | `BleService` |
| `playVoiceFile`의 `"PEDAL"` 분기 | `BleService` |
| 죽은 `addAction("ACTION_PEDAL_CRITICAL")` | `MainActivity` (핸들러 없던 dead code) |
| **`CriticalActivity.kt` + `activity_critical.xml`** | 파일 삭제 + Manifest 등록 제거 |

> raw 데이터의 입력 진입점은 `handleRawData()`. 향후 디코딩→윈도우 버퍼링→로컬 판단→오조작 시 경고 트리거 순으로 채운다.

### 로컬 판단(파이썬) 아키텍처 방향
- **BLE 센트럴은 안드로이드가 유지**한다. 파이썬이 BLE를 직접 잡는 구조(별도 PC/Pi)는 폰이 데이터 경로에서 빠지고 경고용 2차 통신이 또 필요해져 **비추천**.
- 권장: 데이터는 폰이 BLE로 받고(`handleRawData`), **판단만 모델로 처리**.
  - **운영용**: 파이썬은 학습/프로토타입에만 쓰고, 모델을 **TFLite/ONNX로 export → 코틀린 온디바이스 추론** (런타임 파이썬 없음).
  - **프로토타입용**: **Chaquopy**로 APK에 파이썬 임베드, 버퍼 윈도우를 파이썬 함수에 넘겨 판정 회수.

---

## 4. 남은 작업 / 주의사항

- [ ] **`RAW_DATA` UUID 확정**: 펌웨어 측 실제 값으로 교체.
- [ ] **`handleRawData()` 구현**: 현재는 수신 바이트 수만 로깅. ~50Hz 센서 데이터 디코딩→윈도우 버퍼링→로컬 판단(엑셀 오조작)→경고 트리거 필요.
- [ ] **로컬 판단 모델 통합 방식 결정**: TFLite/ONNX 온디바이스(운영) vs Chaquopy 임베드(프로토타입).
- [ ] **새 에러 구조 정의 후** `handleTextMessage`의 `when`에 케이스 추가(SD 등).
- [ ] `voice_pedal_male/female.mp3`는 현재 `DeviceManagerActivity`의 **성별 음성 미리듣기 샘플**로만 쓰임(엑셀 오류 로직 아님). 미리듣기 음성을 교체하면 이 리소스도 정리 가능.
- [ ] `warning_red_blink` 드로어블은 `CriticalActivity` 삭제로 **미사용** 상태(빌드엔 무해). 정리 여부 선택.
- [ ] `isCapacityDialogShowing`은 현재 **죽은 상태**: SD 감지를 뺐기 때문에 `true`로 세팅되는 지점이 없음. 단, `handleCapacityClearSequence`(0xAB 전송)와 `onStartCommand`의 `ACTION_CLEAR_CAPACITY` 경로는 살아 있으므로, SD 케이스 복원 시 함께 연결하면 됨.

### 빌드 검증
이 프로젝트에는 gradle 래퍼(`gradlew`)가 없어 CLI 컴파일 검증을 하지 못했다.
**Android Studio에서 빌드해 컴파일 확인 필요.**

---

## 5. 펌웨어 연동 규격 (Peripheral Contract) — 대상: ESP32-C3

> 안드로이드(Central)와 ESP32-C3(Peripheral) 간 GATT 계약.
> ESP32-C3는 BLE 5.0(LE 전용)이며 보통 **NimBLE** 스택을 쓴다(ESP-IDF NimBLE / NimBLE-Arduino). 아래 속성 플래그는 NimBLE 기준 병기.

### 5-1. GATT 구조 / Characteristic 속성

- **Service UUID**: `6E400001-B5A3-F393-E0A9-E50E24DCCA9E` (Nordic UART Service)

| 이름 | UUID | 속성 | NimBLE 플래그 | 방향 |
|---|---|---|---|---|
| **TX** | `6E400003-...` | Notify | `NOTIFY` | ESP32 → 폰 (문자열) |
| **RX** | `6E400002-...` | **Write (응답O)** | `WRITE` | 폰 → ESP32 (명령/시간) |
| **RAW** | `6E400004-...` ⚠️임시 | Notify | `NOTIFY` | ESP32 → 폰 (센서 raw) |

- ⚠️ **RX는 반드시 Write Request(응답 있는 Write) 지원**. 앱이 `WRITE_TYPE_DEFAULT`로 보내므로, `WRITE_NO_RSP`(쓰기 무응답)만 열어두면 전송 실패한다. NimBLE에서 `WRITE` 플래그(필요시 `WRITE | WRITE_NO_RSP` 둘 다)를 켤 것.
- **TX / RAW에는 CCCD(0x2902)가 자동 포함**돼야 한다. NimBLE는 `NOTIFY` 속성 선언 시 CCCD를 자동 생성하므로 별도 작업 불필요. 앱이 여기에 notify-enable을 기록하면 송신 시작.
- ⚠️ **RAW의 UUID(`...6E400004`)는 안드로이드 측 임시값.** 펌웨어가 실제 값을 정하면 앱 `companion object`도 같이 교체해야 한다. (이 칸을 펌웨어가 확정)

### 5-2. RX 수신 페이로드 (폰 → ESP32) — 펌웨어가 파싱

RX로 들어오는 데이터는 **길이로 종류를 구분**한다.

| 길이 | 의미 | 포맷 |
|---|---|---|
| **16 byte** | 시간 동기화 | `int64 sec` + `int64 usec`, **little-endian** |
| **1 byte = 0xAA** | 에러 해제 확인 | 단일 명령 바이트 |
| **1 byte = 0xAB** | 용량 부족 경고 확인 | 단일 명령 바이트 |

- 시간 동기화 상세: 앱이 **연결 직후(notify 구독 완료 후) 1회** `gettimeofday` 형식으로 전송.
  - 바이트 0–7 = `sec`(유닉스 초), 바이트 8–15 = `usec`(마이크로초).
  - **ESP32-C3는 RISC-V로 네이티브 little-endian** → 수신 버퍼를 `struct { int64_t sec; int64_t usec; }` 에 그대로 `memcpy` 가능(바이트 스왑 불필요).
- 명령 바이트(`0xAA`/`0xAB`)는 앱에서 사용자가 경고 팝업 "확인"을 눌렀을 때 발생. 펌웨어는 이 신호로 해당 경고 상태를 클리어/재개하면 된다.

### 5-3. TX 송신 문자열 (ESP32 → 폰) — **양측 합의 필요**

앱은 수신 문자열을 `String.contains(토큰)` 으로 매칭한다. 인코딩/토큰을 합의해서 확정할 것.

| 토큰 | 상태 | 앱 동작 | 비고 |
|---|---|---|---|
| `MODULE_ERR` | 구현됨 | 음성+헤드업 알림+팝업 | |
| `SD_SMALL` | 앱 TODO | (재작성 예정) | SD 용량 부족 |
| (추가?) | - | - | 합의 칸 |

- **합의 필요 항목**: ① 인코딩(ASCII/UTF-8 권장 ASCII) ② 종결자 유무(`\n` 등) ③ 한 notify에 한 토큰만 보낼지.
- ⚠️ **엑셀/페달 오조작은 더 이상 문자열로 보내지 않는다.** (MCU 감지 → 안드로이드 로컬 판단으로 이관. §3-6 참고)

### 5-4. RAW 패킷 포맷 (ESP32 → 폰) — **펌웨어가 정의해서 채울 칸**

엑셀 오조작 로컬 판단용 ~50Hz 센서 데이터. 아래 항목을 펌웨어가 확정해 알려주면 앱 `handleRawData()` 디코딩을 맞춘다.

| 항목 | 값 | 비고 |
|---|---|---|
| 샘플당 바이트 수 | ? | 예: int16=2B, float32=4B |
| 채널 수 | ? | 예: x/y/z = 3 |
| 엔디안 | ? | little-endian 권장(ESP32 네이티브) |
| 패킷당 샘플 수 | ? | notify 1건에 몇 샘플 |
| 헤더/타임스탬프 유무 | ? | 패킷 선두 메타 |
| 송신 주기 | ~50Hz(간헐) | |

### 5-5. MTU / 연결 파라미터

- ⚠️ **현재 앱은 `requestMtu()`를 호출하지 않음** → ATT MTU 기본 23 → **notify 페이로드 최대 20 byte**.
  - RAW를 효율적으로 흘리려면 MTU 확장 필요. **합의 사항**: 앱이 연결 후 `requestMtu(N)` 호출하도록 추가할지, 그리고 ESP32가 받을 최대 MTU.
  - ESP32-C3/NimBLE는 ATT MTU **최대 517**까지 가능(`NimBLEDevice::setMTU()` 또는 IDF `CONFIG_BT_NIMBLE_ATT_PREFERRED_MTU`). 예: 247로 합의 시 1 notify에 ~244 byte.
- **연결 주기(Connection Interval)**: 50Hz 데이터면 짧은 interval 선호. ESP32가 connection parameter update를 요청하거나, 앱이 high-priority 연결을 요청하는 방안 합의.
- ⚠️ 위 두 항목(`requestMtu`, 연결 우선순위)은 **현재 안드로이드 코드에 미구현** → RAW 본격 구현 시 함께 추가해야 함.

### 5-6. 광고(Advertising)

- 앱은 **MAC 주소로 필터**(`ScanFilter.setDeviceAddress`)하므로 펌웨어는 일반 광고만 하면 됨.
- 단, 최초 기기 등록 화면(`ScanActivity`)은 **device name이 있는 기기만 목록 노출** → 광고/스캔응답에 **device name 포함 필요**.
- 광고 패킷에 NUS Service UUID(`6E400001-...`) 포함 권장(앱 필수는 아니나 디버깅 편의).
