package com.shostakovich.mdeditor.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsContentBuilderTest {

    // ---- TtsContentBuilder.build ----

    @Test
    fun `見出しごとにチャンクが切れて headingChunks に記録される`() {
        val md = "# 章1\n本文1。\n## 節1\n本文2。\n# 章2\n本文3。\n"
        val content = TtsContentBuilder.build(md)
        assertEquals(3, content.headingChunks.size)
        assertTrue(content.chunks[content.headingChunks[0]].contains("章1"))
        assertTrue(content.chunks[content.headingChunks[1]].contains("節1"))
        assertTrue(content.chunks[content.headingChunks[2]].contains("章2"))
    }

    @Test
    fun `headingChunks は昇順`() {
        val md = "# A\n本文。\n# B\n本文。\n# C\n本文。\n"
        val content = TtsContentBuilder.build(md)
        assertEquals(content.headingChunks.sorted(), content.headingChunks)
    }

    @Test
    fun `コードフェンス内の見出し記号はセクションを切らない`() {
        val md = "# 本物の見出し\n説明文。\n```\n# これはコメント\nprint(x)\n```\n続きの文。\n"
        val content = TtsContentBuilder.build(md)
        // 見出しは「本物の見出し」1つだけ。フェンス内の `# これはコメント` は見出しでない
        assertEquals(1, content.headingChunks.size)
        assertTrue(content.chunks[content.headingChunks[0]].contains("本物の見出し"))
    }

    @Test
    fun `チルダフェンス内の見出し記号もセクションを切らない`() {
        val md = "# 見出し\n本文。\n~~~\n# コメント\ncode\n~~~\nおわり。\n"
        val content = TtsContentBuilder.build(md)
        assertEquals(1, content.headingChunks.size)
    }

    @Test
    fun `見出し前のプリアンブルは headingChunks に含めない`() {
        val md = "前書きの文章がここにある。\n# 最初の見出し\n本文。\n"
        val content = TtsContentBuilder.build(md)
        // プリアンブル (chunk 0) は見出しでないので headingChunks に入らない
        assertEquals(1, content.headingChunks.size)
        assertTrue("見出しチャンクはプリアンブルより後ろ", content.headingChunks[0] > 0)
        assertTrue(content.chunks[content.headingChunks[0]].contains("最初の見出し"))
    }

    @Test
    fun `見出しのないノートは headingChunks が空`() {
        val md = "ただの段落。\nもう一段落あるだけ。\n"
        val content = TtsContentBuilder.build(md)
        assertTrue(content.chunks.isNotEmpty())
        assertTrue(content.headingChunks.isEmpty())
    }

    @Test
    fun `空ノートは空の TtsContent`() {
        val content = TtsContentBuilder.build("   \n\n")
        assertTrue(content.chunks.isEmpty())
        assertTrue(content.headingChunks.isEmpty())
    }

    @Test
    fun `実パイプラインで見出し数と headingChunks 数が一致する`() {
        val md = buildString {
            repeat(10) { i ->
                append("## セクション$i\n")
                append("セクション${i}の本文がここにある。特記事項はない。\n\n")
            }
        }
        val content = TtsContentBuilder.build(md)
        assertEquals(10, content.headingChunks.size)
        content.headingChunks.forEachIndexed { i, idx ->
            assertTrue("chunk[$idx] にセクション$i が含まれるべき", content.chunks[idx].contains("セクション$i"))
        }
    }

    // ---- TtsContent.nextHeading / prevHeading（純粋ロジック） ----

    private fun content(headingChunks: List<Int>, size: Int = 10) =
        TtsContent(chunks = List(size) { "" }, headingChunks = headingChunks)

    @Test
    fun `nextHeading は現在位置より後の最初の見出しを返す`() {
        val c = content(listOf(0, 3, 7))
        assertEquals(3, c.nextHeading(0))
        assertEquals(3, c.nextHeading(2))
        assertEquals(7, c.nextHeading(3))
        assertEquals(7, c.nextHeading(6))
        assertNull(c.nextHeading(7))
        assertNull(c.nextHeading(9))
    }

    @Test
    fun `prevHeading はセクション途中なら頭出し 頭なら前セクション`() {
        val c = content(listOf(0, 3, 7))
        assertEquals(3, c.prevHeading(5))    // セクション途中 → 頭出し
        assertEquals(3, c.prevHeading(4))    // セクション途中 → 頭出し
        assertEquals(0, c.prevHeading(3))    // ちょうど頭 → 前セクション頭
        assertEquals(7, c.prevHeading(8))    // セクション途中 → 頭出し
        assertNull(c.prevHeading(0))         // 先頭セクションの頭 → 戻り先なし
    }

    @Test
    fun `prevHeading はプリアンブル内なら戻り先なし`() {
        val c = content(listOf(2, 5))
        assertNull(c.prevHeading(1))         // 最初の見出しより前（プリアンブル内）
        assertEquals(2, c.prevHeading(3))    // セクション途中 → 頭出し
        assertEquals(2, c.prevHeading(5))    // ちょうど頭 → 前セクション頭(=2)
        assertNull(c.prevHeading(2))         // 最初の見出しの頭 → 戻り先なし
    }

    @Test
    fun `見出しが無ければ next prev とも null`() {
        val c = content(emptyList())
        assertNull(c.nextHeading(0))
        assertNull(c.prevHeading(3))
    }
}
