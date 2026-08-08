package com.shostakovich.mdeditor.data.vault

import com.shostakovich.mdeditor.data.drive.DriveChange
import com.shostakovich.mdeditor.data.drive.DriveFile
import com.shostakovich.mdeditor.data.index.MarkdownFileEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VaultIndex の差分同期ロジック (classifyChanges / computeFolderPath) のユニットテスト。
 *
 * - Drive API / Room / Android Log への依存はラムダ・引数で隔離されているので、
 *   Robolectric なしで純粋に JVM 上で実行できる。
 * - 検証対象は M8-b の差分同期の意思決定ロジック。実際の Drive 通信や DB 書き込みは
 *   インテグレーションテスト相当 (= 実機で確認) に任せる。
 */
class VaultIndexLogicTest {

    // テストでよく使う定数
    private val VAULT_ID = "vault-root-id"
    private val VAULT_NAME = "BrainDump"

    // -----------------------------------------------------------------------------------
    // classifyChanges
    // -----------------------------------------------------------------------------------

    @Test
    fun `classifyChanges - empty changes returns empty diff`() = runTest {
        val diff = VaultIndex.classifyChanges(
            changes = emptyList(),
            existingMap = emptyMap(),
            vaultRootId = VAULT_ID,
            vaultRootName = VAULT_NAME,
            getMetadata = { error("should not be called") },
        )
        assertTrue(diff.toUpsert.isEmpty())
        assertTrue(diff.toDelete.isEmpty())
    }

    @Test
    fun `classifyChanges - removed change for existing file is deleted`() = runTest {
        val existing = mapOf("f1" to mdEntity("f1", "note.md", "BrainDump > sub"))
        val diff = VaultIndex.classifyChanges(
            changes = listOf(DriveChange(fileId = "f1", removed = true)),
            existingMap = existing,
            vaultRootId = VAULT_ID,
            vaultRootName = VAULT_NAME,
            getMetadata = { error("should not be called") },
        )
        assertEquals(listOf("f1"), diff.toDelete)
        assertTrue(diff.toUpsert.isEmpty())
    }

    @Test
    fun `classifyChanges - removed change for unknown file is ignored`() = runTest {
        val diff = VaultIndex.classifyChanges(
            changes = listOf(DriveChange(fileId = "unknown", removed = true)),
            existingMap = emptyMap(),
            vaultRootId = VAULT_ID,
            vaultRootName = VAULT_NAME,
            getMetadata = { error("should not be called") },
        )
        assertTrue(diff.toDelete.isEmpty())
        assertTrue(diff.toUpsert.isEmpty())
    }

    @Test
    fun `classifyChanges - trashed file for existing is deleted`() = runTest {
        val existing = mapOf("f1" to mdEntity("f1", "note.md", "BrainDump"))
        val diff = VaultIndex.classifyChanges(
            changes = listOf(
                DriveChange(
                    fileId = "f1",
                    file = driveMd("f1", "note.md", parents = listOf(VAULT_ID), trashed = true),
                )
            ),
            existingMap = existing,
            vaultRootId = VAULT_ID,
            vaultRootName = VAULT_NAME,
            getMetadata = { error("should not be called") },
        )
        assertEquals(listOf("f1"), diff.toDelete)
        assertTrue(diff.toUpsert.isEmpty())
    }

    @Test
    fun `classifyChanges - existing md updated keeps folderPath`() = runTest {
        val existing = mapOf(
            "f1" to mdEntity("f1", "old-name.md", "BrainDump > 極薄プレート")
        )
        val updated = driveMd(
            "f1",
            "new-name.md",
            parents = listOf("some-parent"),
            modifiedTime = "2026-05-24T10:00:00Z",
        )
        val diff = VaultIndex.classifyChanges(
            changes = listOf(DriveChange(fileId = "f1", file = updated)),
            existingMap = existing,
            vaultRootId = VAULT_ID,
            vaultRootName = VAULT_NAME,
            getMetadata = { error("should not be called for existing file") },
        )
        assertEquals(1, diff.toUpsert.size)
        val u = diff.toUpsert[0]
        assertEquals("f1", u.id)
        assertEquals("new-name.md", u.name)
        // 既存の folderPath は維持される (リネーム/移動は forceResync 任せの仕様)
        assertEquals("BrainDump > 極薄プレート", u.folderPath)
        assertEquals("2026-05-24T10:00:00Z", u.modifiedTime)
        assertTrue(diff.toDelete.isEmpty())
    }

