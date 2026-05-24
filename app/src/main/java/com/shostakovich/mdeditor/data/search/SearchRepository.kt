package com.shostakovich.mdeditor.data.search

import android.util.Log
import com.shostakovich.mdeditor.data.vault.FileContentCache
import com.shostakovich.mdeditor.data.vault.VaultIndex
import com.shostakovich.mdeditor.data.vault.VaultRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Vault に対する検索ロジック。
 *
 * 戦略:
 *  1. **ファイル名検索** を先に全件チェックして即座に Flow に流す (高速、メモリのみ)
 *  2. その後 **本文検索** を 1ファイルずつ実行 (Drive 取得 + キャッシュ済みは即時)
 *     - 取得した本文は FileContentCache に保存して次回以降高速化
 *
 * Flow で逐次 emit するので、UI 側は受け取り次第リストに追加表示できる。
 * 結果として「ファイル名一致が一瞬で出て、本文一致が徐々に追加される」UX。
 */
object SearchRepository {
    private const val TAG = "SearchRepository"

    /** 本文一致時の抜粋に取る前後の文字数 */
    private const val SNIPPET_RADIUS = 40

    /**
     * クエリを検索して結果を逐次 Flow に流す。
     *
     * @param query 検索文字列。空なら何も emit しない
     * @param caseSensitive 大文字小文字を区別するか (デフォルト false)
     */
    fun search(
        query: String,
        caseSensitive: Boolean = false,
    ): Flow<SearchResult> = flow {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@flow

        val all = VaultIndex.allMarkdownFiles
        if (all.isEmpty()) {
            Log.w(TAG, "VaultIndex is empty, did you call build() yet?")
            return@flow
        }

        // フェーズ 1: ファイル名検索 (即座)
        val filenameHitIds = mutableSetOf<String>()
        for (indexed in all) {
            if (indexed.file.name.contains(trimmed, ignoreCase = !caseSensitive)) {
                filenameHitIds += indexed.file.id
                emit(
                    SearchResult(
                        file = indexed.file,
                        folderPath = indexed.folderPath,
                        matchType = SearchResult.MatchType.FILENAME,
                    )
                )
            }
        }

        // フェーズ 2: 本文検索 (順次)
        // ファイル名一致したものは本文検索でも当たる可能性あるが、表示はファイル名一致を優先するため
        // 重複を避ける目的で skip する。本文一致のみ知りたいなら別途出す。
        for (indexed in all) {
            if (indexed.file.id in filenameHitIds) continue

            val content = FileContentCache.get(indexed.file.id) ?: run {
                try {
                    val fresh = VaultRepository.downloadTextFile(indexed.file.id)
                    FileContentCache.put(indexed.file.id, fresh)
                    fresh
                } catch (e: Throwable) {
                    Log.w(TAG, "Failed to download ${indexed.file.name}: ${e.message}")
                    null
                }
            } ?: continue

            val idx = content.indexOf(trimmed, ignoreCase = !caseSensitive)
            if (idx >= 0) {
                emit(
                    SearchResult(
                        file = indexed.file,
                        folderPath = indexed.folderPath,
                        matchType = SearchResult.MatchType.CONTENT,
                        snippet = extractSnippet(content, idx, trimmed.length),
                    )
                )
            }
        }
    }

    /**
     * マッチ箇所の前後 SNIPPET_RADIUS 文字を抜粋する。
     * 改行は空白に置換、先頭/末尾には省略記号を付ける。
     */
    private fun extractSnippet(content: String, matchIndex: Int, matchLength: Int): String {
        val start = (matchIndex - SNIPPET_RADIUS).coerceAtLeast(0)
        val end = (matchIndex + matchLength + SNIPPET_RADIUS).coerceAtMost(content.length)
        val slice = content.substring(start, end).replace(Regex("\\s+"), " ").trim()
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < content.length) "…" else ""
        return "$prefix$slice$suffix"
    }
}
