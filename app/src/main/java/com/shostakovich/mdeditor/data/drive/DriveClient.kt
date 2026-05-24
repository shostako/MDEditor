package com.shostakovich.mdeditor.data.drive

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Drive API 用 Retrofit クライアントのプロセスシングルトン。
 *
 * 設計方針:
 * - OkHttp + Retrofit + kotlinx.serialization の標準構成
 * - Logging Interceptor は BASIC レベル (URL とステータスコードのみ、ボディは出さない)
 *   * BODY レベルにすると access_token を含むヘッダや巨大なファイル本体までログに出るので注意
 * - JSON は ignoreUnknownKeys = true: Drive API は将来フィールドが増えても落ちないように
 * - タイムアウトはやや長め (画像取得を想定)
 */
object DriveClient {

    private val json: Json = Json {
        ignoreUnknownKeys = true
        // Drive API の field 名はキャメルケースなのでデフォルトのままで OK
    }

    private val loggingInterceptor: HttpLoggingInterceptor =
        HttpLoggingInterceptor().apply {
            // BODY にすると Authorization ヘッダの token もログに出る危険がある。
            // BASIC は HTTP method, URL, レスポンスコード, レスポンス時間まで。
            level = HttpLoggingInterceptor.Level.BASIC
        }

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val retrofit: Retrofit = Retrofit.Builder()
        .baseUrl("https://www.googleapis.com/")
        .client(okHttpClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    val api: DriveApi = retrofit.create(DriveApi::class.java)

    /**
     * Authorization ヘッダーの値を組み立てる便利関数。
     * Drive API は "Bearer <token>" 形式。
     */
    fun bearer(accessToken: String): String = "Bearer $accessToken"
}
