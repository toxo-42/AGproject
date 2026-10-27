package com.example.agproject

import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ── RAW 원시 샘플 CSV 로깅 (Phase A/E: 학습 데이터 + 증거자료) ──────────
// 첫 윈도우가 찰 때 lazy 로 세션 파일을 연다(연결 실패 시 빈 파일 안 남김).
// 저장 위치: filesDir/logs/pedal_yyyyMMdd_HHmmss.csv (권한 불필요, adb pull 로 회수)
// 컬럼: date,time,brake,accel,module_err,pedal_err,accel_high,accel_rate,accel_score,armed,
//        consecutive,label,persona,trial_id,trial_type,trial_phase,accel_median,speed_kmh,speed_acc_kmh,purpose
//   (2026-07-17 accel_exceed 제거 + label→accel_high 교체 / 2026-07-18 label 재도입,
//   accel_rate 추가, accel_score/armed/consecutive 추가(긴 홀드 오조작 미탐지 원인 진단용,
//   §진행상황_및_로드맵.md 참고) / 2026-09-25 style(강/보통/약) → persona(Persona.id, 미선택 "none") 교체
//   — 옛 CSV의 strong/normal/weak 는 각각 aggressive/normal/beginner 에 대응)
//   / 2026-09-25 trial_id,trial_type,trial_phase 추가(안내형 재현 실험 — Trial.kt, 실험 밖이면 "none")
//   / 2026-09-26 accel_median 추가(윈도우 엑셀 중앙값 — accel 은 마지막 샘플 하나라 판정의 "윈도우 50% 이상
//   초과"와 어긋난다. 중앙값 >= 임계값이면 정확히 그 조건과 같아서 임계값 학습에 쓴다, prototype/train_thresholds.py)
//   / 2026-10-25 speed_kmh,speed_acc_kmh 추가(GPS 차량 속도와 그 오차 — 운전 성향 진단 Phase F-2, SpeedTracker.kt.
//   GPS 는 1Hz 라 4Hz 행에선 같은 값이 반복된다. 권한 없음/fix 없음/3초 이상 끊김이면 "none". 좌표는 안 남긴다)
//   / 2026-10-28 purpose 추가("demo"/"train" — 개발자 설정 데모(시연) 모드가 켜진 동안의 행은 "demo".
//   PC 학습(prototype/src/dataset.py)이 demo 행을 빼서 시연 주행이 학습 데이터에 섞이지 않게 한다)
//   - 기록 주기는 200Hz 원시 샘플 전부가 아니라 **판정 윈도우 하나당 1행(4Hz)**이다.
//     pedal_err/accel_high 가 애초에 4Hz 단위라 200Hz로 찍어도 값이 반복될 뿐이고,
//     증거자료로서 사람이 열어볼 수 있는 크기가 더 중요하다고 판단(2026-07-13 사용자 결정,
//     처음엔 200Hz 전부 남겼다가 "초당 데이터가 너무 많다"는 피드백으로 축소).
//     brake/accel 은 그 윈도우의 마지막 샘플 값을 대표값으로 쓴다.
//   - date/time: 사람이 읽을 수 있는 형식(원시 epoch 대신).
//   - module_err: 그 순간 미해결 장치 오류가 있었는지("module_err"/"none",
//     isErrorDialogShowing 그대로). 0/1 대신 문자열로 남겨 바로 알아볼 수 있게 함(2026-07-13).
//   - pedal_err: onPedalMisoperation() 이 실제로 발동한 그 윈도우 한 행만 "pedal_err",
//     나머지는 "none"(2026-07-17 사용자 결정). 예전엔 misop 레벨 조건이 유지되는 매 윈도우마다
//     찍었는데, judge.py 에 변화율(미분) 트리거가 추가되면서 "완만하게 계속 깊게 밟는 상태"와
//     "실제로 경고가 뜬 사건"이 달라졌다 — 후자만 남겨야 증거자료로서 의미가 있다.
//     JudgeResult.fired 로 판정한다. ⚠️ 알고리즘이 자동으로 트리거한 것이라
//     패턴 파악 진행 중이면 judgeWindow 가 판정 자체를 건너뛰어 항상 "none"이다
//     — 아래 label(수동)과는 독립적인, 실시간 감지 결과일 뿐이라는 점에 주의.
//   - accel_high: 그 순간 적용 중인 임계값(캘리브레이션 전이면 기본값 calibration.DEFAULT_ACCEL_HIGH,
//     2026-10-01 전 파일은 "none"). 패턴 파악 진행 중이면 "none".
//   - accel_rate: judge_calibrated_json 이 계산한 그 윈도우의 "rate"(RATE_LAG_SAMPLES=50ms
//     떨어진 두 샘플 간 엑셀 델타의 최댓값) — accel_rate_high(급조작 트리거 임계값)와 직접
//     비교되는 값 그 자체. 판정 자체가 스킵된 윈도우(패턴 파악 중, 2026-10-01 전 파일은 캘리브레이션 전도)는 "none"
//     (2026-07-18 사용자 요청 — accel_rate_high 실측 튜닝에 쓸 실제 값 분포를 보기 위함).
//   - accel_score: 그 윈도우의 "score"(레벨 조건 hits 비율) — high_ratio와 직접 비교되는
//     값. 스킵된 윈도우는 "none".
//   - armed: judgeWindow() 처리 후 그 시점의 무장 상태(judge.MisopDetector). 스킵된 윈도우는
//     "none"(2026-09-24 상태기계 Python 이관 후 — 패턴 파악 중엔 detector 자체가 없다).
//   - consecutive: 그 시점의 연속 misop 윈도우 카운트. 스킵된 윈도우는 "none".
//     accel_score/armed/consecutive 셋 다 "긴 홀드형 오조작 재현이 왜 반복적으로 안 잡히는지"
//     진단용으로 추가(2026-07-18) — 로그캣 없이 CSV만으로 무장이 언제 풀렸는지 재구성 가능.
//   - label: 수동 오조작 라벨링(개발자용 지도학습 정답, Phase A, 0=정상/1=오조작 재현).
//     2026-07-17에 "실주행에서 항상 0으로만 찍힌다"는 이유로 accel_high로 교체하며 없앴는데,
//     의도적으로 오조작을 재현하며 라벨을 남기는 세션(바로 이 currentLabel 자체가 그 정답)에서는
//     그 판단이 틀렸다 — 라벨 누른 시점이 CSV 어디에도 안 남아서 지도학습 데이터가 통째로
//     유실됐다(2026-07-18 사용자 피드백). accel_high 는 그대로 두고 label 을 별도 컬럼으로 복원.
//   - style: 개발자 전용 강/보통/약 스타일 라벨(그대로 유지).
// 2026-10-28 BleService 에서 분리 — 서비스는 한 행의 값(PedalCsvRow)만 모아 넘기고, 파일·형식은 여기서만 다룬다.

