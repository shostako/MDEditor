package com.shostakovich.mdeditor.data.drive

import kotlinx.serialization.Serializable

/**
 * Drive API v3 の files リソース。
 *
 * 参考: https://developers.google.com/drive/api/v3/reference/files
 *
 * フィールドは fields パラメタで明示的に指定したものだけ返ってくる仕様。
 * MDEditor で使うのは以下:
 *  - id: fileId (永続的なユニークキー)
 *  - name: ファイル名 / フォルダ名
 *  - mimeType: フォルダなら "application/vnd.google-apps.folder"、それ以外は実 MIME
 *  - parents: 親フォルダの fileId 配列 (通常 1要素、複数親もありうる)
 *  - modifiedTime: ISO8601、ファイル一覧でソート / 表示用
 *  - size: byte 数。Drive ドキュメント (Google Docs 等) は size を返さないので nullable
 *
 * 注: Drive API は size を string で返す (JS の数値精度問題対策)。
 * Long に decode するには @SerialName と String→Long 変換が必要だが、
 * MDEditor では表示用にしか使わないので String のままで保持する。
 */
@Serializable
data class DriveFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val parents: List<String> = emptyList(),
    val modifiedTime: String? = null,
    val size: String? = null,
    // changes API のレスポンスで参照する。listFiles では `trashed = false` 条件で
    // 弾いてるので普段は出てこないが、changes ではゴミ箱行きを検出するために必要。
    val trashed: Boolean? = null,
) {
    val isFolder: Boolean
        get() = mimeType == MIME_FOLDER

    val isMarkdown: Boolean
        get() = mimeType == "text/markdown" ||
            mimeType == "text/x-markdown" ||
            // Drive は .md を text/plain として保存することがある
            (mimeType == "text/plain" && name.endsWith(".md", ignoreCase = true))

    val isImage: Boolean
        get() = mimeType.startsWith("image/")

    companion object {
        const val MIME_FOLDER = "application/vnd.google-apps.folder"
    }
}

/**
 * files.list のレスポンス。
 * nextPageToken が null でなければ続きあり (ページング)。
 */
@Serializable
data class DriveFileListResponse(
    val files: List<DriveFile> = emptyList(),
    val nextPageToken: String? = null,
)

/**
 * changes.getStartPageToken のレスポンス。
 * 初回 build 完了時にこれを取得して保存しておくと、次回以降の sync で
 * 「この時点以降の変更」だけを差分取得できる。
 */
@Serializable
data class StartPageTokenResponse(
    val startPageToken: String,
)

/**
 * changes.list のレスポンス。
 *
 * - `nextPageToken` あり: まだ続きの changes がある (ページング中)
 * - `newStartPageToken` あり: 全 changes 取り終わった (次回の起点として保存する)
 * - 通常はどちらか一方だけが返る
 */
@Serializable
data class ChangeListResponse(
    val changes: List<DriveChange> = emptyList(),
    val nextPageToken: String? = null,
    val newStartPageToken: String? = null,
)

/**
 * Drive changes リソース 1件。
 *
 * - `removed = true` あるいは `file.trashed = true` でゴミ箱 / 完全削除を表す
 * - `file = null` (= removed=true) のときは fileId だけ取れる。元の情報は不明。
 *   → DB 側で fileId 一致するエントリを削除すれば足りる
 * - `changeType = "file"` 以外 (例えば "drive") は無視
 */
@Serializable
data class DriveChange(
    val fileId: String? = null,
    val removed: Boolean = false,
    val changeType: String? = null,
    val file: DriveFile? = null,
)
