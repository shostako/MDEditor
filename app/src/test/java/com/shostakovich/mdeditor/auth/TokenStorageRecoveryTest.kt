package com.shostakovich.mdeditor.auth

import android.content.SharedPreferences
import java.security.GeneralSecurityException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * TokenStorage の初期化状態機械 (runInit) のユニットテスト。
 *
 * v1.11 実機クラッシュ (Galaxy S25 修理後、EncryptedSharedPreferences.create が
 * AEADBadTagException → MainActivity.onCreate ごとプロセス死) の再発防止。
 *
 * 実物の EncryptedSharedPreferences / Android Keystore は JVM 単体テストでは動かないので、
 * 生成 (create) と破棄 (reset) を fake で注入し、フォールバック遷移だけを検証する:
 *
 *   1回目成功                → Ok       (reset は呼ばれない)
 *   1回目失敗 → reset → 成功 → Recovered (reset が1回呼ばれる)
 *   全部失敗                 → Degraded (save/load が no-op で落ちない)
 *
 * ※ android.util.Log は testOptions.unitTests.isReturnDefaultValues = true で無害化済み。
 */
class TokenStorageRecoveryTest {

    /** getString/edit だけ実装した in-memory fake。他は単体テストでは呼ばれない。 */
    private class FakePrefs : SharedPreferences {
        val map = mutableMapOf<String, Any?>()

        override fun getString(key: String?, defValue: String?): String? =
            map[key] as? String ?: defValue

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removals = mutableSetOf<String>()
            override fun putString(key: String, value: String?) = apply { pending[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values }
            override fun putInt(key: String, value: Int) = apply { pending[key] = value }
            override fun putLong(key: String, value: Long) = apply { pending[key] = value }
            override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
            override fun remove(key: String) = apply { removals.add(key) }
            override fun clear() = apply { removals.addAll(map.keys) }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                removals.forEach { map.remove(it) }
                map.putAll(pending)
                pending.clear()
                removals.clear()
            }
        }

        override fun getAll(): MutableMap<String, *> = map
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
            @Suppress("UNCHECKED_CAST")
            return map[key] as? MutableSet<String> ?: defValues
        }
        override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit
    }

    @Before
    fun setUp() {
        TokenStorage.resetForTest()
    }

    @After
    fun tearDown() {
        TokenStorage.resetForTest()
    }

    @Test
    fun `正常初期化なら Ok で reset は呼ばれない`() {
        var resetCalls = 0
        val result = TokenStorage.runInit(
            create = { FakePrefs() },
            reset = { resetCalls++ },
        )
        assertEquals(TokenStorage.InitResult.Ok, result)
        assertEquals(0, resetCalls)
    }

    @Test
    fun `復号失敗からリセットで復旧したら Recovered`() {
        // 実機で観測した AEADBadTagException は GeneralSecurityException のサブクラス
        var attempts = 0
        var resetCalls = 0
        val result = TokenStorage.runInit(
            create = {
                attempts++
                if (attempts == 1) throw GeneralSecurityException("AEADBadTag: MAC verification failed")
                FakePrefs()
            },
            reset = { resetCalls++ },
        )
        assertEquals(TokenStorage.InitResult.Recovered, result)
        assertEquals(2, attempts)
        assertEquals(1, resetCalls)
    }

    @Test
    fun `リセット後も失敗するなら Degraded で例外は漏れない`() {
        val result = TokenStorage.runInit(
            create = { throw GeneralSecurityException("keystore is hopeless") },
            reset = { },
        )
        assertEquals(TokenStorage.InitResult.Degraded, result)
    }

    @Test
    fun `reset 自体が例外を投げても runInit は落ちず再試行する`() {
        var attempts = 0
        val result = TokenStorage.runInit(
            create = {
                attempts++
                if (attempts == 1) throw GeneralSecurityException("broken")
                FakePrefs()
            },
            reset = { throw RuntimeException("deleteSharedPreferences failed") },
        )
        // reset が失敗しても attempt 2 は実行され、成功すれば Recovered
        assertEquals(TokenStorage.InitResult.Recovered, result)
    }

    @Test
    fun `Degraded 状態でも save と load はクラッシュしない`() {
        val result = TokenStorage.runInit(
            create = { throw GeneralSecurityException("always broken") },
            reset = { },
        )
        assertEquals(TokenStorage.InitResult.Degraded, result)
        // prefs = null のまま → save は no-op、load は null
        TokenStorage.save(net.openid.appauth.AuthState())
        assertNull(TokenStorage.load())
        // clear も落ちない
        TokenStorage.clear()
    }

    @Test
    fun `復旧後の prefs で save と load がラウンドトリップする`() {
        var attempts = 0
        val fake = FakePrefs()
        val result = TokenStorage.runInit(
            create = {
                attempts++
                if (attempts == 1) throw GeneralSecurityException("broken once")
                fake
            },
            reset = { },
        )
        assertEquals(TokenStorage.InitResult.Recovered, result)
        // 復旧直後は保存物なし = 未認証
        assertNull(TokenStorage.load())
        // 空の AuthState を保存 → 読み戻しても例外にならない
        TokenStorage.save(net.openid.appauth.AuthState())
        val restored = TokenStorage.load()
        assertTrue(restored != null && !restored.isAuthorized)
    }
}
