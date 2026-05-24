package com.shostakovich.mdeditor.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ClientSecretPost
import net.openid.appauth.ResponseTypeValues

/**
 * AppAuth-Android の薄いラッパー。プロセスシングルトン。
 *
 * 設計方針:
 * - AuthorizationService はネイティブリソース (ブラウザカスタムタブの管理) を持つので
 *   Activity 単位ではなく Application スコープに置く。本来は Application class を作るのが綺麗だが、
 *   現段階では object シングルトンで代用。M2-c で永続化を入れる際に再検討する。
 * - authState は AppAuth が提供する状態オブジェクト。トークン保持・更新の責務をここに集約。
 * - M2-a 時点ではメモリ保持のみ。M2-c で EncryptedSharedPreferences に永続化。
 *
 * 使い方:
 * 1. Application or MainActivity の onCreate で AuthManager.init(this)
 * 2. ログインボタン押下で getAuthorizationRequestIntent() を取得 → ActivityResult launcher へ渡す
 * 3. 戻ってきた intent を handleAuthorizationResponse() に渡す (M2-b で実装)
 * 4. プロセス終了時 (onDestroy 等) で dispose()
 */
object AuthManager {
    private const val TAG = "AuthManager"

    @Volatile
    private var _authService: AuthorizationService? = null
    val authService: AuthorizationService
        get() = _authService ?: error(
            "AuthManager.init(context) を先に呼ぶこと。MainActivity.onCreate 等で初期化する。"
        )

    /** AppAuth が管理する認証状態 (トークン等)。M2-c で永続化対象になる。 */
    var authState: AuthState = AuthState()
        private set

    /**
     * AppAuth の AuthorizationService を初期化する。
     * 多重呼び出ししても安全。
     *
     * M2-c: TokenStorage も同時に初期化し、保存された AuthState があれば復元する。
     * これによりアプリ再起動後もログイン状態を維持できる。
     */
    fun init(context: Context) {
        if (_authService == null) {
            synchronized(this) {
                if (_authService == null) {
                    TokenStorage.init(context)
                    _authService = AuthorizationService(context.applicationContext)
                    // 永続化されている AuthState を復元 (起動時に1回)。
                    // ここで復元しておくと isAuthorized() がすぐ true を返せる。
                    TokenStorage.load()?.let { restored ->
                        authState = restored
                        Log.d(TAG, "AuthState restored (isAuthorized=${restored.isAuthorized})")
                    }
                    Log.d(TAG, "AuthorizationService initialized")
                }
            }
        }
    }

    /**
     * Authorization Request を組み立てる。
     * - response_type=code (Authorization Code Flow)
     * - PKCE は AppAuth がデフォルトで自動付与する (code_challenge_method=S256)
     */
    fun buildAuthorizationRequest(): AuthorizationRequest {
        val serviceConfig = AuthorizationServiceConfiguration(
            Uri.parse(AuthConfig.AUTHORIZATION_ENDPOINT),
            Uri.parse(AuthConfig.TOKEN_ENDPOINT),
            null,
            Uri.parse(AuthConfig.END_SESSION_ENDPOINT)
        )

        return AuthorizationRequest.Builder(
            serviceConfig,
            AuthConfig.CLIENT_ID,
            ResponseTypeValues.CODE,
            Uri.parse(AuthConfig.REDIRECT_URI)
        )
            .setScopes(AuthConfig.SCOPES)
            // prompt=consent で毎回同意画面を出す (テスト時に挙動が分かりやすい)。
            // 本番運用では外していい。
            .setPrompt("consent")
            .build()
    }

    /**
     * Authorization Request を Chrome Custom Tabs で起動するための Intent を取得。
     */
    fun getAuthorizationRequestIntent(): Intent {
        val request = buildAuthorizationRequest()
        return authService.getAuthorizationRequestIntent(request)
    }

