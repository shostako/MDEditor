package com.shostakovich.mdeditor.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.shostakovich.mdeditor.MainActivity
import com.shostakovich.mdeditor.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * TTS 読み上げ中のバックグラウンド維持を担う Foreground Service。
 *
 * 役割は「表示と入力ルーティング」のみ:
 *  - MediaStyle 通知（⏸/▶・⏹ アクション、ロック画面・QS メディアコントロール対応）
 *  - MediaSessionCompat（ヘッドセットボタンの受け口）
 *  - [TtsManager.state] を collect し、Idle/Error に遷移したら自分で止まる
 *
 * 再生ロジックは一切持たない。読み上げテキストも受け取らない
 * （EditorScreen が先に [TtsManager.play] を呼んでから [start] する。
 *   Intent extra にテキストを載せると binder のサイズ上限を踏むため）。
 *
 * 注意: onStartCommand 冒頭で TTS init を待たず即 startForeground する
 * （startForegroundService 後 約5秒以内に startForeground しないと ANR）。
 */
class TtsService : Service() {

    companion object {
        private const val ACTION_START = "com.shostakovich.mdeditor.tts.START"
        private const val ACTION_PLAY_PAUSE = "com.shostakovich.mdeditor.tts.PLAY_PAUSE"
        private const val ACTION_STOP = "com.shostakovich.mdeditor.tts.STOP"
        private const val CHANNEL_ID = "tts_playback"
        private const val NOTIFICATION_ID = 1001

        /** EditorScreen から呼ぶ。必ず TtsManager.play() の後に呼ぶこと */
        fun start(context: Context) {
            val intent = Intent(context, TtsService::class.java).setAction(ACTION_START)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                // Android 12+ の ForegroundServiceStartNotAllowedException 等。
                // FGS なしでも再生自体 (TtsManager) は動くので、ログに残して続行する
                android.util.Log.e("TtsService", "startForegroundService failed", e)
            }
        }
    }

    private lateinit var mediaSession: MediaSessionCompat
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /**
     * Idle/Error 観測による「意図した停止」か。onDestroy で再生を止めるかの判定に使う。
     * これが無いと「停止 → 即再開」の競合で、stopSelf 処理中の旧 Service インスタンスの
     * onDestroy が新しい再生 (Preparing/Playing) を殺してしまう。
     */
    private var stoppingIntentionally = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        mediaSession = MediaSessionCompat(this, "MDEditorTts").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = TtsManager.resume()
                override fun onPause() = TtsManager.pause()
                override fun onStop() = TtsManager.stop()
            })
            isActive = true
        }

        // 状態を一方向に観測して 通知/セッション を追従させる。Idle/Error で自殺
        scope.launch {
            TtsManager.state.collect { st ->
                when (st) {
                    is TtsManager.TtsState.Playing -> {
                        updateSession(st.fileName, playing = true)
                        notifyUpdate(buildNotification(st.fileName, "チャンク ${st.chunk} / ${st.total}", playing = true))
                    }
                    is TtsManager.TtsState.Paused -> {
                        updateSession(st.fileName, playing = false)
                        notifyUpdate(buildNotification(st.fileName, "一時停止中 (${st.chunk} / ${st.total})", playing = false))
                    }
                    is TtsManager.TtsState.Preparing -> Unit  // 起動時の暫定通知のまま待つ
                    is TtsManager.TtsState.Idle,
                    is TtsManager.TtsState.Error -> {
                        // 読了・停止・エラー。エラー詳細は EditorScreen 側で表示する
                        stoppingIntentionally = true
                        ServiceCompat.stopForeground(this@TtsService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 5秒ルール対策: 何より先に foreground 化（通知内容は state collect が直後に追従更新する）
        val st = TtsManager.state.value
        val initial = when (st) {
            is TtsManager.TtsState.Playing ->
                buildNotification(st.fileName, "チャンク ${st.chunk} / ${st.total}", playing = true)
            is TtsManager.TtsState.Paused ->
                buildNotification(st.fileName, "一時停止中 (${st.chunk} / ${st.total})", playing = false)
            else -> buildNotification(null, "読み上げ準備中...", playing = true)
        }
        // ServiceCompat.startForeground は androidx.core 1.12+ なので使わない (本プロジェクトは 1.10)。
        // type 指定付きオーバーロードは API 29+、それ未満は type なし版。
        // Android 14+ は権限・type 不整合で SecurityException を投げうるので捕捉する
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, initial)
            }
        } catch (e: Exception) {
            android.util.Log.e("TtsService", "startForeground failed", e)
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_START -> Unit  // 再生は EditorScreen が TtsManager.play() で開始済み
            ACTION_PLAY_PAUSE -> when (TtsManager.state.value) {
                is TtsManager.TtsState.Playing -> TtsManager.pause()
                is TtsManager.TtsState.Paused -> TtsManager.resume()
                else -> Unit
            }
            ACTION_STOP -> TtsManager.stop()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        // システムに殺された場合 (意図しない destroy) は再生を孤児化させない
        // (FGS が消えたのにバックグラウンドで音声だけ続くのを防ぐ)。
        // 意図した停止経路では止めない — 「停止 → 即再開」競合で新しい再生を殺さないため
        if (!stoppingIntentionally) {
            TtsManager.stop()
        }
        mediaSession.release()
        super.onDestroy()
    }

    // ---- 通知 ----

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "読み上げ",
            NotificationManager.IMPORTANCE_LOW,  // 音・バイブなし (読み上げ自体が音)
        ).apply {
            description = "ノート読み上げの再生コントロール"
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun notifyUpdate(notification: Notification) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(title: String?, text: String, playing: Boolean): Notification {
        val playPauseAction = if (playing) {
            NotificationCompat.Action(
                android.R.drawable.ic_media_pause, "一時停止", servicePendingIntent(ACTION_PLAY_PAUSE, 1),
            )
        } else {
            NotificationCompat.Action(
                android.R.drawable.ic_media_play, "再開", servicePendingIntent(ACTION_PLAY_PAUSE, 1),
            )
        }
        val stopAction = NotificationCompat.Action(
            android.R.drawable.ic_menu_close_clear_cancel, "停止", servicePendingIntent(ACTION_STOP, 2),
        )
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title ?: "読み上げ")
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(playPauseAction)
            .addAction(stopAction)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1),
            )
            .build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, TtsService::class.java).setAction(action)
        return PendingIntent.getService(this, requestCode, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    // ---- MediaSession ----

    /**
     * PlaybackState と Metadata を更新する。
     * Android 13+ ではシステムのメディアコントロールのボタンが PlaybackStateCompat の
     * actions から生成されるため、ここを正しく更新しないと QS にボタンが出ない。
     */
    private fun updateSession(title: String?, playing: Boolean) {
        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title ?: "MDEditor 読み上げ")
                .build(),
        )
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_STOP,
                )
                .setState(
                    if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                    if (playing) 1f else 0f,
                )
                .build(),
        )
    }
}
