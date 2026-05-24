package com.shostakovich.mdeditor.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import net.openid.appauth.AuthState
import org.json.JSONException

/**
 * AppAuth の AuthState を EncryptedSharedPreferences に永続化する object シングルトン。
 *
 * 設計方針:
 * - AuthState は AppAuth が提供する serialize/deserialize メソッドを持つ
 *   (jsonSerializeString / jsonDeserialize)。そのまま String として保存する。
 * - 暗号化は androidx.security.crypto.EncryptedSharedPreferences に委譲。
 *   - キーは Android Keystore に保管される MasterKey で暗号化される
 *   - 値は AES256-GCM、キー名は AES256-SIV (決定的暗号、検索可能性のため)
 * - プロセス内で1回 init すれば良い (MainActivity から AuthManager.init 経由)。
 *
 * 注意:
 * - access_token は短命 (通常 1 時間) なので、保存価値があるのは実質 refresh_token。
 * - AuthState.jsonSerializeString() は refresh_token も含めて全部直列化する。
 * - EncryptedSharedPreferences は端末ロック解除中のみ復号可能 (アプリプロセスとして)。
 */
object TokenStorage {
    private const val TAG = "TokenStorage"

    /** SharedPreferences のファイル名。data/data/<package>/shared_prefs/ 配下にできる。 */
    private const val PREFS_FILE_NAME = "mdeditor_secure_prefs"

    /** AuthState を保存するキー。 */
    private const val KEY_AUTH_STATE = "auth_state_json"

    @Volatile
    private var prefs: SharedPreferences? = null

    /**
     * EncryptedSharedPreferences を初期化する。多重呼び出ししても安全。
     */
    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs == null) {
                val masterKey = MasterKey.Builder(context.applicationContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                prefs = EncryptedSharedPreferences.create(
                    context.applicationContext,
                    PREFS_FILE_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
                Log.d(TAG, "EncryptedSharedPreferences initialized")
            }
        }
    }

    /**
     * AuthState を暗号化して保存する。
     * 認証成功直後 or refresh_token 更新直後に呼ぶ。
     */
    fun save(authState: AuthState) {
        val p = prefs ?: run {
            Log.w(TAG, "save() called before init(), no-op")
            return
        }
        val json = authState.jsonSerializeString()
        p.edit().putString(KEY_AUTH_STATE, json).apply()
        Log.d(TAG, "AuthState saved (jsonLength=${json.length}, isAuthorized=${authState.isAuthorized})")
    }

    /**
     * 保存されている AuthState を復元する。
     * 一度もログインしていない / クリアされた / パース失敗 の場合は null。
     */
    fun load(): AuthState? {
        val p = prefs ?: run {
            Log.w(TAG, "load() called before init(), returning null")
            return null
        }
        val json = p.getString(KEY_AUTH_STATE, null) ?: return null
        return try {
            AuthState.jsonDeserialize(json).also {
                Log.d(TAG, "AuthState restored from storage (isAuthorized=${it.isAuthorized})")
            }
        } catch (e: JSONException) {
            // 古い形式 / 破損データなど。クリアして null を返す。
            Log.e(TAG, "Failed to deserialize AuthState, clearing storage", e)
            clear()
            null
        }
    }

    /**
     * 保存されている AuthState を削除する。
     * 明示的ログアウト or 401 (refresh_token も失効) の検知時に呼ぶ予定。
     */
    fun clear() {
        prefs?.edit()?.remove(KEY_AUTH_STATE)?.apply()
        Log.d(TAG, "AuthState cleared from storage")
    }
}
