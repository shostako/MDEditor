package com.shostakovich.mdeditor.markdown

import com.shostakovich.mdeditor.data.drive.DriveFile
import com.shostakovich.mdeditor.data.vault.VaultIndex
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * WikilinkResolver の **ノートリンク** (`[[note]]`) 解決ロジックのユニットテスト。
 *
 * 画像 (`![[image.ext]]`) 側は Drive 通信 (listChildren) を含むので統合テスト相当に任せ、
 * ここでは純粋関数化された `resolveNotesPure` だけ検証する。
 */
class WikilinkResolverTest {

    private val NOTE_SCHEME = WikilinkResolver.NOTE_SCHEME // "mdeditor-note"

    @Test
    fun `resolveNotesPure - empty index returns markdown unchanged`() {
        val md = "[[anything]]"
        val out = WikilinkResolver.resolveNotesPure(
            markdown = md,
            allFiles = emptyList(),
            currentFolderPath = null,
        )
        assertEquals(md, out) // 未解決でも空インデックスなら触らない (素通り)
    }

    @Test
    fun `resolveNotesPure - simple match resolves to note link`() {
        val md = "see [[STEP3]]"
        val out = WikilinkResolver.resolveNotesPure(
            markdown = md,
            allFiles = listOf(indexed("id-step3", "STEP3.md", "BrainDump > 極薄プレート")),
            currentFolderPath = null,
        )
        assertEquals("see [STEP3]($NOTE_SCHEME://id-step3)", out)
    }

    @Test
    fun `resolveNotesPure - filename with extension also resolves`() {
        // [[note.md]] も同じ note と見なす
        val md = "[[STEP3.md]]"
        val out = WikilinkResolver.resolveNotesPure(
            markdown = md,
            allFiles = listOf(indexed("id-step3", "STEP3.md", "BrainDump")),
            currentFolderPath = null,
        )
        assertEquals("[STEP3.md]($NOTE_SCHEME://id-step3)", out)
    }

    @Test
    fun `resolveNotesPure - alias overrides display text`() {
        val md = "click [[STEP3|前回の試作レポート]]"
        val out = WikilinkResolver.resolveNotesPure(
            markdown = md,
            allFiles = listOf(indexed("id-step3", "STEP3.md", "BrainDump")),
            currentFolderPath = null,
        )
        assertEquals("click [前回の試作レポート]($NOTE_SCHEME://id-step3)", out)
    }

    @Test
    fun `resolveNotesPure - section suffix is stripped for resolution but kept invisible`() {
        // `[[note#見出し]]` → note 部分だけで解決、表示テキストは元のまま
        val md = "[[STEP3#結果]]"
        val out = WikilinkResolver.resolveNotesPure(
            markdown = md,
            allFiles = listOf(indexed("id-step3", "STEP3.md", "BrainDump")),
            currentFolderPath = null,
        )
        // 表示テキストは raw の `STEP3#結果`、リンク先は note 部分の id
        assertEquals("[STEP3#結果]($NOTE_SCHEME://id-step3)", out)
    }

    @Test
    fun `resolveNotesPure - block ref suffix is stripped`() {
        val md = "[[STEP3^abc123]]"
        val out = WikilinkResolver.resolveNotesPure(
            markdown = md,
            allFiles = listOf(indexed("id-step3", "STEP3.md", "BrainDump")),
            currentFolderPath = null,
        )
        assertEquals("[STEP3^abc123]($NOTE_SCHEME://id-step3)", out)
    }

    @Test
    fun `resolveNotesPure - section plus alias`() {
        val md = "[[STEP3#結果|結果セクションへ]]"
        val out = WikilinkResolver.resolveNotesPure(
            markdown = md,
            allFiles = listOf(indexed("id-step3", "STEP3.md", "BrainDump")),
            currentFolderPath = null,
        )
        assertEquals("[結果セクションへ]($NOTE_SCHEME://id-step3)", out)
    }