    @Test
    fun `classifyChanges - new md under vault root is upserted with computed folderPath`() = runTest {
        // f-new の parents = [vault-root] つまり Vault 直下に新規 .md
        val newFile = driveMd("f-new", "fresh.md", parents = listOf(VAULT_ID))
        val diff = VaultIndex.classifyChanges(
            changes = listOf(DriveChange(fileId = "f-new", file = newFile)),
            existingMap = emptyMap(),
            vaultRootId = VAULT_ID,
            vaultRootName = VAULT_NAME,
            getMetadata = { error("vault root reached without metadata calls") },
        )
        assertEquals(1, diff.toUpsert.size)
        assertEquals("f-new", diff.toUpsert[0].id)
        assertEquals(VAULT_NAME, diff.toUpsert[0].folderPath)
        assertTrue(diff.toDelete.isEmpty())
    }

    @Test
    fun `classifyChanges - new md in deep subfolder builds full path`() = runTest {
        // f-new の親は subfolder-id (BrainDump > 極薄プレート)
        val newFile = driveMd("f-new", "fresh.md", parents = listOf("sub2"))
        val metadata = mapOf(
            "sub2" to DriveFile(
                id = "sub2", name = "極薄プレート",
                mimeType = DriveFile.MIME_FOLDER, parents = listOf("sub1"),
            ),
            "sub1" to DriveFile(
                id = "sub1", name = "Tech",
                mimeType = DriveFile.MIME_FOLDER, parents = listOf(VAULT_ID),
            ),
        )
        val diff = VaultIndex.classifyChanges(
            changes = listOf(DriveChange(fileId = "f-new", file = newFile)),
            existingMap = emptyMap(),
            vaultRootId = VAULT_ID,
            vaultRootName = VAULT_NAME,
            getMetadata = { id -> metadata[id] ?: error("unknown id: $id") },
        )
        assertEquals(1, diff.toUpsert.size)
        assertEquals("BrainDump > Tech > 極薄プレート", diff.toUpsert[0].folderPath)
    }

    @Test
    fun `classifyChanges - new md outside vault is ignored`() = runTest {
        val newFile = driveMd("f-other", "outside.md", parents = listOf("other-folder"))
        val metadata = mapOf(
            "other-folder" to DriveFile(
                id = "other-folder", name = "Other",
                mimeType = DriveFile.MIME_FOLDER, parents = listOf("totally-unrelated-root"),
            ),
            "totally-unrelated-root" to DriveFile(
                id = "totally-unrelated-root", name = "Misc",
                mimeType = DriveFile.MIME_FOLDER, parents = emptyList(),
            ),
        )
        val diff = VaultIndex.classifyChanges(
            changes = listOf(DriveChange(fileId = "f-other", file = newFile)),
            existingMap = emptyMap(),
            vaultRootId = VAULT_ID,
            vaultRootName = VAULT_NAME,
            getMetadata = { id -> metadata[id] ?: error("unknown id: $id") },
        )
        assertTrue(diff.toUpsert.isEmpty())
        assertTrue(diff.toDelete.isEmpty())
    }

    @Test
    fun `classifyChanges - non-markdown change deletes if previously indexed`() = runTest {
        // 過去 .md だったが拡張子変更で .txt になった (or 同じ id のファイルが消えた等)
        val existing = mapOf("f1" to mdEntity("f1", "old.md", "BrainDump"))
        val nowTxt = DriveFile(
            id = "f1", name = "old.txt",
            mimeType = "text/plain",
            parents = listOf(VAULT_ID),
        )
        val diff = VaultIndex.classifyChanges(
            changes = listOf(DriveChange(fileId = "f1", file = nowTxt)),
            existingMap = existing,
            vaultRootId = VAULT_ID,
            vaultRootName = VAULT_NAME,
            getMetadata = { error("should not be called") },
        )
        assertEquals(listOf("f1"), diff.toDelete)
        assertTrue(diff.toUpsert.isEmpty())
    }

