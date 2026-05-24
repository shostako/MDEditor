package com.shostakovich.mdeditor.data.index

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Drive `changes` API の pageToken を永続化する素 SharedPreferences ラッパー。
 *
 * pageToken は機密情報ではない (アクセストークンとは別物、Vault root のヒントは含むが
 * Drive 内部のページネーション用カーソルに過ぎない) ので、EncryptedSharedPreferences は
 * 不要。素の SharedPreferences で OK。
 *
 * Vault root が変わった場合、Vault root 配下しか index に入れていない設計上、
 * 古い pageToken をそのまま流用しても致命的ではない (差分判定で新 root 配下の
 * ファイルだけ拾える) が、誤検出を避けるため `clear()` で消すフローも用意しておく。
 */
object PageTokenStorage {
    private const val PREFS_NAME = "drive_changes_token"
    private const val KEY_PAGE_TOKEN = "page_token"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (this::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE,
        )
    }

    fun loadPageToken(): String? = prefs.getString(KEY_PAGE_TOKEN, null)

    fun savePageToken(token: String) {
        prefs.edit { putString(KEY_PAGE_TOKEN, token) }
    }

    fun clear() {
        prefs.edit { remove(KEY_PAGE_TOKEN) }
    }
}
