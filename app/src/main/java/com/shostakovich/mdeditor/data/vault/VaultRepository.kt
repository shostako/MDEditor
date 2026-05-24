package com.shostakovich.mdeditor.data.vault

import android.util.Log
import com.shostakovich.mdeditor.auth.AuthManager
import com.shostakovich.mdeditor.data.drive.ChangeListResponse
import com.shostakovich.mdeditor.data.drive.DriveClient
import com.shostakovich.mdeditor.data.drive.DriveFile
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Vault に対する高レベル操作をまとめる Repository。
 *
 * 責務:
 *  - 名前からフォルダ候補を検索 (Vault root の初期選択用)
 *  - 指定フォルダの子要素一覧を取得 (FileTreeScreen 用)
 *  - 単一ファイルのテキスト取得 (M4 で EditorScreen から呼ぶ予定)
 *
 * 設計方針:
 *  - object シングルトン (依存注入は当面 Hilt 入れずに直接参照で OK)
 *  - メソッドは全て suspend。UI 層は ViewModel / rememberCoroutineScope から呼ぶ
 *  - エラーは throw、呼び出し側で try-catch
 *  - Drive クエリ言語はここに閉じ込めて、UI 層には漏らさない
 */
object VaultRepository {
    private const val TAG = "VaultRepository"

    /**
     * 名前で **フォルダ** を検索する。Vault root の初期選択時に使う。
     * 大文字小文字の扱いは Drive API 仕様 (基本 case-sensitive、name は大小区別)。
     */
    suspend fun searchFoldersByName(name: String): List<DriveFile> {
        val token = AuthManager.freshAccessToken()
        // クエリ内のシングルクォートをエスケープ (Drive クエリ言語: \' で表現)
        val escaped = name.replace("'", "\\'")
        val query = "name = '$escaped' and mimeType = '${DriveFile.MIME_FOLDER}' and trashed = false"
        val response = DriveClient.api.listFiles(
            authorization = DriveClient.bearer(token),
            query = query,
        )
        Log.d(TAG, "searchFoldersByName(name='$name') hit ${response.files.size} folder(s)")
        return response.files
    }

    /**
     * 指定フォルダの直下にある子ファイル / フォルダを全て取得する。
     * Drive API は 1ページ最大1000件なので nextPageToken を辿って結合する。
     */
    suspend fun listChildren(parentFileId: String): List<DriveFile> {
        val token = AuthManager.freshAccessToken()
        val bearer = DriveClient.bearer(token)
        val query = "'$parentFileId' in parents and trashed = false"

        val all = mutableListOf<DriveFile>()
        var pageToken: String? = null
        var pageIndex = 0
        do {
            val response = DriveClient.api.listFiles(
                authorization = bearer,
                query = query,
                pageToken = pageToken,
            )
            all += response.files
            pageToken = response.nextPageToken
            pageIndex++
        } while (pageToken != null && pageIndex < 50) // 安全弁: 5万件で打ち切り
        Log.d(TAG, "listChildren(parent=${parentFileId.take(10)}...) → ${all.size} items in $pageIndex page(s)")
        return all
    }

    /**
     * 単一ファイルのメタデータを取得する (id, name, mimeType, parents, modifiedTime, size)。
     * EditorScreen でファイル名表示するためのリクエスト等で使う。
     */
    suspend fun getFileMetadata(fileId: String): DriveFile {
        val token = AuthManager.freshAccessToken()
        return DriveClient.api.getFileMetadata(
            authorization = DriveClient.bearer(token),
            fileId = fileId,
        )
    }

    /**
     * ファイル本体をテキストとして取得する。MD ファイル想定 (UTF-8)。
     * 画像など binary に使うと壊れるので、呼び出し側で分けること。
     */
    suspend fun downloadTextFile(fileId: String): String {
        val token = AuthManager.freshAccessToken()
        val body = DriveClient.api.downloadFile(
            authorization = DriveClient.bearer(token),
            fileId = fileId,
        )
        // ResponseBody は使い終わったら close 必要。string() が一括読み + close をやってくれる。
        return body.string()
    }

    /**
     * ファイル本体をバイト列で取得する。画像取得用 (M5 で使う予定)。
     */
    suspend fun downloadBinaryFile(fileId: String): ByteArray {
        val token = AuthManager.freshAccessToken()
        val body = DriveClient.api.downloadFile(
            authorization = DriveClient.bearer(token),
            fileId = fileId,
        )
        return body.bytes()
    }

    /**
     * テキストファイルの本文を更新する。Drive Files.update (uploadType=media)。
     *
     * - 本文だけ差し替え、メタデータは触らない
     * - Content-Type は text/markdown を明示 (Drive は内部的に拡張子から推測もするが念のため)
     * - 更新後の Drive メタデータ (modifiedTime 等) を返す
     *
     * 注意: 同時編集の競合検知は最小限。本格運用なら ETag や lastModifiedTime 比較を入れるべき。
     */
    suspend fun updateTextFile(fileId: String, content: String): DriveFile {
        val token = AuthManager.freshAccessToken()
        val requestBody = content.toRequestBody("text/markdown; charset=utf-8".toMediaType())
        return DriveClient.api.updateFile(
            authorization = DriveClient.bearer(token),
            fileId = fileId,
            body = requestBody,
        )
    }

    /**
     * Drive 全体の「現在時刻」を指す changes pageToken を取得する。
     * 初回 build 完了直後に呼んで PageTokenStorage に保存する。
     */
    suspend fun getStartPageToken(): String {
        val token = AuthManager.freshAccessToken()
        return DriveClient.api.getStartPageToken(
            authorization = DriveClient.bearer(token),
        ).startPageToken
    }

    /**
     * pageToken 以降の changes を1ページ分取得する。ページングは呼び出し側 (VaultIndex) で。
     */
    suspend fun listChanges(pageToken: String): ChangeListResponse {
        val token = AuthManager.freshAccessToken()
        return DriveClient.api.listChanges(
            authorization = DriveClient.bearer(token),
            pageToken = pageToken,
        )
    }
}
