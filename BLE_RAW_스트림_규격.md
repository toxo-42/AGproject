# BLE RAW 센서 스트림 규격 (펌웨어 → 안드로이드)

> 대상: ESP32-C3(Peripheral) ↔ Android(Central)
> 이 문서는 펌웨어에 **실제 구현된 값**을 기준으로 작성됨. `BLE_NUS_마이그레이션.md` §5-4(RAW 포맷)·§5-5(MTU)의 빈칸을 채운다.
> 펌웨어 구현 위치: `NewC3/main/BLE.cpp`, `NewC3/main/MCU_Comm.cpp`, `NewC3/main/main.cpp`

---

## 1. 데이터 흐름 개요

```
STM32(센서)  ──UART 200Hz──▶  ESP32-C3  ──BLE RAW Notify 50Hz(배치)──▶  Android
                                  └─ 4샘플 묶어서 1 notify
```

- STM32가 센서를 **200Hz**로 ESP32에 푸시(요청 없는 비동기 스트림).
- ESP32가 **4샘플씩 묶어(batch)** RAW 특성으로 notify → 실제 notify 빈도는 **50Hz**.
- 버퍼링으로 인한 추가 지연: **약 20ms**(배치 4 × 5ms).
- 오조작 판단은 **Android 로컬**에서 수행(펌웨어는 raw만 전달).

---

## 2. GATT 채널 (NUS)

- **Service UUID**: `6E400001-B5A3-F393-E0A9-E50E24DCCA9E`

| 이름 | UUID | 속성 | 방향 | 용도 |
|---|---|---|---|---|
| **TX** | `6E400003-B5A3-F393-E0A9-E50E24DCCA9E` | Notify | ESP32 → 폰 | 문자열 상태/경고 (`MODULE_ERR` 등) |
| **RX** | `6E400002-B5A3-F393-E0A9-E50E24DCCA9E` | Write(응답O) | 폰 → ESP32 | 명령(0xAA·0xAB) |
| **RAW** | `6E400004-B5A3-F393-E0A9-E50E24DCCA9E` ⚠️임시 | Notify | ESP32 → 폰 | 센서 raw 배치 스트림 |

> ⚠️ **RAW UUID(`...6E400004`)는 임시값.** 확정 시 펌웨어 `BLE.h`의 `RAW_DATA`와 앱 `companion object`를 함께 교체.
> TX/RAW에는 CCCD(0x2902)가 자동 포함됨. 앱이 둘 다 notify-enable 해야 데이터가 흐른다.

---

## 3. RAW 패킷 포맷 (★ Android 디코딩 대상)

한 notify = **배치 프레임 1개**. 전부 **little-endian**(ESP32-C3 RISC-V 네이티브).
센서값은 **Q31 정수(raw) 그대로** 전송한다 — MCU(Cortex-M0+)에 FPU가 없어 소프트플롯을 피하고, 환산은 FPU 있는 Android에서 수행.

```
프레임:
  ┌────────┬──────────────────────────────── ... ──────────┐
  │ count  │  RawSample[0]  RawSample[1]  ...  RawSample[N-1] │
  │ 1 byte │  10 B           10 B               10 B          │
  └────────┴────────────────────────────────────────────────┘
  N = count (보통 4, 스트림 끊김 시 1~3일 수 있음)
  프레임 길이 = 1 + N*10  (N=4 → 41 byte)
  ※ RawSample[0]이 가장 오래된 샘플, [N-1]이 최신 (cnt 오름차순)

RawSample (10 byte, packed):
  offset 0 : int32(Q31)  break_val   // 브레이크 페달 (정규화 힘 ×2^31)
  offset 4 : int32(Q31)  accel_val   // 엑셀(가속) 페달 (정규화 힘 ×2^31)
  offset 8 : uint16      cnt         // 샘플 시퀀스 카운터 (200Hz로 1씩 증가, wrap-around)
```

### 값 의미 / 환산 (★ 중요)
`break_val`/`accel_val`은 **FSR 페달 힘을 정규화(0.0~1.0)한 값의 Q31 표현**이다(전압 아님).

```
normalized(0~1) = raw / 2147483648.0          // 2^31. 0=안 밟음, 1.0=완전히 밟음
```
- 범위: `0 ~ 2^31-1` (LUT가 1.0에서 포화 → 상한 클램프). 음수/NaN 없음.
- 오조작 판단 모델의 입력 feature는 이 정규화 힘 시계열.
- 0~3.3 "전압형" 스케일이 굳이 필요하면 `normalized * 3.3` (펌웨어 보정계수 65535/65520은 미세보정용, 보통 생략 가능).

### 디코딩 의사코드 (Kotlin 참고)

