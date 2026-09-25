package com.example.agproject

import android.content.SharedPreferences
import androidx.annotation.StringRes

/**
 * 개발자 학습 데이터 수집용 운전 페르소나 — 한 사람이 운전 스타일을 연기해 데이터를 모을 때 쓴다.
 * 사용자 기능이 아니다: 개발자 데이터 수집 화면(수집 버튼 길게 누르기)에서만 고르고, 사용자 화면엔 안 보인다.
 * 선택하면 캘리브레이션 대신 accelHigh 프리셋으로 판정하고, CSV persona 컬럼에 id가 찍힌다(선택 안 함 = "none").
 * 페르소나 추가·프리셋 조정은 여기 항목만 고치면 된다(id는 CSV·prefs 키에 남으므로 한 번 쓰면 바꾸지 말 것).
 *
 * accelHigh: 수집 중 실시간 경고/CSV pedal_err 기준으로만 쓰는 임시 고정값(2026-09-25 사용자 지정).
 * "p90 + 0.20" 캘리브레이션 공식은 살살 밟는 초보 연기에서 임계값이 지나치게 낮아져서 프리셋으로 대체했다.
 * 학습은 라벨과 원본 페달 값으로 하므로 이 값이 학습 데이터 자체를 바꾸진 않는다.
 */
enum class Persona(val id: String, @StringRes val labelRes: Int, val accelHigh: Double) {
  BEGINNER("beginner", R.string.persona_beginner, 0.60),
  NORMAL("normal", R.string.persona_normal, 0.70),
  AGGRESSIVE("aggressive", R.string.persona_aggressive, 0.80);

  companion object {
    /** CSV 에 페르소나 미선택(=실사용자 본인)으로 찍히는 값 */
    const val NONE_ID = "none"

    fun fromId(id: String?): Persona? = entries.find { it.id == id }
  }
}

/**
 * 선택된 개발자 페르소나와 사용자 캘리브레이션 값을 prefs("AgPrefs")에 읽고 쓰는 곳.
 * 페르소나를 골라 두면 판정은 그 프리셋으로, "없음"이면 사용자 본인 캘리브레이션으로 한다.
 * 키 이름을 여기 한곳에만 두어 화면·서비스가 각자 prefs 키를 다루다 어긋나지 않게 한다.
 */
object CalibrationPrefs {
  private const val KEY_PERSONA_ID = "DEV_PERSONA_ID"
  private const val KEY_USER_CALIBRATION = "CALIBRATED_THRESHOLDS_JSON"

  // 페르소나별 캘리브레이션을 저장하던 시절(2026-09-25, 0008b91)의 키 — 이제 안 쓰고 정리할 때만 지운다.
  private const val LEGACY_KEY_PERSONA_CALIBRATION_PREFIX = "CALIBRATED_THRESHOLDS_JSON_"

  fun currentPersona(prefs: SharedPreferences): Persona? =
    Persona.fromId(prefs.getString(KEY_PERSONA_ID, null))

  /** null 이면 페르소나 해제(사용자 본인으로 복귀) */
  fun setCurrentPersona(prefs: SharedPreferences, persona: Persona?) {
    prefs.edit().apply {
      if (persona == null) remove(KEY_PERSONA_ID) else putString(KEY_PERSONA_ID, persona.id)
    }.apply()
  }

  /** 사용자 본인 캘리브레이션 임계값 JSON. 아직 패턴 파악 전이면 null. */
  fun userCalibration(prefs: SharedPreferences): String? = prefs.getString(KEY_USER_CALIBRATION, null)

  fun saveUserCalibration(prefs: SharedPreferences, thresholdsJson: String) {
    prefs.edit().putString(KEY_USER_CALIBRATION, thresholdsJson).apply()
  }

  fun clearUserCalibration(prefs: SharedPreferences) {
    prefs.edit().remove(KEY_USER_CALIBRATION).apply()
  }

  /** 오조작 판정이 가능한 상태인가 — 페르소나 프리셋이 있거나 사용자 캘리브레이션을 마쳤으면 true */
  fun isDetectionReady(prefs: SharedPreferences): Boolean =
    currentPersona(prefs) != null || userCalibration(prefs) != null

  /** 센서 모듈이 바뀌면 캘리브레이션이 무효 — 지운다(옛 페르소나별 키도 함께 정리). */
  fun clearAllCalibrations(editor: SharedPreferences.Editor): SharedPreferences.Editor {
    editor.remove(KEY_USER_CALIBRATION)
    Persona.entries.forEach { editor.remove(LEGACY_KEY_PERSONA_CALIBRATION_PREFIX + it.id) }
    return editor
  }
}
