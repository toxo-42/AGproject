package com.example.agproject

import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * CSV 형식은 증거자료이자 PC 학습(prototype/src/dataset.py)의 입력 계약이다 — 글자 단위로 고정한다.
 * 기대값은 BleService 에서 분리하기 전(2026-10-28, 3972322)의 writeRawCsv 출력 규칙 그대로다.
 */
class PedalCsvFormatTest {

  private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
  private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
  // 기기 시간대 그대로 찍히므로 같은 시간대로 시각을 만든다
  private val recvMs = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).parse("2026-10-28 00:45:49.123")!!.time

  private val judgedRow = PedalCsvRow(
    recvMs = recvMs, brake = 0.28374, accel = 0.91, moduleErr = false, pedalErr = true,
    accelHigh = 0.75, accelRate = 0.54321, accelScore = 0.6, armed = true, consecutive = 1,
    label = 1, persona = "none", trialId = "3", trialType = "slam", trialPhase = "act",
    accelMedian = 0.88, speedKmh = 42.26, speedAccKmh = 1.04, demo = false,
  )

  @Test
  fun 헤더() {
    assertEquals(
      "date,time,brake,accel,module_err,pedal_err,accel_high,accel_rate,accel_score,armed,consecutive,label,persona," +
        "trial_id,trial_type,trial_phase,accel_median,speed_kmh,speed_acc_kmh,purpose\n",
      PedalCsvLogger.HEADER,
    )
  }

  @Test
  fun 판정된_윈도우_행() {
    assertEquals(
      "2026-10-28,00:45:49.123,0.2837,0.9100,none,pedal_err,0.7500,0.5432,0.6000,true,1,1,none,3,slam,act,0.8800,42.3,1.0,train\n",
      PedalCsvLogger.formatLine(judgedRow, dateFormat, timeFormat),
    )
  }

  @Test
  fun 판정을_건너뛴_윈도우는_none() {
    val skipped = judgedRow.copy(
      moduleErr = true, pedalErr = false, accelHigh = null, accelRate = null, accelScore = null, armed = null,
      consecutive = null, label = 0, persona = "beginner", trialId = null, trialType = null, trialPhase = null,
      speedKmh = null, speedAccKmh = null, demo = true,
    )
    assertEquals(
      "2026-10-28,00:45:49.123,0.2837,0.9100,module_err,none,none,none,none,none,none,0,beginner,none,none,none,0.8800,none,none,demo\n",
      PedalCsvLogger.formatLine(skipped, dateFormat, timeFormat),
    )
  }
}
