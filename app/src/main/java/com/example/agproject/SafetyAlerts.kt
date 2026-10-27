package com.example.agproject

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * 사용자에게 내보내는 경고 출력(알림 채널·상단 고정 알림·헤드업·전체화면 경고·음성)을 한곳에 모은 것.
 * "언제 경고할지"는 BleService(판정)가 정하고, 여기는 "어떻게 보여 줄지"만 한다 — 알림 문구·모양을 바꿔도 판정 쪽은 그대로다.
 * 2026-10-28 BleService 에서 분리(코드는 그대로 옮김). startForeground 자체는 Service 기능이라 서비스에 남아 있다.
 */
class SafetyAlerts(private val context: Context) {
  private val tag = "SafetyAlerts"
  private val manager: NotificationManager
    get() = context.getSystemService(NotificationManager::class.java)

  fun createChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      // IMPORTANCE_LOW -> IMPORTANCE_HIGH 헤드업 알림 필수 조건!
      val serviceChannel = NotificationChannel(
        CHANNEL_ID,
        "PeOb Safety Channel",
        NotificationManager.IMPORTANCE_HIGH
      )

      // 알림 올 때 진동 Feedback
      serviceChannel.enableVibration(true)
      serviceChannel.description = "안전 장비의 긴급 경고를 알립니다."

      manager.createNotificationChannel(serviceChannel)
    }
  }

  /** 상단 고정 알림(포그라운드 서비스 알림, ONGOING_ID). startForeground 에도 이걸 넘긴다. */
  fun ongoingNotification(content: String): Notification {
    return NotificationCompat.Builder(context, CHANNEL_ID)
      .setContentTitle("PeOb")
      .setContentText(content)
      .setSmallIcon(android.R.drawable.ic_dialog_info)
      .setContentIntent(mainPendingIntent())
      .setOngoing(true)
      .build()
  }

  fun updateOngoing(content: String) {
    manager.notify(ONGOING_ID, ongoingNotification(content))
  }

  fun showHeadsUp(title: String, content: String) {
    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
      .setContentTitle(title)
      .setContentText(content)
      .setSmallIcon(android.R.drawable.stat_sys_warning) // 경고 아이콘
      .setPriority(NotificationCompat.PRIORITY_HIGH) // 중요도 최상
      .setDefaults(Notification.DEFAULT_ALL) // 소리/진동 기본값 사용
      .setAutoCancel(true) // 터치하면 사라짐
      .setContentIntent(mainPendingIntent())
      .build()

    // 중요: ID를 1번(서비스용)과 다르게 999번(경고용)으로 줍니다.
    manager.notify(HEADS_UP_ID, notification)
  }

  /** 페달 오조작 전체화면 경고 — CriticalActivity 를 비상 알림(fullScreenIntent)과 직접 실행 양쪽으로 띄운다. */
  fun showCritical(title: String, content: String) {
    val fullScreenIntent = Intent(context, CriticalActivity::class.java)
    fullScreenIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    val fullScreenPendingIntent = PendingIntent.getActivity(
      context,
      999,
      fullScreenIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val builder = NotificationCompat.Builder(context, CHANNEL_ID)
      .setSmallIcon(android.R.drawable.stat_sys_warning)
      .setContentTitle(title)
      .setContentText(content)
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setCategory(NotificationCompat.CATEGORY_ALARM)
      .setFullScreenIntent(fullScreenPendingIntent, true)
      .setAutoCancel(true)
      .build()

    manager.notify(CRITICAL_ID, builder)

    // fullScreenIntent는 화면이 꺼져있거나 잠겨있을 때만 자동 실행되고,
    // 화면을 보고 있는 상태(포그라운드)에서는 배너 알림으로만 뜬다.
    // 오조작은 화면을 보고 있는 상태에서도 무조건 전환돼야 하므로 직접 실행도 병행한다.
    val forceIntent = Intent(context, CriticalActivity::class.java)
    forceIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(forceIntent)
  }

  /** type: "PEDAL"(페달 오조작) / "SD"(용량 부족) / "ERROR"(장치 오류). 성별은 설정(TTS_GENDER)을 따른다. */
  fun playVoice(type: String) {
    // 저장된 성별 확인
    val prefs = context.getSharedPreferences("AgPrefs", Context.MODE_PRIVATE)
    val gender = prefs.getString("TTS_GENDER", "female") ?: "female"

    // 상황과 성별에 맞는 MP3 파일 ID 찾기
    var soundResId = 0

    if (gender == "male") {
      // 남성일 때 파일 매칭
      soundResId = when(type) {
        "PEDAL" -> R.raw.voice_pedal_male
        "SD" -> R.raw.voice_sd_male
        "ERROR" -> R.raw.voice_error_male
        else -> 0
      }
    } else {
      // 여성일 때 파일 매칭
      soundResId = when(type) {
        "PEDAL" -> R.raw.voice_pedal_female
        "SD" -> R.raw.voice_sd_female
        "ERROR" -> R.raw.voice_error_female
        else -> 0
      }
    }

    // 재생 (파일이 존재할 경우만)
    if (soundResId != 0) {
      try {
        // MediaPlayer 생성 및 재생
        val mediaPlayer = android.media.MediaPlayer.create(context, soundResId)
        mediaPlayer.setOnCompletionListener {
          it.release() // 재생 끝나면 메모리 청소
        }
        mediaPlayer.start()
      } catch (e: Exception) {
        Log.e(tag, "MP3 재생 실패: ${e.message}")
      }
    }
  }

  private fun mainPendingIntent(): PendingIntent? {
    val intent = Intent(context, MainActivity::class.java)
    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP

    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      PendingIntent.FLAG_IMMUTABLE
    } else {
      PendingIntent.FLAG_UPDATE_CURRENT
    }
    return PendingIntent.getActivity(context, 0, intent, flags)
  }

  companion object {
    private const val CHANNEL_ID = "BleCriticalChannel_v2"
    const val ONGOING_ID = 1      // 포그라운드 서비스 알림
    private const val HEADS_UP_ID = 999
    private const val CRITICAL_ID = 888
  }
}