    @Test
    fun `resolveNotesPure - unresolved note is left as text`() {
        val md = "before [[GhostNote]] after"
        val out = WikilinkResolver.resolveNotesPure(
            markdown = md,
            allFiles = listOf(indexed("id-step3", "STEP3.md", "BrainDump")),
            currentFolderPath = null,
        )
        // 該当ノートが Vault に無いなら元の [[GhostNote]] のまま
        assertEquals(md, out)
    }

    @Test
    fun `resolveNotesPure - same name in multiple folders prefers currentFolderPath`() {
        val md = "[[memo]]"
        val files = listOf(
            indexed("id-a", "memo.md", "BrainDump > A"),
            indexed("id-b", "memo.md", "BrainDump > B"),
            indexed("id-c", "memo.md", "BrainDump > C"),
        )
        // 自分が "BrainDump > B" にいるなら id-b が選ばれるはず
        val out = WikilinkResolver.resolveNotesPure(
            markdown = md,
            allFiles = files,
            currentFolderPath = "BrainDump > B",
        )
        assertEquals("[memo]($NOTE_SCHEME://id-b)", out)
    }

    @Test
    fun `resolveNotesPure - same name multiple no current folder picks first`() {
        val md = "[[memo]]"
        val files = listOf(
            indexed("id-a", "memo.md", "BrainDump > A"),
            indexed("id-b", "memo.md", "BrainDump > B"),
        )
        val out = WikilinkResolver.resolveNotesPure(
            markdown = md,
            allFiles = files,
            currentFolderPath = null,
        )
        // 同一フォルダなしなら最初の候補 (リスト順) を採用
        assertEquals("[memo]($NOTE_SCHEME://id-a)", out)
    }

    @Test
    fun `resolveNotesPure - same name current folder not in candidates picks first`() {
        val md = "[[memo]]"
        val files = listOf(
            indexed("id-a", "memo.md", "BrainDump > A"),
            indexed("id-b", "memo.md", "BrainDump > B"),
        )
        // 自分の folderPath "BrainDump > Z" は候補にない → 最初の候補
        val out = WikilinkResolver.resolveNotesPure(
            markdown = md,
            allFiles = files,
            currentFolderPath = "BrainDump > Z",
        )
        assertEquals("[memo]($NOTE_SCHEME://id-a)", out)
    }

    @Test
    fun `resolveNotesPure - empty link is left as text`() {
        val md = "[[]]"
        val out = WikilinkResolver.resolveNotesPure(
            markdown = md,
            allFiles = listOf(indexed("id1", "x.md", "BrainDump")),
            currentFolderPath = null,
        )
        assertEquals(md, out)
    }

    @Test
    fun `resolveNotesPure - only section is left as text`() {
        // [[#見出し]] のように本文部分が空はファイル特定不能 → 触らない
        val md = "[[#結果]]"
        val out = WikilinkResolver.resolveNotesPure(
            markdown = md,
            allFiles = listOf(indexed("id1", "STEP3.md", "BrainDump")),
            currentFolderPath = null,
        )
        assertEquals(md, out)
    }

    @Test
    fun `resolveNotesPure - multiple wikilinks in same markdown`() {
        val md = """
            See [[STEP1]] and [[STEP2|前回]] and [[Ghost]].
        """.trimIndent()
        val files = listOf(
            indexed("id-1", "STEP1.md", "BrainDump"),
            indexed("id-2", "STEP2.md", "BrainDump"),
        )
        val out = WikilinkResolver.resolveNotesPure(
            markdown = md,
            allFiles = files,
            currentFolderPath = null,
        )
        val expected = """
            See [STEP1]($NOTE_SCHEME://id-1) and [前回]($NOTE_SCHEME://id-2) and [[Ghost]].
        """.trimIndent()
        assertEquals(expected, out)
    }

    @Test
    fun `resolveNotesPure - backslash in display text is escaped`() {
        // 表示テキストに `\` が混じったら Markwon のリンク構文を壊さないよう `\\` にする。
        // (`]` は regex で raw から除外済みなのでエスケープ不要)
        val md = """[[STEP3|path\to\thing]]"""
        val out = WikilinkResolver.resolveNotesPure(
            markdown = md,
            allFiles = listOf(indexed("id-3", "STEP3.md", "BrainDump")),
            currentFolderPath = null,
        )
        // 1個の `\` が `\\` に置換される。Kotlin リテラルでは `\\\\` が実文字列 `\\` の意味。
        assertEquals("""[path\\to\\thing]($NOTE_SCHEME://id-3)""", out)
    }