    @Test
    fun `classifyChanges - non-markdown change for unknown file is ignored`() = runTest {
        // 同フォルダの画像変更等は無視されるべき
        val image = DriveFile(
            id = "img1", name = "photo.jpg", mimeType = "image/jpeg",
            parents = listOf(VAULT_ID),
        )
        val diff = VaultIndex.classifyChanges(
            changes = listOf(DriveChange(fileId = "img1", file = image)),
            existingMap = emptyMap(),
            vaultRootId = VAULT_ID,
            vaultRootName = VAULT_NAME,
            getMetadata = { error("should not be called") },
        )
        assertTrue(diff.toUpsert.isEmpty())
        assertTrue(diff.toDelete.isEmpty())
    }

    @Test
    fun `classifyChanges - non-file changeType is ignored`() = runTest {
        // 共有ドライブ系の changeType="drive" 等は無視
        val diff = VaultIndex.classifyChanges(
            changes = listOf(DriveChange(fileId = "x", removed = true, changeType = "drive")),
            existingMap = mapOf("x" to mdEntity("x", "x.md", "BrainDump")),
            vaultRootId = VAULT_ID,
            vaultRootName = VAULT_NAME,
            getMetadata = { error("should not be called") },
        )
        assertTrue(diff.toDelete.isEmpty())
        assertTrue(diff.toUpsert.isEmpty())
    }

    @Test
    fun `classifyChanges - duplicate delete for same fileId only emitted once`() = runTest {
        // Drive は同じファイルに対する複数 changes をページ間で返すことがある。
        // toDelete は重複排除されるべき (DAO の deleteByIds でも問題ないが、保険として)。
        val existing = mapOf("f1" to mdEntity("f1", "x.md", "BrainDump"))
        val diff = VaultIndex.classifyChanges(
            changes = listOf(
                DriveChange(fileId = "f1", removed = true),
                DriveChange(fileId = "f1", removed = true),
            ),
            existingMap = existing,
            vaultRootId = VAULT_ID,
            vaultRootName = VAULT_NAME,
            getMetadata = { error("should not be called") },
        )
        assertEquals(listOf("f1"), diff.toDelete)
    }

    @Test
    fun `classifyChanges - mixed batch with delete plus new`() = runTest {
        // 実運用に近い: 1ファイル削除 + 1ファイル更新 + 1ファイル新規
        val existing = mapOf(
            "to-delete" to mdEntity("to-delete", "gone.md", "BrainDump > Old"),
            "to-update" to mdEntity("to-update", "kept.md", "BrainDump > Keep"),
        )
        val updated = driveMd(
            "to-update", "kept-renamed.md",
            parents = listOf("anywhere"), // parents は existing なら無視される
            modifiedTime = "2026-05-24T11:00:00Z",
        )
        val newFile = driveMd("brand-new", "new.md", parents = listOf(VAULT_ID))
        val changes = listOf(
            DriveChange(fileId = "to-delete", removed = true),
            DriveChange(fileId = "to-update", file = updated),
            DriveChange(fileId = "brand-new", file = newFile),
        )
        val diff = VaultIndex.classifyChanges(
            changes = changes,
            existingMap = existing,
            vaultRootId = VAULT_ID,
            vaultRootName = VAULT_NAME,
            getMetadata = { error("vault root reached") },
        )
        assertEquals(listOf("to-delete"), diff.toDelete)
        // upsert 順序は changes 順に依存するが、内容を id で取り出してチェック
        assertEquals(2, diff.toUpsert.size)
        val byId = diff.toUpsert.associateBy { it.id }
        assertEquals("kept-renamed.md", byId["to-update"]!!.name)
        assertEquals("BrainDump > Keep", byId["to-update"]!!.folderPath) // 既存維持
        assertEquals("new.md", byId["brand-new"]!!.name)
        assertEquals(VAULT_NAME, byId["brand-new"]!!.folderPath)
    }

