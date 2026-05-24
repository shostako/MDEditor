package com.shostakovich.mdeditor.data.drive

import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

/**
 * Drive API v3 の REST エンドポイントを Retrofit interface として定義する。
 *
 * 設計方針:
 * - Authorization ヘッダーは呼び出し側で明示的に組み立てて渡す (@Header)。
 *   Interceptor で自動付与する方式もあるが、OAuth トークンの取得が suspend で、
 *   Interceptor は同期 (OkHttp Interceptor の世界は coroutine 非対応)。
 *   毎リクエスト直前に Repository 層で suspend で token 取って渡す方が明示的で
 *   かつ Refresh の挙動も追いやすい。
 * - エラーは Retrofit が HttpException を投げる (4xx/5xx)。Repository 側で catch する。
 *
 * 参考: https://developers.google.com/drive/api/v3/reference/
 */
interface DriveApi {

    /**
     * ファイル / フォルダの検索・一覧取得。
     *
     * よく使うクエリ例:
     *  - `name = 'BrainDump' and mimeType = 'application/vnd.google-apps.folder' and trashed = false`
     *  - `'<parentFileId>' in parents and trashed = false`
     *
     * fields パラメタはレスポンスの取得項目を絞る。指定しないと巨大なレスポンスになる。
     *
     * @param authorization "Bearer <access_token>" 形式
     * @param query Drive クエリ言語 (q パラメタ)
     * @param fields 取得フィールド (デフォルトは MDEditor で必要な最小限)
     * @param pageSize 1〜1000 (Drive 仕様)
     * @param pageToken 続きを取りに行く場合の next_page_token
     * @param orderBy "name", "modifiedTime", "modifiedTime desc" 等
     */
    @GET("drive/v3/files")
    suspend fun listFiles(
        @Header("Authorization") authorization: String,
        @Query("q") query: String,
        @Query("fields") fields: String =
            "nextPageToken,files(id,name,mimeType,parents,modifiedTime,size)",
        @Query("pageSize") pageSize: Int = 1000,
        @Query("pageToken") pageToken: String? = null,
        @Query("orderBy") orderBy: String = "name",
    ): DriveFileListResponse

    /**
     * 単一ファイルの本体取得 (alt=media)。
     * MD なら UTF-8 テキスト、画像ならバイナリ。
     * @Streaming を付けることで OkHttp が大きなレスポンスを一括メモリに乗せない。
     */
    @GET("drive/v3/files/{fileId}")
    @Streaming
    suspend fun downloadFile(
        @Header("Authorization") authorization: String,
        @Path("fileId") fileId: String,
        @Query("alt") alt: String = "media",
    ): ResponseBody

    /**
     * 単一ファイルのメタデータ取得 (alt 指定なし → metadata)。
     * fileId が有効か、size がいくつか等の確認用。
     */
    @GET("drive/v3/files/{fileId}")
    suspend fun getFileMetadata(
        @Header("Authorization") authorization: String,
        @Path("fileId") fileId: String,
        @Query("fields") fields: String = "id,name,mimeType,parents,modifiedTime,size",
    ): DriveFile

    /**
     * ファイル本体を更新する (本文の差し替え)。
     *
     * `uploadType=media` モード: メタデータは触らず、本文だけ更新する一番シンプルなパターン。
     * モディファイ時刻は Drive が自動更新する。
     *
     * 注意: ベース URL が `upload/drive/v3/` に変わる (`drive/v3/` ではない)。
     * これは Drive の Resumable / Multipart upload 等を扱う **アップロード用エンドポイント**。
     *
     * @param body content-type を text/markdown 等にして渡す RequestBody
     * @return 更新後のメタデータ (fileId / modifiedTime 等)
     */
    @PATCH("upload/drive/v3/files/{fileId}")
    suspend fun updateFile(
        @Header("Authorization") authorization: String,
        @Path("fileId") fileId: String,
        @Query("uploadType") uploadType: String = "media",
        @Query("fields") fields: String = "id,name,mimeType,parents,modifiedTime,size",
        @Body body: RequestBody,
    ): DriveFile

    /**
     * 「現在時刻」を指す changes pageToken を取得する。
     * 初回 build 完了直後にこれを呼び保存しておけば、次回 sync で
     * 「それ以降に変更されたファイル」だけを listChanges で差分取得できる。
     *
     * 注意: ここで取った時点より「前」の変更は二度と取得できない。
     * したがって build → getStartPageToken の順で呼ぶこと
     * (順序を逆にすると、build 中に発生した変更を取りこぼす)。
     */
    @GET("drive/v3/changes/startPageToken")
    suspend fun getStartPageToken(
        @Header("Authorization") authorization: String,
    ): StartPageTokenResponse

    /**
     * pageToken 以降の変更を取得する。
     *
     * - `restrictToMyDrive=true`: 共有ドライブを除外、My Drive 配下の変更のみ
     * - `includeRemoved=true`: ゴミ箱・完全削除も拾う (差分削除の検出に必須)
     * - `spaces=drive`: 通常の Drive スペース (写真や appData ではない)
     * - 戻り値の `file` には trashed も含めて取得する
     *
     * 注意: My Drive 全体の変更が返るので、Vault root 配下かは呼び出し側で判定すること。
     */
    @GET("drive/v3/changes")
    suspend fun listChanges(
        @Header("Authorization") authorization: String,
        @Query("pageToken") pageToken: String,
        @Query("pageSize") pageSize: Int = 1000,
        @Query("includeRemoved") includeRemoved: Boolean = true,
        @Query("restrictToMyDrive") restrictToMyDrive: Boolean = true,
        @Query("spaces") spaces: String = "drive",
        @Query("fields") fields: String =
            "nextPageToken,newStartPageToken," +
                "changes(fileId,removed,changeType," +
                "file(id,name,mimeType,parents,modifiedTime,size,trashed))",
    ): ChangeListResponse
}
