package com.shostakovich.mdeditor.data.index

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * markdown_files テーブルに対する操作。
 *
 * 使い方:
 *  - getAll(): VaultIndex の起動時ロード
 *  - upsertAll(): バックグラウンド再走査の結果を反映
 *  - deleteByIds(): 同期で消えたファイルを削除
 *  - clear(): 完全リセット (Vault 切替時)
 */
@Dao
interface MarkdownFileDao {

    /**
     * 全件取得。検索画面の初期化用。
     */
    @Query("SELECT * FROM markdown_files")
    suspend fun getAll(): List<MarkdownFileEntity>

    /**
     * INSERT or REPLACE。同一 id があれば上書き (差分同期向け)。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(files: List<MarkdownFileEntity>)

    /**
     * 指定 id 群を削除。同期で「消えたファイル」を反映する用途。
     */
    @Query("DELETE FROM markdown_files WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    /**
     * 全削除。Vault 切替時 or 完全リセット時。
     */
    @Query("DELETE FROM markdown_files")
    suspend fun clear()

    /**
     * 件数取得。デバッグ表示用。
     */
    @Query("SELECT COUNT(*) FROM markdown_files")
    suspend fun count(): Int
}