    // -----------------------------------------------------------------------------------
    // computeFolderPath
    // -----------------------------------------------------------------------------------

    @Test
    fun `computeFolderPath - existing file returns stored folderPath`() = runTest {
        val existing = mapOf("f1" to mdEntity("f1", "x.md", "BrainDump > 既存"))
        val file = driveMd("f1", "x.md", parents = listOf("anywhere"))
        val path = VaultIndex.computeFolderPath(
            file = file,
            vaultRootId = VAULT_ID,
            vaultRootName = VAULT_NAME,
            existingMap = existing,
            getMetadata = { error("should not be called for existing") },
        )
        assertEquals("BrainDump > 既存", path)
    }

    @Test
    fun `computeFolderPath - new file directly under vault returns vault name`() = runTest {
        val file = driveMd("new", "x.md", parents = listOf(VAULT_ID))
        val path = VaultIndex.computeFolderPath(
            file = file,
            vaultRootId = VAULT_ID,
            vaultRootName = VAULT_NAME,
            existingMap = emptyMap(),
            getMetadata = { error("should not be called when first parent is vault root") },
        )
        assertEquals(VAULT_NAME, path)
    }

    @Test
    fun `computeFolderPath - new file two levels deep`() = runTest {
        val file = driveMd("new", "x.md", parents = listOf("p2"))
        val metadata = mapOf(
            "p2" to DriveFile(
                id = "p2", name = "B", mimeType = DriveFile.MIME_FOLDER, parents = listOf("p1"),
            ),
            "p1" to DriveFile(
                id = "p1", name = "A", mimeType = DriveFile.MIME_FOLDER, parents = listOf(VAULT_ID),
            ),
        )
        val path = VaultIndex.computeFolderPath(
            file = file,
            vaultRootId = VAULT_ID,
            vaultRootName = VAULT_NAME,
            existingMap = emptyMap(),
            getMetadata = { id -> metadata[id] ?: error("unknown id: $id") },
        )
        assertEquals("BrainDump > A > B", path)
    }

    @Test
    fun `computeFolderPath - file under dot folder returns null`() = runTest {
        // Obsidian のゴミ箱 (.trash) 配下。Vault 内ではあるがインデックス対象外。
        val file = driveMd("trashed", "消したノート.md", parents = listOf("trash"))
        val metadata = mapOf(
            "trash" to DriveFile(
                id = "trash", name = ".trash",
                mimeType = DriveFile.MIME_FOLDER, parents = listOf(VAULT_ID),
            ),
        )
        val path = VaultIndex.computeFolderPath(
            file = file,
            vaultRootId = VAULT_ID,
            vaultRootName = VAULT_NAME,
            existingMap = emptyMap(),
            getMetadata = { id -> metadata[id] ?: error("unknown id: $id") },
        )
        assertNull(path)
    }

    @Test
    fun `computeFolderPath - dot folder deeper in the chain returns null`() = runTest {
        // 祖先のどこか 1階層でもドット始まりなら対象外。
        val file = driveMd("deep", "x.md", parents = listOf("sub"))
        val metadata = mapOf(
            "sub" to DriveFile(
                id = "sub", name = "普通のフォルダ",
                mimeType = DriveFile.MIME_FOLDER, parents = listOf("dot"),
            ),
            "dot" to DriveFile(
                id = "dot", name = ".trash",
                mimeType = DriveFile.MIME_FOLDER, parents = listOf(VAULT_ID),
            ),
        )
        val path = VaultIndex.computeFolderPath(
            file = file,
            vaultRootId = VAULT_ID,
            vaultRootName = VAULT_NAME,
            existingMap = emptyMap(),
            getMetadata = { id -> metadata[id] ?: error("unknown id: $id") },
        )
        assertNull(path)
    }

