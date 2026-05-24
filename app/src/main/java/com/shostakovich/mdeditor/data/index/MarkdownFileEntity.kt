package com.shostakovich.mdeditor.data.index

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Vault 配下の MD ファイル1件を表す Room エンティティ。
 *
 * 検索インデックス用に Vault 全体の MD ファイルメタデータをこのテーブルに保持する。
 * 本文はサイズが大きいので別キャッシュ (FileContentCache) に分離 = ここには持たない。
 *
 * フィールド:
 *  - id: Drive fileId (主キー、これで一意)
 *  - name: ファイル名 (.md 付き)
 *  - mimeType: text/markdown 等
 *  - folderPath: "BrainDump > 極薄プレート" のような表示用パス
 *  - modifiedTime: Drive 上の最終更新時刻 (ISO8601)。差分同期判定にも使う
 *  - size: バイト数 (文字列、Drive 仕様)
 *
 * 注: vaultRootId を別途持って、複数 Vault に対応する設計にも拡張可能。
 * 今は 1 Vault 前提で持たない。
 */
@Entity(tableName = "markdown_files")
data class MarkdownFileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val mimeType: String,
    val folderPath: String,
    val modifiedTime: String?,
    val size: String?,
)
