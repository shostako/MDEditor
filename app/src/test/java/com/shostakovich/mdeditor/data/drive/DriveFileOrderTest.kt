package com.shostakovich.mdeditor.data.drive

import org.junit.Assert.assertEquals
import org.junit.Test

class DriveFileOrderTest {

    private fun folder(name: String) = DriveFile(id = name, name = name, mimeType = DriveFile.MIME_FOLDER)
    private fun md(name: String) = DriveFile(id = name, name = name, mimeType = "text/markdown")
    private fun image(name: String) = DriveFile(id = name, name = name, mimeType = "image/png")
    private fun other(name: String) = DriveFile(id = name, name = name, mimeType = "application/pdf")

    @Test
    fun `フォルダ md 画像 その他 の順に並ぶ`() {
        val input = listOf(other("z.pdf"), image("a.png"), md("m.md"), folder("f"))
        val sorted = input.sortedWith(DRIVE_FILE_DISPLAY_ORDER).map { it.name }
        assertEquals(listOf("f", "m.md", "a.png", "z.pdf"), sorted)
    }

    @Test
    fun `同カテゴリ内は名前昇順 大文字小文字を無視`() {
        val input = listOf(md("Banana.md"), md("apple.md"), md("Cherry.md"))
        val sorted = input.sortedWith(DRIVE_FILE_DISPLAY_ORDER).map { it.name }
        assertEquals(listOf("apple.md", "Banana.md", "Cherry.md"), sorted)
    }

    @Test
    fun `md だけ抽出しても一覧と同じ相対順になる`() {
        // 兄弟ナビは sortedWith 後に isMarkdown で filter する。filter は順序を保つので
        // 一覧の md 部分と完全に同じ並びになることを担保する
        val input = listOf(folder("dir"), md("b.md"), image("x.png"), md("a.md"), other("c.txt"))
        val mdOrder = input.sortedWith(DRIVE_FILE_DISPLAY_ORDER).filter { it.isMarkdown }.map { it.name }
        assertEquals(listOf("a.md", "b.md"), mdOrder)
    }
}