/**
 * CSV 한 행(판정 윈도우 하나)의 값. null 인 판정 값은 "none" 으로 찍힌다 — 판정을 건너뛴 윈도우(영점 조절 중 등).
 * 컬럼 의미는 위 주석 참고. 컬럼 이름은 prototype/src/dataset.py 가 읽는 계약이다.
 */
data class PedalCsvRow(
  val recvMs: Long,
  val brake: Double,
  val accel: Double,
  val moduleErr: Boolean,
  val pedalErr: Boolean,
  val accelHigh: Double?,
  val accelRate: Double?,
  val accelScore: Double?,
  val armed: Boolean?,
  val consecutive: Int?,
  val label: Int,
  val persona: String,
  val trialId: String?,
  val trialType: String?,
  val trialPhase: String?,
  val accelMedian: Double,
  val speedKmh: Double?,
  val speedAccKmh: Double?,
  val demo: Boolean,
)

/**
 * 주행 세션 하나의 CSV 파일. 인스턴스 하나 = 세션 하나(close 후엔 다시 열지 않는다).
 * BLE notify 콜백은 여러 바인더 스레드에서 올라온다 → writer 접근은 전부 lock 아래에서.
 * closed 는 종료 후 뒤늦게 도착한 콜백이 새 세션 파일을 되살리는 것을 막는다.
 * 쓰기 실패는 로그만 남기고 삼킨다 — 로깅 때문에 판정 흐름이 멈추면 안 된다.
 */
