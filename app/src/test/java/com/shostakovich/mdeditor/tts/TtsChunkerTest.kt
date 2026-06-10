package com.shostakovich.mdeditor.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsChunkerTest {

    @Test
    fun `短い複数文は1チャンクに詰める`() {
        assertEquals(listOf("あ。\nい。"), TtsChunker.chunk("あ。い。", maxLength = 10))
    }

    @Test
    fun `maxLengthを超える組は文末で分割`() {
        assertEquals(
            listOf("あああ。", "いいい。"),
            TtsChunker.chunk("あああ。いいい。", maxLength = 5),
        )
    }

    @Test
    fun `改行も文境界として扱う`() {
        assertEquals(listOf("あ\nい"), TtsChunker.chunk("あ\nい", maxLength = 10))
    }

    @Test
    fun `長い単文は読点で再分割`() {
        assertEquals(
            listOf("あ、", "い、う"),
            TtsChunker.chunk("あ、い、う", maxLength = 3),
        )
    }

    @Test
    fun `文末も読点もない長文は機械切り`() {
        assertEquals(
            listOf("あいう", "えおか", "き"),
            TtsChunker.chunk("あいうえおかき", maxLength = 3),
        )
    }

    @Test
    fun `空入力と空白のみは空リスト`() {
        assertEquals(emptyList<String>(), TtsChunker.chunk(""))
        assertEquals(emptyList<String>(), TtsChunker.chunk("   \n  \n"))
    }

    @Test
    fun `全チャンクがmaxLength以下`() {
        val text = buildString {
            repeat(50) { append("これはそこそこ長い文というやつだ。") }
        }
        val chunks = TtsChunker.chunk(text)
        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.all { it.length <= TtsChunker.MAX_CHUNK_LENGTH })
    }
}
