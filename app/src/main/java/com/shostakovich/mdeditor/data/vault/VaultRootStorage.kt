package com.shostakovich.mdeditor.data.vault

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * 選択された Vault root の fileId / 表示名を SharedPreferences に永続化する。
 *
 * 設計方針:
 * - 秘匿性は不要 (Google Drive の fileId は知っていてもログイン無しではアクセスできない)
 *   なので EncryptedSharedPreferences は使わず素の SharedPreferences で OK。
 * - プロセスシングルトンとして MainActivity から init する。
 *
 * 保存項目:
 *  - vaultRootId: Drive の fileId (例: "1AbCdEf...")
 *  - vaultRootName: 表示用名前 (例: "BrainDump")
 *
 * 想定運用:
 *  - 未選択時は VaultScreen を表示してユーザーに選ばせる
 *  - 選択済みなら FileTreeScreen に直行できる
 *  - 何らかの理由で root が無効化されたら (削除等) clear() してやり直し
 */
object VaultRootStorage {
    private const val TAG = "VaultRootStorage"
    private const val PREFS_NAME = "mdeditor_vault_prefs"
    private const val KEY_VAULT_ID = "vault_root_id"
    private const val KEY_VAULT_NAME = "vault_root_name"

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs == null) {
                prefs = context.applicationContext
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                Log.d(TAG, "VaultRootStorage initialized")
            }
        }
    }

    /**
     * Vault root の fileId と名前を保存する。
     * VaultScreen でユーザーが選択した直後に呼ぶ。
     */
    fun save(vaultId: String, vaultName: String) {
        val p = prefs ?: run {
            Log.w(TAG, "save() before init(), no-op")
            return
        }
        p.edit()
            .putString(KEY_VAULT_ID, vaultId)
            .putString(KEY_VAULT_NAME, vaultName)
            .apply()
        Log.d(TAG, "Vault root saved: name=$vaultName id=${vaultId.take(10)}...")
    }

    /** 保存済みの fileId (未選択なら null) */
    fun loadVaultId(): String? = prefs?.getString(KEY_VAULT_ID, null)

    /** 保存済みの表示名 (未選択なら null) */
    fun loadVaultName(): String? = prefs?.getString(KEY_VAULT_NAME, null)

    /** 選択済みか */
    fun isSelected(): Boolean = loadVaultId() != null

    /** 選択解除。Vault を変更したい時に呼ぶ。 */
    fun clear() {
        prefs?.edit()?.remove(KEY_VAULT_ID)?.remove(KEY_VAULT_NAME)?.apply()
        Log.d(TAG, "Vault root cleared")
    }
}
