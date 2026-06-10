package com.shostakovich.mdeditor.data.prefs

import android.content.Context
import android.content.SharedPreferences

/**
 * UI 上の細かな表示設定を SharedPreferences に永続化する。
 * VaultRootStorage と同じプロセスシングルトン方式で MainActivity から init する。
 *
 * 保存項目:
 *  - showFrontmatter: Preview でプロパティ (frontmatter) パネルを表示するか。
 *    EditorScreen のトグルで切替え、次回起動後も維持される。デフォルト false
 *    (従来挙動 = 本文のみ表示 と互換)。
 *  - ttsSpeed: 読み上げ速度 (TextToSpeech.setSpeechRate に渡す倍率)。デフォルト 1.0
 */
object UiPrefsStorage {
    private const val PREFS_NAME = "mdeditor_ui_prefs"
    private const val KEY_SHOW_FRONTMATTER = "show_frontmatter"
    private const val KEY_TTS_SPEED = "tts_speed"

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs == null) {
                prefs = context.applicationContext
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            }
        }
    }

    fun loadShowFrontmatter(): Boolean =
        prefs?.getBoolean(KEY_SHOW_FRONTMATTER, false) ?: false

    fun saveShowFrontmatter(value: Boolean) {
        prefs?.edit()?.putBoolean(KEY_SHOW_FRONTMATTER, value)?.apply()
    }

    fun loadTtsSpeed(): Float =
        prefs?.getFloat(KEY_TTS_SPEED, 1.0f) ?: 1.0f

    fun saveTtsSpeed(value: Float) {
        prefs?.edit()?.putFloat(KEY_TTS_SPEED, value)?.apply()
    }
}
