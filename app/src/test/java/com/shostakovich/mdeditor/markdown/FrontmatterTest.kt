package com.shostakovich.mdeditor.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrontmatterTest {

    @Test
    fun `split - simple frontmatter is separated`() {
        val md = """
            ---
            title: hello
            tags:
              - test
            ---
            # heading
            body text
        """.trimIndent()
        val split = Frontmatter.split(md)
        assertEquals("---\ntitle: hello\ntags:\n  - test\n---\n", split.frontmatter)
        assertEquals("# heading\nbody text", split.body)
    }

    @Test
    fun `split - no frontmatter returns markdown as body`() {
        val md = "# heading\nbody"
        val split = Frontmatter.split(md)
        assertNull(split.frontmatter)
        assertEquals(md, split.body)
    }

    @Test
    fun `split - empty string`() {
        val split = Frontmatter.split("")
        assertNull(split.frontmatter)
        assertEquals("", split.body)
    }

    @Test
    fun `split - frontmatter only (no body)`() {
        val md = "---\ntitle: only\n---\n"
        val split = Frontmatter.split(md)
        assertEquals("---\ntitle: only\n---\n", split.frontmatter)
        assertEquals("", split.body)
    }

    @Test
    fun `split - frontmatter only without trailing newline`() {
        val md = "---\ntitle: only\n---"
        val split = Frontmatter.split(md)
        assertEquals("---\ntitle: only\n---", split.frontmatter)
        assertEquals("", split.body)
    }

    @Test
    fun `split - incomplete frontmatter (no closing) treats as body`() {
        // 閉じる `---` が無いから frontmatter としては認識しない
        val md = "---\ntitle: oops\n# heading"
        val split = Frontmatter.split(md)
        assertNull(split.frontmatter)
        assertEquals(md, split.body)
    }

    @Test
    fun `split - opening delimiter not at start is not frontmatter`() {
        // 冒頭が空行で始まる → frontmatter 扱いしない (Obsidian も同様)
        val md = "\n---\ntitle: x\n---\n# heading"
        val split = Frontmatter.split(md)
        assertNull(split.frontmatter)
        assertEquals(md, split.body)
    }

    @Test
    fun `split - mid-document hr is not frontmatter`() {
        // 本文中の `---` は無視されるべき
        val md = """
            # heading
            para1

            ---

            para2
        """.trimIndent()
        val split = Frontmatter.split(md)
        assertNull(split.frontmatter)
        assertEquals(md, split.body)
    }

    @Test
    fun `split - trailing spaces on delimiter line allowed`() {
        // `--- ` (末尾スペース) も OK
        val md = "---   \ntitle: x\n---  \n# h"
        val split = Frontmatter.split(md)
        assertEquals("---   \ntitle: x\n---  \n", split.frontmatter)
        assertEquals("# h", split.body)
    }

    @Test
    fun `split - CRLF line endings`() {
        val md = "---\r\ntitle: x\r\n---\r\n# h"
        val split = Frontmatter.split(md)
        assertEquals("---\r\ntitle: x\r\n---\r\n", split.frontmatter)
        assertEquals("# h", split.body)
    }

    @Test
    fun `split - body roundtrip restores original`() {
        // 保存時の roundtrip 検証: split → (frontmatter ?? "") + body == 元の markdown
        val md = """
            ---
            title: hello
            ---
            # heading
            body text
        """.trimIndent() + "\n"
        val split = Frontmatter.split(md)
        val reconstructed = (split.frontmatter ?: "") + split.body
        assertEquals(md, reconstructed)
    }

    @Test
    fun `split - roundtrip without frontmatter restores original`() {
        val md = "# heading\nbody only\n"
        val split = Frontmatter.split(md)
        val reconstructed = (split.frontmatter ?: "") + split.body
        assertEquals(md, reconstructed)
    }

    @Test
    fun `stripBody - convenience returns body only`() {
        val md = "---\ntitle: x\n---\n# heading"
        assertEquals("# heading", Frontmatter.stripBody(md))
        assertEquals("plain text", Frontmatter.stripBody("plain text"))
    }
}
