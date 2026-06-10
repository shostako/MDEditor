package com.shostakovich.mdeditor.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsTextNormalizerTest {

    private fun norm(s: String) = TtsTextNormalizer.normalize(s)

    @Test
    fun `見出し記号を除去して本文を残す`() {
        assertEquals("見出し\n本文", norm("# 見出し\n本文"))
        assertEquals("小見出し", norm("###### 小見出し"))
    }

    @Test
    fun `コードブロックは省略文言に置換`() {
        val result = norm("前\n```kotlin\nval x = 1\n```\n後")
        assertTrue(result.contains("コードブロック、省略。"))
        assertFalse(result.contains("val x"))
        assertTrue(result.contains("前"))
        assertTrue(result.contains("後"))
    }

    @Test
    fun `ブロック数式は数式という読みに置換`() {
        assertEquals("数式。", norm("$$\\int_0^1 x^2\\,dx$$"))
    }

    @Test
    fun `インライン数式は数式という読みに置換`() {
        assertEquals("粘度 数式 を計算", norm("粘度 \$\\eta\$ を計算"))
    }

    @Test
    fun `通貨表記は数式扱いしない`() {
        assertEquals("\$100 と \$200 を比較", norm("\$100 と \$200 を比較"))
    }

    @Test
    fun `インラインコードは中身だけ残す`() {
        assertEquals("実行は gradlew assembleRelease だ", norm("実行は `gradlew assembleRelease` だ"))
    }

    @Test
    fun `wikilinkはalias優先で読む`() {
        assertEquals("ピーダブリューシー", norm("[[entities/PwC|ピーダブリューシー]]"))
        assertEquals("ノート名", norm("[[ノート名]]"))
        assertEquals("c", norm("[[a/b/c]]"))
    }

    @Test
    fun `画像は読まない`() {
        val result = norm("前 ![[写真.png]] 後 ![代替](https://example.com/i.png) 末尾")
        assertFalse(result.contains("写真"))
        assertFalse(result.contains("代替"))
        assertTrue(result.contains("前"))
        assertTrue(result.contains("末尾"))
    }

    @Test
    fun `リンクはテキストだけ読む`() {
        assertEquals("詳細は Google を見ろ", norm("詳細は [Google](https://google.com) を見ろ"))
    }

    @Test
    fun `テーブルはセル中身を読点区切りで読む`() {
        val result = norm("| 項目 | 値 |\n|---|---|\n| 温度 | 220度 |")
        assertEquals("項目、値。\n温度、220度。", result)
    }

    @Test
    fun `引用記号はネストごと除去`() {
        assertEquals("引用文", norm("> > 引用文"))
        assertEquals("引用内見出し", norm("> # 引用内見出し"))
    }

    @Test
    fun `強調マーカーを除去`() {
        assertEquals("太字 と 取消 と 斜体", norm("**太字** と ~~取消~~ と *斜体*"))
    }

    @Test
    fun `リスト記号とチェックボックスを除去`() {
        assertEquals("項目1\n項目2\n完了タスク", norm("- 項目1\n1. 項目2\n- [x] 完了タスク"))
    }

    @Test
    fun `罫線行は削除`() {
        assertEquals("前\n後", norm("前\n---\n後"))
    }

    @Test
    fun `連続空行は1つに圧縮`() {
        assertEquals("あ\n\nい", norm("あ\n\n\n\nい"))
    }

    @Test
    fun `装飾なしテキストは素通し`() {
        val plain = "これは普通の文章だ。記号は何もない。"
        assertEquals(plain, norm(plain))
    }
}
