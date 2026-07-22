package com.shostakovich.mdeditor.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore
import net.openid.appauth.AuthState

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
 * ## クラッシュ耐性 (v1.12)
 *
 * 端末修理・OS更新・データ復元などで「暗号化済み prefs と Android Keystore の鍵状態が
 * 不整合」になると、EncryptedSharedPreferences.create() が AEADBadTagException
 * (Signature/MAC verification failed) を投げる。v1.11 まではこれが MainActivity.onCreate
 * まで突き抜けて起動即クラッシュしていた (Galaxy S25 修理後に実際に発生)。
 *
 * v1.12 からは init() が例外を外に漏らさない3段階の状態機械になっている:
 *
 *  1. 通常初期化                                → InitResult.Ok
 *  2. 失敗 → 壊れた secure prefs と MasterKey を
 *     削除して再初期化 (認証データのみ破棄)      → InitResult.Recovered (要・再ログイン)
 *  3. それでも失敗 → prefs = null のまま進行
 *     (未認証扱い・保存不可、ただし落ちない)     → InitResult.Degraded
 *
 * 削除するのは mdeditor_secure_prefs (認証トークン) と対応する Keystore alias だけ。
 * Vault 選択・検索インデックス・UI設定・Drive 上のデータには一切触れない。
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

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    /** init() の結果。UI (LoginScreen) が復旧告知の表示判定に使う。 */
    sealed interface InitResult {
        /** 正常初期化。保存済みの認証状態があればそのまま使える。 */
        data object Ok : InitResult

        /**
         * 暗号ストレージが壊れていた (Keystore 不整合等) ためリセットして復旧した。
         * 保存されていた認証情報は失われたので再ログインが必要。
         */
        data object Recovered : InitResult

        /**
         * リセットしても暗号ストレージを初期化できなかった。
         * このプロセスでは認証状態を永続化できない (ログインしても次回起動で消える)。
         */
        data object Degraded : InitResult
    }

    @Volatile
    var initResult: InitResult = InitResult.Ok
        private set

    @Volatile
    private var prefs: SharedPreferences? = null

    /**
     * EncryptedSharedPreferences を初期化する。多重呼び出ししても安全。
     * どんな失敗が起きても例外を呼び出し元 (MainActivity 起動経路) に伝播させない。
     */
    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            val app = context.applicationContext
            initResult = runInit(
                create = { createEncryptedPrefs(app) },
                reset = { resetEncryptedStorage(app) },
            )
            Log.d(TAG, "init finished: $initResult")
        }
    }

    /**
     * 初期化の状態機械。テスト用に生成・削除の実装を注入できるよう分離してある。
     * 例外はここで全て堰き止める。
     */
    internal fun runInit(
        create: () -> SharedPreferences,
        reset: () -> Unit,
    ): InitResult {
        // attempt 1: 通常初期化
        try {
            prefs = create()
            return InitResult.Ok
        } catch (e: Exception) {
            // AEADBadTagException / KeyStoreException / IOException 等。
            // 端末のセキュリティ状態変化で暗号データが復号不能になった典型ケース。
            Log.e(TAG, "secure prefs init failed, resetting encrypted auth storage", e)
        }

        // 壊れた暗号化データと MasterKey を破棄 (認証ストレージのみ)
        try {
            reset()
        } catch (e: Exception) {
            Log.e(TAG, "secure prefs reset failed (continuing to retry anyway)", e)
        }

        // attempt 2: まっさらな状態で再初期化
        try {
            prefs = create()
            return InitResult.Recovered
        } catch (e: Exception) {
            Log.e(TAG, "secure prefs re-init after reset failed, running degraded", e)
        }

        // 全滅: prefs = null のまま。save/load は no-op になり、アプリは未認証として動く。
        return InitResult.Degraded
    }

    private fun createEncryptedPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /**
     * 壊れた暗号化認証ストレージを物理削除する。
     * 対象は secure prefs ファイル本体と、それを暗号化していた Keystore の MasterKey のみ。
     * (VaultRootStorage / PageTokenStorage / UiPrefs / Room / Drive 上のデータは対象外)
     */
    private fun resetEncryptedStorage(context: Context) {
        // prefs ファイル削除 (中に暗号化済み keyset と暗号化済み AuthState が入っている)
        val deleted = context.deleteSharedPreferences(PREFS_FILE_NAME)
        Log.w(TAG, "deleted corrupt secure prefs file: $deleted")
        // Keystore 側の鍵も作り直す (鍵自体が修理・復元で不整合になった可能性に備える)
        try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (ks.containsAlias(MasterKey.DEFAULT_MASTER_KEY_ALIAS)) {
                ks.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                Log.w(TAG, "deleted master key alias from AndroidKeyStore")
            }
        } catch (e: Exception) {
            // alias 削除に失敗しても prefs ファイルは消えているので再初期化は試せる
            Log.w(TAG, "master key alias delete failed (continuing)", e)
        }
    }

    /**
     * AuthState を暗号化して保存する。
     * 認証成功直後 or refresh_token 更新直後に呼ぶ。
     * 書き込み時の暗号化失敗でもクラッシュさせない (保存されないだけ)。
     */
    fun save(authState: AuthState) {
        val p = prefs ?: run {
            Log.w(TAG, "save() skipped: secure prefs unavailable (degraded or before init)")
            return
        }
        try {
            val json = authState.jsonSerializeString()
            p.edit().putString(KEY_AUTH_STATE, json).apply()
            Log.d(TAG, "AuthState saved (jsonLength=${json.length}, isAuthorized=${authState.isAuthorized})")
        } catch (e: Exception) {
            Log.e(TAG, "AuthState save failed (ignored, will require re-login next launch)", e)
        }
    }

    /**
     * 保存されている AuthState を復元する。
     * 一度もログインしていない / クリアされた / 復号・パース失敗 の場合は null。
     *
     * 注: EncryptedSharedPreferences は値の復号を getString() 時に行うため、
     * init が通っても読み出しで初めて失敗するケースがある。その場合も
     * ストレージをクリアして null (= 未認証) に落とす。
     */
    fun load(): AuthState? {
        val p = prefs ?: run {
            Log.w(TAG, "load() skipped: secure prefs unavailable, returning null")
            return null
        }
        return try {
            val json = p.getString(KEY_AUTH_STATE, null) ?: return null
            AuthState.jsonDeserialize(json).also {
                Log.d(TAG, "AuthState restored from storage (isAuthorized=${it.isAuthorized})")
            }
        } catch (e: Exception) {
            // 復号失敗 (SecurityException 系) / 旧形式・破損データ (JSONException)。
            // クリアして未認証扱いに落とす。
            Log.e(TAG, "Failed to load AuthState, clearing storage", e)
            clear()
            null
        }
    }

    /**
     * 保存されている AuthState を削除する。
     * 明示的ログアウト or 401 (refresh_token も失効) の検知時に呼ぶ。
     */
    fun clear() {
        try {
            prefs?.edit()?.remove(KEY_AUTH_STATE)?.apply()
            Log.d(TAG, "AuthState cleared from storage")
        } catch (e: Exception) {
            Log.e(TAG, "AuthState clear failed (ignored)", e)
        }
    }

    /** テスト専用: シングルトン状態を初期化前に戻す。 */
    internal fun resetForTest() {
        prefs = null
        initResult = InitResult.Ok
    }
}