```kotlin
fun decodeRaw(data: ByteArray) {
    val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
    val count = buf.get().toInt() and 0xFF
    require(data.size == 1 + count * 10)        // 프레임 길이 검증
    repeat(count) {
        val brkRaw = buf.int                    // Q31
        val accRaw = buf.int                    // Q31
        val cnt    = buf.short.toInt() and 0xFFFF
        val brk = brkRaw / 2147483648.0         // 0~1 정규화
        val acc = accRaw / 2147483648.0
        // → 시계열 버퍼에 push (cnt로 시간격자 복원/누락 검출)
    }
}
```

### `cnt` 사용법 (인터폴레이션 핵심)
- `cnt`는 **200Hz 등간격**으로 1씩 증가 → 샘플 i의 상대시각 = `cnt_i / 200.0` 초.
- 연속 샘플의 `cnt` 차가 1보다 크면 **그만큼 누락**된 것. 보간 시 그 구간을 메우거나 가중.
- `uint16`이라 65536(=약 327초)마다 wrap. diff 계산은 `(b - a) and 0xFFFF`로.
- ⚠️ `cnt`는 **STM32에서 도는 카운터**라 BLE 연결과 무관하게 연속 — **재연결해도 0으로 리셋되지 않는다.** "연결마다 0부터" 가정 금지.
- ⚠️ per-sample 타임스탬프는 없음. 절대시각이 필요하면 **폰 수신 시각** + `cnt` 격자를 폰 쪽에서 조합(펌웨어는 시각 개념이 없음 — SD 로깅 제거로 시간동기 경로 없음).

> 참고
> - per-sample의 `stx/len/crc/module_err`는 RAW에 **싣지 않는다**. BLE 자체 CRC/프레이밍과 중복이고, 모듈 에러는 TX 문자열로 별도 통지하기 때문.
> - **1 notify = 완결된 프레임 1개**. MTU만 충분하면 notify 간 재조립(fragment) 불필요.

---

## 4. TX 문자열 (ESP32 → 폰)

| 토큰 | 트리거 | 비고 |
|---|---|---|
| `MODULE_ERR` | 배치 내 한 샘플이라도 모듈 에러 | ASCII, 종결자 없음 |

- ⚠️ **엑셀/페달 오조작은 더 이상 문자열로 안 보냄** → RAW 스트림 받아 폰이 로컬 판단.

---

## 5. RX 명령 (폰 → ESP32) — 길이로 구분

| 길이 | 의미 | 포맷 |
|---|---|---|
| 1 byte = `0xAA` | 모듈에러 해제 확인 | 사용자가 경고 팝업 "확인" 시 |
| 1 byte = `0xAB` | 용량경고 해제 확인 | (현재 다운스트림 미연결, 수신만 처리) |

- ⚠️ 시간동기(과거 16B `int64 sec/usec`)는 **제거됨.** SD 로깅이 빠지면서 펌웨어가 절대시각을 쓸 데가 없어졌다. 절대시각은 폰 쪽에서 수신 시각 + `cnt`로 처리(§3 참고).
- RX는 **Write Request(응답 있는 쓰기)** 로 보낼 것. 앱이 `WRITE_TYPE_DEFAULT` 사용.

---

## 6. ★ Android가 반드시 추가해야 할 것 (MTU / 연결)

현재 펌웨어는 preferred MTU를 **247**로 설정해 둠. 하지만 **실제 MTU는 양측 협상값의 min**이라, 앱이 요청을 안 하면 기본 23으로 남는다.

1. **`requestMtu(≥ 64)` 필수**
   - 미요청 시 MTU=23 → notify payload 최대 20B → **41B 배치 프레임이 전송 실패**(펌웨어는 에러 리턴, 무전송).
   - 데이터가 아예 안 들어오면 **이게 1순위 원인**.
   - 호출 시점: 연결 후 services discovered 직후, notify 구독보다 먼저 권장.

2. **`setConnectionPriority(CONNECTION_PRIORITY_HIGH)`** 권장
   - 50Hz notify를 빠짐없이 받으려면 connection interval ≤ 20ms 필요(HIGH면 보통 11.25~15ms).
   - 안 잡으면 대역폭이 남아도 샘플이 큐에 밀려 실효 레이트가 떨어짐(끊김은 `cnt`로 감지됨, 안전엔 문제 없음).

---

## 7. 대역폭 참고

- payload: 200Hz × 10B ≈ **2 kB/s (16 kbps)** — LE 1M PHY의 약 2%, 실용 처리량의 한 자릿수 %.
- 배치로 오버헤드를 상각하므로 50Hz/샘플 직송과 거의 동일한 에어타임(~4%). 대역폭은 여유 충분, 관건은 §6의 MTU·연결주기.

---

## 8. 펌웨어가 확정/변경 시 함께 고쳐야 하는 칸

- [ ] **RAW UUID** 확정 → `BLE.h::RAW_DATA` + 앱 `companion object`.
- [ ] 배치 크기 `BATCH`(현재 4) 변경 시 → notify 빈도/지연/필요 MTU 재계산(`1 + BATCH*10` byte).
- [ ] `RawSample` 필드 추가 시 → §3 표 + 앱 디코더 동시 수정.
