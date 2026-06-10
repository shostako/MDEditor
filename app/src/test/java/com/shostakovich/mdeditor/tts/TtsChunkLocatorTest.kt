package com.shostakovich.mdeditor.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsChunkLocatorTest {

    private val chunks = listOf(
        "最初のチャンクだ。導入の説明がここに続く。",
        "二番目のチャンクで本題に入る。金型温度の管理が重要だ。",
        "三番目のチャンクで結論を述べる。以上をまとめると改善余地がある。",
    )

    @Test
    fun `チャンク内の文を渡すとそのindexを返す`() {
        assertEquals(1, TtsChunkLocator.findStartChunk(chunks, "金型温度の管理が重要だ"))
        assertEquals(2, TtsChunkLocator.findStartChunk(chunks, "三番目のチャンクで結論"))
    }

    @Test
    fun `空白や改行の差は無視して一致する`() {
        assertEquals(1, TtsChunkLocator.findStartChunk(chunks, "金型温度の\n管理が 重要だ"))
    }

    @Test
    fun `画像置換文字を含んでいても一致する`() {
        assertEquals(2, TtsChunkLocator.findStartChunk(chunks, "￼三番目のチャンクで結論"))
    }

    @Test
    fun `見つからなければ先頭の0を返す`() {
        assertEquals(0, TtsChunkLocator.findStartChunk(chunks, "存在しないテキスト片というやつ"))
    }

    @Test
    fun `空snippetや空チャンクは0を返す`() {
        assertEquals(0, TtsChunkLocator.findStartChunk(chunks, "   "))
        assertEquals(0, TtsChunkLocator.findStartChunk(emptyList(), "何か"))
    }

    @Test
    fun `長いsnippetは先頭プローブを短縮しながら探す`() {
        // 先頭24字はチャンク2の内容 + チャンク境界を跨いだゴミが続く想定。
        // 短縮プローブで2が見つかる
        val snippet = "三番目のチャンクで結論を述べる。ここから先は別資料の引用で本文に無い文章が続く"
        assertEquals(2, TtsChunkLocator.findStartChunk(chunks, snippet))
    }

    @Test
    fun `短いsnippetでも一意に含まれれば一致する`() {
        assertEquals(1, TtsChunkLocator.findStartChunk(chunks, "本題に"))
    }

    @Test
    fun `チャンク境界を跨ぐ選択は開始側のチャンクを返す`() {
        // チャンク1の末尾 + チャンク2の先頭にまたがる snippet。
        // 連結全文へのマッチなので境界跨ぎでも開始位置のチャンク (1) に当たる
        val snippet = "管理が重要だ。三番目のチャンクで結論を述べる"
        assertEquals(1, TtsChunkLocator.findStartChunk(chunks, snippet))
    }

    @Test
    fun `繰り返しフレーズは最長一致で曖昧性が解消される`() {
        val repeated = listOf(
            "序文で結論を先に述べる。それがこのノートの流儀だ。",
            "本文では詳細を扱う。",
            "最後にもう一度、結論を先に述べる。ただし今度は詳細付きだ。",
        )
        // 「結論を先に述べる」はチャンク0にも出現するが、続きの「ただし」まで
        // 一致するのはチャンク2だけ → 最長一致で2が選ばれる
        assertEquals(2, TtsChunkLocator.findStartChunk(repeated, "結論を先に述べる。ただし今度は"))
    }

    // ---- 見出しベースの特定 ----

    private val headedChunks = listOf(
        "導入\nこのノートの概要を説明する。",
        "測定方法\n金型温度をセンサーで測る。\n結果\n初回の結果は良好だった。",
        "考察\n温度分布に偏りがある。\n結果\n再測定の結果も同様だった。",
    )

    @Test
    fun `見出し行と一致するチャンクを返す`() {
        assertEquals(1, TtsChunkLocator.findChunkByHeading(headedChunks, "測定方法", 0))
        assertEquals(2, TtsChunkLocator.findChunkByHeading(headedChunks, "考察", 0))
    }

    @Test
    fun `同名見出しはoccurrenceで区別する`() {
        assertEquals(1, TtsChunkLocator.findChunkByHeading(headedChunks, "結果", 0))
        assertEquals(2, TtsChunkLocator.findChunkByHeading(headedChunks, "結果", 1))
    }

    @Test
    fun `同名見出しはsnippetが優先的に曖昧性を解消する`() {
        // occurrence が 0 (=最初の「結果」) でも、snippet が2つ目の「結果」の
        // 直後の本文なら snippet スコアが勝って 2 を返す
        assertEquals(
            2,
            TtsChunkLocator.findChunkByHeading(headedChunks, "結果", 0, snippet = "再測定の結果も同様だった"),
        )
        // snippet が1つ目の「結果」の本文なら 1
        assertEquals(
            1,
            TtsChunkLocator.findChunkByHeading(headedChunks, "結果", 1, snippet = "初回の結果は良好だった"),
        )
    }

    @Test
    fun `headingKeyは空白差を無視する`() {
        assertEquals(TtsChunkLocator.headingKey("A B"), TtsChunkLocator.headingKey("AB"))
        assertEquals(TtsChunkLocator.headingKey(" 測定 方法 "), TtsChunkLocator.headingKey("測定方法"))
    }

    @Test
    fun `見出しが本文の部分文字列に誤マッチしない`() {
        // 「結果は良好」等の本文行は「結果」と行一致しない
        assertEquals(null, TtsChunkLocator.findChunkByHeading(headedChunks, "存在しない見出し", 0))
    }

    @Test
    fun `hint指定は見出し優先でスニペットにフォールバックする`() {
        // 見出しが見つかる → 見出しのチャンク
        assertEquals(
            2,
            TtsChunkLocator.findStartChunk(
                headedChunks,
                TtsStartHint(heading = "考察", headingOccurrence = 0, snippet = "金型温度をセンサーで測る"),
            ),
        )
        // 見出しが無い (null) → スニペット一致
        assertEquals(
            1,
            TtsChunkLocator.findStartChunk(
                headedChunks,
                TtsStartHint(heading = null, headingOccurrence = 0, snippet = "金型温度をセンサーで測る"),
            ),
        )
        // 見出し不一致 → スニペットにフォールバック
        assertEquals(
            1,
            TtsChunkLocator.findStartChunk(
                headedChunks,
                TtsStartHint(heading = "無い見出し", headingOccurrence = 0, snippet = "金型温度をセンサーで測る"),
            ),
        )
    }

    @Test
    fun `実パイプラインで見出しから当てる`() {
        val md = buildString {
            repeat(20) { i ->
                append("## 工程$i\n")
                append("工程${i}の作業内容を記述する。特記事項は特にない。\n\n")
            }
        }
        val text = TtsTextNormalizer.normalize(md)
        val chunks = TtsChunker.chunk(text)
        val index = TtsChunkLocator.findChunkByHeading(chunks, "工程15", 0)
        assertTrue("工程15 の見出しが特定できるべき", index != null)
        assertTrue(chunks[index!!].contains("工程15"))
    }

    @Test
    fun `正規化からチャンク経由の実パイプラインで中盤位置を当てる`() {
        val md = buildString {
            repeat(40) { i ->
                append("## セクション$i\n")
                append("セクション${i}の説明文がここにある。テーマ${i}について **強調** を交えつつ長めに語る文章だ。\n\n")
            }
        }
        val text = TtsTextNormalizer.normalize(md)
        val chunks = TtsChunker.chunk(text)
        // 表示テキスト相当として、正規化済みテキストの中盤からの80字片を渡す
        val pos = text.indexOf("セクション30の説明文")
        val snippet = text.substring(pos, minOf(pos + 80, text.length))
        val index = TtsChunkLocator.findStartChunk(chunks, snippet)
        assertTrue("chunk[$index] にセクション30が含まれるべき", chunks[index].contains("セクション30の説明文"))
        assertTrue("中盤なので先頭チャンクではないはず", index > 0)
    }
}