    /**
     * ブラウザから戻ってきた intent を受け取り、Authorization Code を抽出後、
     * Token Endpoint に POST して access_token / refresh_token を取得する。
     *
     * 注: AuthorizationService.performTokenRequest はコールバック方式。
     * Token 取得は非同期 (ネットワーク I/O) なので onResult も非同期で呼ばれる。
     *
     * M2-c で永続化を実装する際は、ここで取得した authState を
     * EncryptedSharedPreferences に保存する。
     */
    fun handleAuthorizationResponse(
        data: Intent?,
        onResult: (success: Boolean, message: String) -> Unit
    ) {
        if (data == null) {
            onResult(false, "Authorization intent is null (キャンセル or 異常終了)")
            return
        }
        val response = AuthorizationResponse.fromIntent(data)
        val exception = AuthorizationException.fromIntent(data)

        when {
            response != null -> {
                authState.update(response, exception)
                Log.d(
                    TAG,
                    "Authorization succeeded. authCode=${response.authorizationCode?.take(10)}..."
                )
                // M2-b: Authorization Code を access_token に交換する。
                // AppAuth が PKCE の code_verifier を保持しているので、
                // createTokenExchangeRequest() だけで PKCE 検証付き TokenRequest が作れる。
                //
                // 注: Google Desktop OAuth クライアントは PKCE があっても
                // client_secret を必須要求する仕様 (invalid_client / "client_secret is missing")。
                // ClientSecretPost で POST body に client_secret を含めて送る。
                // (Desktop タイプの secret は Google 公式が「APK 埋め込み前提」と明言、AuthConfig.kt 参照)
                val tokenRequest = response.createTokenExchangeRequest()
                val clientAuth = ClientSecretPost(AuthConfig.CLIENT_SECRET)
                authService.performTokenRequest(tokenRequest, clientAuth) { tokenResponse, tokenException ->
                    when {
                        tokenResponse != null -> {
                            authState.update(tokenResponse, tokenException)
                            // M2-c: 永続化。次回起動時にここで保存した refresh_token から復元できる。
                            TokenStorage.save(authState)
                            Log.d(
                                TAG,
                                "Token exchange succeeded. " +
                                    "accessToken=${tokenResponse.accessToken?.take(10)}... " +
                                    "refreshToken=${if (tokenResponse.refreshToken != null) "present" else "absent"} " +
                                    "expiresIn=${tokenResponse.accessTokenExpirationTime}"
                            )
                            onResult(true, "認証完了 (アクセストークン取得)")
                        }
                        tokenException != null -> {
                            Log.e(
                                TAG,
                                "Token exchange failed: ${tokenException.errorDescription}",
                                tokenException
                            )
                            onResult(
                                false,
                                "トークン交換失敗: ${tokenException.errorDescription ?: tokenException.message}"
                            )
                        }
                        else -> {
                            onResult(false, "想定外: tokenResponse も tokenException も null")
                        }
                    }
                }
            }
            exception != null -> {
                Log.e(TAG, "Authorization failed: ${exception.errorDescription}", exception)
                onResult(false, "認可失敗: ${exception.errorDescription ?: exception.message}")
            }
            else -> {
                onResult(false, "想定外: response も exception も null")
            }
        }
    }

    /**
     * 現在ログイン済みかを返す。
     * authState.isAuthorized は access_token を1度でも取得していれば true。
     * (期限切れトークンも true を返す点に注意。実利用前に getFreshAccessToken() で更新する想定)
     */
    fun isAuthorized(): Boolean = authState.isAuthorized

    /**
     * ログアウト処理。永続化された AuthState を消し、メモリ上の authState も空に戻す。
     * これ以降 `isAuthorized()` は false を返す。
     *
     * 注: Drive 側のトークン失効 (revoke) は呼んでない。必要なら
     * `https://oauth2.googleapis.com/revoke?token=<refresh_token>` を POST すれば足りる。
     * 個人用 APK では削除だけで十分。
     */
    fun logout() {
        TokenStorage.clear()
        authState = AuthState()
        Log.d(TAG, "logged out")
    }

    /**
     * 有効な access_token を取得する。期限切れなら refresh_token で自動更新。
     * M3 で Drive API クライアントから呼び出す予定。
     *
     * 注: authState.performActionWithFreshTokens はコールバック方式。
     * Coroutines に変換するなら suspendCoroutine 経由が標準。
     *
     * Token refresh 時も Google Desktop タイプは client_secret を要求するので、
     * ClientSecretPost を3引数版に渡す。
     */
    fun getFreshAccessToken(
        onResult: (accessToken: String?, error: Throwable?) -> Unit
    ) {
        val clientAuth = ClientSecretPost(AuthConfig.CLIENT_SECRET)
        authState.performActionWithFreshTokens(authService, clientAuth) { accessToken, _, ex ->
            // refresh が走った場合、AuthState 内の access_token / 有効期限 / (まれに refresh_token) が
            // 更新されているので永続化を上書きする。エラー時はトークンが無効化されてる可能性も
            // あるが、AuthState 内に exception 情報も保持されるので合わせて保存しておく。
            TokenStorage.save(authState)
            onResult(accessToken, ex)
        }
    }

    /**
     * Coroutines 用の suspend ラッパー。
     * Drive API リクエスト直前に呼んで Authorization ヘッダーに乗せる用途。
     *
     * - access_token が期限内なら即座に返る
     * - 期限切れなら refresh_token で内部的に更新してから返す
     * - refresh も失敗したら例外を投げる
     */
    suspend fun freshAccessToken(): String = suspendCancellableCoroutine { cont ->
        getFreshAccessToken { token, error ->
            when {
                token != null -> cont.resume(token)
                error != null -> cont.resumeWithException(error)
                else -> cont.resumeWithException(
                    IllegalStateException("access_token も error も null")
                )
            }
        }
    }

    /**
     * AuthorizationService のリソースを解放する。
     * Activity onDestroy 等で呼ぶ。
     */
    fun dispose() {
        _authService?.dispose()
        _authService = null
        Log.d(TAG, "AuthorizationService disposed")
    }
}
