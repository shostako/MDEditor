package com.shostakovich.mdeditor.data.search

import com.shostakovich.mdeditor.data.drive.DriveFile

/**
 * 検索結果1件。ファイル名一致と本文一致をどちらも表せる。
 */
data class SearchResult(
    val file: DriveFile,
    val folderPath: String,
    val matchType: MatchType,
    /** 本文一致時の周辺テキスト抜粋。ファイル名一致時は null */
    val snippet: String? = null,
) {
    enum class MatchType {
        /** ファイル名にクエリが含まれる */
        FILENAME,

        /** 本文にクエリが含まれる */
        CONTENT,
    }
}
