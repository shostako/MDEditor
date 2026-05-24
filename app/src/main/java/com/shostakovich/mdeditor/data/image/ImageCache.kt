package com.shostakovich.mdeditor.data.image

import android.util.Log
import android.util.LruCache

/**
 * Drive から取得した画像バイト列を保持するプロセスシングルトンの LRU メモリキャッシュ。
 *
 * 設計方針:
 *  - キー: Drive fileId (String)
 *  - 値: 画像のオリジナルバイト列 (ByteArray)
 *  - サイズ計算: ByteArray の長さをそのままバイト数として LruCache に計上
 *  - 上限: 32 MB (BrainDump 想定の平均画像 100KB として 320 枚保持可能)
 *
 * 使い方:
 *  - DriveSchemeHandler の handle() で最初にキャッシュチェック
 *  - hit したら即返す。miss したら Drive から取得→ put()
 *
 * 制限:
 *  - メモリのみ。アプリ再起動で消える (M5-c でディスクキャッシュ追加予定)
 *  - 並列アクセス時の thread-safety: LruCache 自体が同期化されているので OK
 */
object ImageCache {
    private const val TAG = "ImageCache"
    private const val MAX_BYTES = 32 * 1024 * 1024 // 32 MB

    private val cache = object : LruCache<String, ByteArray>(MAX_BYTES) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }

    /** キャッシュにあれば返す。無ければ null */
    fun get(fileId: String): ByteArray? {
        val hit = cache.get(fileId)
        if (hit != null) {
            Log.d(TAG, "HIT  fileId=${fileId.take(10)}... ${hit.size} bytes")
        }
        return hit
    }

    /** 取得したバイト列をキャッシュに保存する */
    fun put(fileId: String, bytes: ByteArray) {
        cache.put(fileId, bytes)
        Log.d(
            TAG,
            "PUT  fileId=${fileId.take(10)}... ${bytes.size} bytes " +
                "(now ${cache.size() / 1024} KB / ${MAX_BYTES / 1024 / 1024} MB)"
        )
    }

    /** デバッグ・ログアウト時用。全消去 */
    fun clear() {
        cache.evictAll()
        Log.d(TAG, "Cache cleared")
    }
}