    @Test
    fun `computeFolderPath - dot named file returns null`() = runTest {
        val file = driveMd("hidden", ".draft.md", parents = listOf(VAULT_ID))
        val path = VaultIndex.computeFolderPath(
            file = file,
            vaultRootId = VAULT_ID,
            vaultRootName = VAULT_NAME,
            existingMap = emptyMap(),
            getMetadata = { error("should not be called") },
        )
        assertNull(path)
    }

    @Test
    fun `computeFolderPath - existing entry under dot folder returns null`() = runTest {
        // 旧バージョンのインデックスに入ってしまった .trash 配下のノート。
        // 既存エントリは親を辿り直さないので、保存済みパスから弾く必要がある。
        val existing = mapOf("f1" to mdEntity("f1", "x.md", "BrainDump > .trash"))
        val file = driveMd("f1", "x.md", parents = listOf("anywhere"))
        val path = VaultIndex.computeFolderPath(
            file = file,
            vaultRootId = VAULT_ID,
            vaultRootName = VAULT_NAME,
            existingMap = existing,
            getMetadata = { error("should not be called for existing") },
        )
        assertNull(path)
    }

    @Test
    fun `computeFolderPath - dot named vault root does not exclude everything`() = runTest {
        // Vault 自体がドット始まりの名前でも、その配下は対象のまま。
        // folderPath の先頭要素 (= Vault 名) を判定から外していることの確認。
        val existing = mapOf("f1" to mdEntity("f1", "x.md", ".MyVault > sub"))
        val file = driveMd("f1", "x.md", parents = listOf("anywhere"))
        val path = VaultIndex.computeFolderPath(
            file = file,
            vaultRootId = VAULT_ID,
            vaultRootName = ".MyVault",
            existingMap = existing,
            getMetadata = { error("should not be called for existing") },
        )
        assertEquals(".MyVault > sub", path)
    }

    @Test
    fun `computeFolderPath - new file outside vault returns null`() = runTest {
        val file = driveMd("new", "x.md", parents = listOf("outside"))
        val metadata = mapOf(
            "outside" to DriveFile(
                id = "outside", name = "Outside",
                mimeType = DriveFile.MIME_FOLDER, parents = emptyList(),
            ),
        )
        val path = VaultIndex.computeFolderPath(
            file = file,
            vaultRootId = VAULT_ID,
            vaultRootName = VAULT_NAME,
            existingMap = emptyMap(),
            getMetadata = { id -> metadata[id] ?: error("unknown id: $id") },
        )
        assertNull(path)
    }

    @Test
    fun `computeFolderPath - getMetadata throws returns null`() = runTest {
        val file = driveMd("new", "x.md", parents = listOf("unknown-parent"))
        val path = VaultIndex.computeFolderPath(
            file = file,
            vaultRootId = VAULT_ID,
            vaultRootName = VAULT_NAME,
            existingMap = emptyMap(),
            getMetadata = { throw RuntimeException("network down") },
        )
        assertNull(path)
    }

    @Test
    fun `computeFolderPath - empty parents returns null`() = runTest {
        val file = driveMd("new", "x.md", parents = emptyList())
        val path = VaultIndex.computeFolderPath(
            file = file,
            vaultRootId = VAULT_ID,
            vaultRootName = VAULT_NAME,
            existingMap = emptyMap(),
            getMetadata = { error("should not be called") },
        )
        assertNull(path)
    }

    // -----------------------------------------------------------------------------------
    // ヘルパー
    // -----------------------------------------------------------------------------------

    private fun mdEntity(
        id: String,
        name: String,
        folderPath: String,
        mimeType: String = "text/markdown",
    ) = MarkdownFileEntity(
        id = id, name = name, mimeType = mimeType, folderPath = folderPath,
        modifiedTime = null, size = null,
    )

    private fun driveMd(
        id: String,
        name: String,
        parents: List<String>,
        mimeType: String = "text/markdown",
        modifiedTime: String? = null,
        trashed: Boolean? = null,
    ) = DriveFile(
        id = id, name = name, mimeType = mimeType,
        parents = parents, modifiedTime = modifiedTime, trashed = trashed,
    )
}
