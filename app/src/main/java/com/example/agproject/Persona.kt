package com.example.agproject

import android.content.SharedPreferences
import androidx.annotation.StringRes

/**
 * 개발자 학습 데이터 수집용 운전 페르소나 — 한 사람이 운전 스타일을 연기해 데이터를 모을 때 쓴다.
 * 사용자 기능이 아니다: 개발자 데이터 수집 화면(수집 버튼 길게 누르기)에서만 고르고, 사용자 화면엔 안 보인다.
 * 페르소나마다 캘리브레이션을 따로 가지며, CSV persona 컬럼에 id가 찍힌다(선택 안 함 = "none").
 * 페르소나를 추가/변경하려면 여기 항목만 고치면 된다(id는 CSV·prefs 키에 남으므로 한 번 쓰면 바꾸지 말 것).
 */
enum class Persona(val id: String, @StringRes val labelRes: Int) {
  BEGINNER("beginner", R.string.persona_beginner),
  NORMAL("normal", R.string.persona_normal),
  AGGRESSIVE("aggressive", R.string.persona_aggressive);

  companion object {
    /** CSV 에 페르소나 미선택(=실사용자 본인)으로 찍히는 값 */
    const val NONE_ID = "none"

    fun fromId(id: String?): Persona? = entries.find { it.id == id }
  }
}

/**
 * 선택된 페르소나와 캘리브레이션 값을 prefs("AgPrefs")에 읽고 쓰는 곳.
 * 페르소나 미선택이면 사용자 본인 캘리브레이션(기존 키 그대로)을, 선택돼 있으면 그 페르소나 몫을 쓴다.
 * 키 이름을 여기 한곳에만 두어 화면·서비스가 각자 prefs 키를 다루다 어긋나지 않게 한다.
 */
object CalibrationPrefs {
  private const val KEY_PERSONA_ID = "DEV_PERSONA_ID"
  private const val KEY_USER_CALIBRATION = "CALIBRATED_THRESHOLDS_JSON"
  private const val KEY_PERSONA_CALIBRATION_PREFIX = "CALIBRATED_THRESHOLDS_JSON_"

  fun currentPersona(prefs: SharedPreferences): Persona? =
    Persona.fromId(prefs.getString(KEY_PERSONA_ID, null))

  /** null 이면 페르소나 해제(사용자 본인으로 복귀) */
  fun setCurrentPersona(prefs: SharedPreferences, persona: Persona?) {
    prefs.edit().apply {
      if (persona == null) remove(KEY_PERSONA_ID) else putString(KEY_PERSONA_ID, persona.id)
    }.apply()
  }

  /** 캘리브레이션 임계값 JSON. 아직 패턴 파악 전이면 null. persona 기본값은 현재 선택된 페르소나. */
  fun calibration(prefs: SharedPreferences, persona: Persona? = currentPersona(prefs)): String? =
    prefs.getString(key(persona), null)

  fun saveCalibration(prefs: SharedPreferences, persona: Persona?, thresholdsJson: String) {
    prefs.edit().putString(key(persona), thresholdsJson).apply()
  }

  fun clearCalibration(prefs: SharedPreferences, persona: Persona?) {
    prefs.edit().remove(key(persona)).apply()
  }

  /** 센서 모듈이 바뀌면 사용자·모든 페르소나의 캘리브레이션이 무효 — 전부 지운다. */
  fun clearAllCalibrations(editor: SharedPreferences.Editor): SharedPreferences.Editor {
    editor.remove(KEY_USER_CALIBRATION)
    Persona.entries.forEach { editor.remove(KEY_PERSONA_CALIBRATION_PREFIX + it.id) }
    return editor
  }

  private fun key(persona: Persona?): String =
    if (persona == null) KEY_USER_CALIBRATION else KEY_PERSONA_CALIBRATION_PREFIX + persona.id
}