    @Test
    fun `resolveNotesPure - already converted image link is not touched`() {
        // 画像 wikilink は事前に `![](mdeditor-drive://...)` 形式に変換済みの想定。
        // その後の resolveNotesPure 通過時に、その中の `[]` が誤マッチしないこと。
        val md = "![名前](mdeditor-drive://abc)\n本文 [[STEP1]]"
        val files = listOf(indexed("id-1", "STEP1.md", "BrainDump"))
        val out = WikilinkResolver.resolveNotesPure(
            markdown = md,
            allFiles = files,
            currentFolderPath = null,
        )
        val expected = "![名前](mdeditor-drive://abc)\n本文 [STEP1]($NOTE_SCHEME://id-1)"
        assertEquals(expected, out)
    }

    @Test
    fun `resolveNotesPure - path prefix selects matching folder candidate`() {
        // Obsidian の Vault 相対パス指定 `[[entities/PwC|PwC]]` を正しく解決:
        //  - basename `PwC` で候補を集める
        //  - pathHint `entities` が folderPath の末尾セグメントと一致する候補を採用
        val md = "see [[entities/PwC|PwC]]"
        val files = listOf(
            // 別フォルダの同名ファイル (誤って選ばれないこと)
            indexed("id-other", "PwC.md", "BrainDump > Random"),
            // 真の候補
            indexed("id-pwc-entity", "PwC.md", "BrainDump > Knowledge > entities"),
        )
        val out = WikilinkResolver.resolveNotesPure(
            markdown = md,
            allFiles = files,
            currentFolderPath = null,
        )
        assertEquals("see [PwC]($NOTE_SCHEME://id-pwc-entity)", out)
    }

    @Test
    fun `resolveNotesPure - multi-segment path hint`() {
        // `[[Knowledge/entities/PwC]]` で 2 セグメント末尾一致
        val md = "[[Knowledge/entities/PwC]]"
        val files = listOf(
            indexed("id-shallow", "PwC.md", "BrainDump > entities"),                // 末尾1セグメントは一致するが
            indexed("id-deep", "PwC.md", "BrainDump > Knowledge > entities"),       // 末尾2セグメントが一致 ← こちらを優先
        )
        val out = WikilinkResolver.resolveNotesPure(
            markdown = md,
            allFiles = files,
            currentFolderPath = null,
        )
        // 「末尾セグメントから順にチェック」する単純実装なので、最初に hit したのが採用される。
        // 候補リスト順が逆 (deep が後) でも multi-segment が一致するなら deep を優先する想定
        assertEquals("[Knowledge/entities/PwC]($NOTE_SCHEME://id-deep)", out)
    }

    @Test
    fun `resolveNotesPure - path with extension is also accepted`() {
        // `[[entities/PwC.md|PwC]]` 拡張子付き
        val md = "[[entities/PwC.md|PwC]]"
        val files = listOf(
            indexed("id-pwc", "PwC.md", "BrainDump > entities"),
        )
        val out = WikilinkResolver.resolveNotesPure(
            markdown = md,
            allFiles = files,
            currentFolderPath = null,
        )
        assertEquals("[PwC]($NOTE_SCHEME://id-pwc)", out)
    }

    @Test
    fun `resolveNotesPure - case sensitivity follows exact match`() {
        // Drive のファイル名は case-sensitive。"step3" と "STEP3" は別物として扱う。
        val md = "[[step3]]"
        val out = WikilinkResolver.resolveNotesPure(
            markdown = md,
            allFiles = listOf(indexed("id-3", "STEP3.md", "BrainDump")),
            currentFolderPath = null,
        )
        // 大文字小文字違いは未解決
        assertEquals(md, out)
    }

    // ヘルパー
    private fun indexed(
        id: String,
        name: String,
        folderPath: String,
    ) = VaultIndex.IndexedFile(
        file = DriveFile(id = id, name = name, mimeType = "text/markdown"),
        folderPath = folderPath,
    )
}
