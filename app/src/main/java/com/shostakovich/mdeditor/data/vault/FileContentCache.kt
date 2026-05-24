package com.shostakovich.mdeditor.data.vault

import android.util.Log
import android.util.LruCache

/**
 * MD ファイル本文 (テキスト) のプロセスシングルトン LRU メモリキャッシュ。
 *
 * - 検索時に本文を Vault 全体に対して走査するが、毎回 Drive 取得は重い
 * - 一度取った本文はキャッシュして次回以降の検索を高速化
 *
 * 設計:
 *  - キー: Drive fileId (String)
 *  - 値: 本文 (String, UTF-8 想定)
 *  - サイズ計算: 文字数 (UTF-16 internal なので * 2 でバイト数概算)
 *  - 上限: 16 MB (BrainDump 想定の平均 MD 数 KB として 数千ファイル相当)
 */
object FileContentCache {
    private const val TAG = "FileContentCache"
    private const val MAX_BYTES = 16 * 1024 * 1024 // 16 MB

    private val cache = object : LruCache<String, String>(MAX_BYTES) {
        // String は UTF-16 で内部保持されるので 1 文字 ≈ 2 byte
        override fun sizeOf(key: String, value: String): Int = value.length * 2
    }

    fun get(fileId: String): String? = cache.get(fileId)

    fun put(fileId: String, content: String) {
        cache.put(fileId, content)
        Log.d(
            TAG,
            "PUT fileId=${fileId.take(10)}... ${content.length} chars " +
                "(now ${cache.size() / 1024} KB / ${MAX_BYTES / 1024 / 1024} MB)"
        )
    }

    /** 編集保存などで本文が変わった時に上書き or invalidate するため */
    fun invalidate(fileId: String) {
        cache.remove(fileId)
    }

    fun clear() {
        cache.evictAll()
    }
}
