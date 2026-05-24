package com.shostakovich.mdeditor.data.index

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * MDEditor のアプリ DB。検索インデックスを保持する。
 *
 * バージョン管理:
 *  - 1: 初期スキーマ (markdown_files)
 *  - 将来テーブル追加・カラム変更時にバージョンを上げ、Migration を書く
 *
 * 現状は exportSchema = false。本格運用前に true にしてスキーマ JSON 出力推奨。
 */
@Database(
    entities = [MarkdownFileEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class MDEditorDatabase : RoomDatabase() {
    abstract fun markdownFileDao(): MarkdownFileDao
}

/**
 * DB シングルトンプロバイダ。プロセスに1つだけインスタンスを持つ。
 * MainActivity から init() する。
 */
object IndexDatabaseProvider {
    private const val DB_NAME = "mdeditor_index.db"

    @Volatile
    private var instance: MDEditorDatabase? = null

    fun init(context: Context): MDEditorDatabase {
        return instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                MDEditorDatabase::class.java,
                DB_NAME,
            )
                // 開発中は破壊的 migration で OK。本番運用時は Migration を書く
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
                .also { instance = it }
        }
    }

    /** 既に init 済みのインスタンスを取得。未初期化なら error。 */
    fun get(): MDEditorDatabase = instance
        ?: error("IndexDatabaseProvider.init(context) を MainActivity 等で先に呼ぶこと")
}