class PedalCsvLogger(private val dir: File) {
  private val tag = "PedalCsvLogger"
  private val lock = Any()
  private var writer: BufferedWriter? = null
  private var closed = false
  private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
  private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

  fun write(row: PedalCsvRow) {
    synchronized(lock) {
      val w = ensureWriterLocked() ?: return
      try {
        w.write(formatLine(row, dateFormat, timeFormat))
      } catch (e: Exception) {
        Log.e(tag, "CSV 쓰기 실패: ${e.message}")
      }
    }
  }

  fun flush() {
    synchronized(lock) {
      try { writer?.flush() } catch (e: Exception) { Log.w(tag, "CSV flush 실패: ${e.message}") }
    }
  }

  fun close() {
    synchronized(lock) {
      try {
        writer?.flush()
        writer?.close()
      } catch (e: Exception) {
        Log.w(tag, "CSV 닫기 실패: ${e.message}")
      }
      writer = null
      closed = true
    }
  }

  // lock 아래에서만 호출. 최초 호출 시점에 세션 파일을 lazy 로 연다.
  private fun ensureWriterLocked(): BufferedWriter? {
    if (closed) return null   // 서비스 종료 후 도착한 콜백 → 새 세션 파일을 열지 않는다
    writer?.let { return it }
    return try {
      if (!dir.exists()) dir.mkdirs()
      val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
      val file = File(dir, "pedal_${ts}.csv")
      BufferedWriter(FileWriter(file, true)).also {
        it.write(HEADER)
        writer = it
        Log.i(tag, "RAW 로깅 시작: ${file.absolutePath}")
      }
    } catch (e: Exception) {
      Log.e(tag, "CSV 로깅 파일 생성 실패: ${e.message}", e)
      null
    }
  }

  companion object {
    const val HEADER =
      "date,time,brake,accel,module_err,pedal_err,accel_high,accel_rate,accel_score,armed,consecutive,label,persona,trial_id,trial_type,trial_phase,accel_median,speed_kmh,speed_acc_kmh,purpose\n"

    // 0/1 대신 문자열로 남겨서 CSV를 열어봤을 때 바로 무슨 뜻인지 알 수 있게 한다.
    // accel/brake 는 소수 4자리로 반올림(Q31 원본보다 촘촘할 필요 없음 → 줄 크기 안정).
    fun formatLine(row: PedalCsvRow, dateFormat: SimpleDateFormat, timeFormat: SimpleDateFormat): String {
      val d = Date(row.recvMs)
      fun f4(v: Double) = String.format(Locale.US, "%.4f", v)
      fun f1(v: Double) = String.format(Locale.US, "%.1f", v)
      return "${dateFormat.format(d)},${timeFormat.format(d)}," +
        "${f4(row.brake)},${f4(row.accel)}," +
        "${if (row.moduleErr) "module_err" else "none"},${if (row.pedalErr) "pedal_err" else "none"}," +
        "${row.accelHigh?.let(::f4) ?: "none"},${row.accelRate?.let(::f4) ?: "none"}," +
        "${row.accelScore?.let(::f4) ?: "none"},${row.armed?.toString() ?: "none"},${row.consecutive?.toString() ?: "none"}," +
        "${row.label},${row.persona}," +
        "${row.trialId ?: "none"},${row.trialType ?: "none"},${row.trialPhase ?: "none"}," +
        "${f4(row.accelMedian)},${row.speedKmh?.let(::f1) ?: "none"},${row.speedAccKmh?.let(::f1) ?: "none"}," +
        "${if (row.demo) "demo" else "train"}\n"
    }
  }
}
