# 🚗 PeOb (Pedal Observer) - 운전자 페달 오조작 방지 및 긴급 경고 시스템

![Android](https://img.shields.io/badge/Android-3DDC84?style=for-the-badge&logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)
![Bluetooth](https://img.shields.io/badge/BLE_GATT-0082FC?style=for-the-badge&logo=bluetooth&logoColor=white)

##  프로젝트 소개
**PeOb (Pedal Observer)**는 운전자의 페달 오조작(가속 페달과 브레이크 혼동)을 실시간으로 감지하고 즉각적인 시각/청각적 경고를 발생시켜 대형 사고를 예방하는 안드로이드 기반 스마트 안전 시스템입니다. 
차량 내부의 하드웨어 모듈(ESP-32)과 초저지연 BLE(Bluetooth Low Energy) 통신을 유지하며, 스마트폰의 상태(화면 꺼짐, 다른 앱 사용 중)와 무관하게 **100% 확률로 즉각적인 화면 강제 전환**을 보장하는 것이 특징입니다.

##  기술 스택 및 아키텍처
* **Language:** Kotlin
* **Architecture:** Event-Driven Architecture (EDA) via `BroadcastReceiver`
* **Network / Hardware:** BLE GATT (Generic Attribute Profile), ESP-32
* **Asynchronous Processing:** Android `Foreground Service`, `Handler`
* **UI / UX:** Material Design Components, Glide, State-driven Dynamic Rendering

##  핵심 기술 및 트러블슈팅 (Key Features & Deep Dive)

### 1. 투트랙(Two-track) 강제 화면 전환 로직 (안드로이드 백그라운드 제약 돌파)
안드로이드 10 이상의 백그라운드 액티비티 실행 제한 정책을 우회하고, 긴급 상황 시 운전자의 시야를 즉각 확보하기 위해 두 가지 방어선을 구축했습니다.
* **절전/잠금 화면 상태:** `Full Screen Intent`와 Window 플래그(`FLAG_SHOW_WHEN_LOCKED`, `FLAG_TURN_SCREEN_ON`)를 조합하여 기기 잠금을 해제하지 않고도 즉시 경고 화면을 호출합니다.
* **다른 앱(내비게이션 등) 사용 상태:** `SYSTEM_ALERT_WINDOW` (다른 앱 위에 그리기) 권한을 선제적으로 획득하고, 에러 감지 시 `FLAG_ACTIVITY_NEW_TASK`를 통해 기존 뷰를 찢고 최상단에 붉은 경고창(`CriticalActivity`)을 강제 오버레이합니다.

### 2. Zero-Latency BLE 통신 (Notify 방식 적용)
기존의 Polling 방식이 가진 배터리 소모와 통신 지연 문제를 해결하기 위해, GATT `Notify` 방식을 적용했습니다.
* 서비스(7301)와 특성(수신:735e, 송신:735f) UUID를 명확히 분리하여 데이터 채널의 간섭을 없앴습니다.
* 하드웨어 모듈의 CCCD(Client Characteristic Configuration Descriptor)를 활성화하여, 에러 신호(`PEDAL_ERR`) 발생 즉시 스마트폰으로 Push 되도록 설계하여 **지연 시간(Delay)을 0에 가깝게 최적화**했습니다.

### 3. 이벤트 기반 아키텍처(EDA)를 통한 의존성 분리
* 백그라운드 생명주기를 담당하는 `BleService`와 UI를 담당하는 `MainActivity`를 철저히 분리했습니다.
* 서비스에서 통신 상태 변화나 에러가 감지될 때만 `Broadcast`를 송출하고, 메인 화면은 이를 수신(`IntentFilter`)하여 UI를 동적으로 렌더링합니다. 이를 통해 불필요한 리소스 낭비를 막고 시스템의 결합도를 낮췄습니다.

### 4. 시인성 극대화 및 안전장치 (Safety-First UX)
* **동적 UI 렌더링:** 연결 대기, 정상 감시, 연결 오류 등 시스템 상태에 따라 메인 대시보드의 `StrokeColor`와 `StrokeWidth`가 실시간으로 변하여 0.1초 만에 상태를 직관적으로 인지할 수 있습니다.
* **휴먼 에러 방지:** 긴급 경고 화면(`CriticalActivity`)에서 운전자가 당황하여 뒤로 가기 버튼을 누르는 것을 방지하기 위해 `onBackPressed`를 무효화했습니다. 반드시 화면 중앙의 '상황 확인' 버튼을 터치해야만 모듈로 해제 신호(`0xAA`)가 전송되고 시스템이 복구되도록 설계했습니다.

##  프로젝트 구조 (Directory Structure)
```text
com.example.agproject
├── system/
│   ├── BleService.kt (백그라운드 통신 두뇌 및 알림 권한 제어)
│   └── AndroidManifest.xml (시스템 코어 및 권한 명세)
├── ui/
│   ├── MainActivity.kt (진입점 및 상태 관제탑)
│   ├── ScanActivity.kt (BLE 기기 탐색 및 필터링)
│   ├── CriticalActivity.kt (최상위 긴급 경고 화면)
│   └── DeviceManagerActivity.kt (사용자 맞춤형 TTS/환경 설정)
└── adapter/
    └── DeviceAdapter.kt (RecyclerView 데이터 바인딩)
