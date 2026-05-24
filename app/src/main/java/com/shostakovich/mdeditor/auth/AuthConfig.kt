package com.shostakovich.mdeditor.auth

import com.shostakovich.mdeditor.BuildConfig

/**
 * OAuth 2.0 認可フローで使う設定値を集約する object。
 *
 * クライアントタイプ:
 * - GCP の OAuth クライアントは **"Desktop アプリ"** タイプ (PKCE 公開クライアントとして使用)。
 * - 当初 "Android" タイプを使ったが、AppAuth + Drive scope で
 *   "invalid_request" になる罠を踏んだので Desktop タイプに切替済み。
 *
 * セキュリティ方針:
 * - Desktop タイプは GCP から client_secret が発行され、Google は Token Endpoint で
 *   この secret を必須として受理する (client_secret 無しだと invalid_client エラー)。
 * - Google 公式仕様で「Desktop の client_secret は実質 secret ではない、
 *   インストール型アプリに埋め込む前提」と明言されている:
 *   https://developers.google.com/identity/protocols/oauth2/native-app
 * - 本人性は PKCE の code_verifier で担保される。secret は補助的な役割。
 *
 * 値の取り扱い:
 * - CLIENT_ID / CLIENT_SECRET は local.properties に置き、build.gradle.kts 経由で
 *   BuildConfig フィールドとして注入される。
 * - local.properties は .gitignore 済なのでソース管理に入らない。
 * - 新規セットアップ手順は local.properties.example 参照。
 * - secret が漏れたら GCP コンソールでローテート可能 (CLIENT_ID は据置で再発行)。
 */
object AuthConfig {
    // local.properties -> BuildConfig 経由で注入。
    // ★この値を変更したら local.properties も書き換えて clean build すること。
    //   (build.gradle.kts が manifestPlaceholders も同じ値から導出するので同期する)
    val CLIENT_ID: String = BuildConfig.AUTH_CLIENT_ID

    // Google Desktop OAuth クライアントの client_secret。
    // Desktop タイプは Google 公式が「APK 埋め込み OK、実質公開」と明言。
    // とはいえソース直書きは避けたいので local.properties に分離。
    val CLIENT_SECRET: String = BuildConfig.AUTH_CLIENT_SECRET

    // Google OAuth 2.0 標準エンドポイント (公開情報)
    const val AUTHORIZATION_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
    const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
    const val END_SESSION_ENDPOINT = "https://oauth2.googleapis.com/revoke"

    /**
     * CLIENT_ID から導出した reverse-domain スキーム。
     * 例: "123456789-abc.apps.googleusercontent.com"
     *  → "com.googleusercontent.apps.123456789-abc"
     *
     * これは Google が Desktop タイプ OAuth クライアントに対して
     * "custom URI scheme for installed apps" として予約している形式。
     * https://developers.google.com/identity/protocols/oauth2/native-app
     *
     * 注: build.gradle.kts 側でも同じ値を manifestPlaceholders["appAuthRedirectScheme"]
     * として使う (AndroidManifest の <data android:scheme>)。同じ CLIENT_ID から
     * 導出するので自動的に一致する。
     */
    private val REVERSE_CLIENT_ID: String =
        "com.googleusercontent.apps." +
            CLIENT_ID.removeSuffix(".apps.googleusercontent.com")

    /**
     * AppAuth が認可レスポンスを受け取る redirect URI。
     * Google Desktop タイプは <REVERSE_CLIENT_ID>:/<任意のpath> を受理する。
     * path 部分 "/oauth2redirect" は AppAuth サンプル準拠の慣習値。
     */
    val REDIRECT_URI: String = "$REVERSE_CLIENT_ID:/oauth2redirect"

    /**
     * 要求するスコープ。
     * - drive: Drive 全体の読み書き (Vault 全体を扱うため必要)
     *
     * 注: openid + email は当初足していたが、Google の OAuth クライアントで
     * Drive scope と混在させると invalid_request になる罠を踏んだ。
     * ログインユーザーのメアド表示が必要になったら drive.about.get?fields=user
     * から取得する方針に変更する。
     */
    val SCOPES = listOf(
        "https://www.googleapis.com/auth/drive"
    )
}
