package com.shostakovich.mdeditor.tts

/**
 * 読み上げ用に分割済みのチャンク列と、見出しが始まるチャンクの index を保持する。
 *
 * ## なぜ見出し index を持つか
 * 「画面で触った位置から読む」方式は、画面表示テキスト (Markwon レンダリング) と
 * 読み上げチャンク ([TtsTextNormalizer] → [TtsChunker]) が別経路の変換で
 * 文字オフセット対応が取れず、位置特定が原理的に外れた。そこで方式を変え、
 * 読み上げ側のチャンク列の中だけで「見出しチャンク」を前後に移動する
 * （メディアプレイヤーの前トラック/次トラックと同じ）。表示テキストを一切参照しないので
 * 外れようがない。その移動先テーブルが [headingChunks]。
 *
 * 純粋データ + 純粋ロジック（TtsContentTest）。
 */
data class TtsContent(
    val chunks: List<String>,
    /** 見出しで始まるチャンクの index（昇順・重複なし）。プリアンブルや見出しなしノートでは空 */
    val headingChunks: List<Int>,
) {
    /** [current] より後の最初の見出しチャンク。無ければ null（もう次が無い＝末尾セクション） */
    fun nextHeading(current: Int): Int? = headingChunks.firstOrNull { it > current }

    /**
     * メディアプレイヤーの「前トラック」相当:
     *  - セクション途中（[current] が直近見出しより後ろ）なら、そのセクション頭へ巻き戻す
     *  - 既にセクション頭にいるなら、一つ前のセクション頭へ
     *  - もう前が無ければ（先頭セクション頭 / プリアンブル内）null
     */
    fun prevHeading(current: Int): Int? {
        val sectionStart = headingChunks.lastOrNull { it <= current }
            ?: return null  // current は最初の見出しより前（プリアンブル内）→ 戻り先なし
        return if (current > sectionStart) sectionStart else headingChunks.lastOrNull { it < current }
    }

    companion object {
        val EMPTY = TtsContent(emptyList(), emptyList())
    }
}

/**
 * 生 Markdown を「見出しで始まるセクション」に区切り、セクション単位で
 * [TtsTextNormalizer] → [TtsChunker] にかけて [TtsContent] を組み立てる。
 *
 * セクション境界を必ずチャンク境界にすることで、見出しの開始位置が
 * チャンク index として正確に分かる。副次的に「節頭の直前に前節の末尾文が
 * 混入する」問題（チャンク詰めの都合）も解消される。
 */
object TtsContentBuilder {

    /** 行頭の ATX 見出し（`# ` 〜 `###### `）。`#見出し`（スペースなし）は CommonMark 上見出しでない */
    private val HEADING = Regex("""^#{1,6}\s""")

    /** フェンスドコードブロックの開閉（最大3スペースインデント許容）。トグルで内外を判定する */
    private val FENCE = Regex("""^\s{0,3}(```|~~~)""")

    fun build(markdown: String, maxChunkLength: Int = TtsChunker.MAX_CHUNK_LENGTH): TtsContent {
        val allChunks = ArrayList<String>()
        val headingChunks = ArrayList<Int>()
        for (section in splitSections(markdown)) {
            val text = TtsTextNormalizer.normalize(section)
            val chunks = TtsChunker.chunk(text, maxChunkLength)
            if (chunks.isEmpty()) continue
            if (startsWithHeading(section)) headingChunks.add(allChunks.size)
            allChunks.addAll(chunks)
        }
        return TtsContent(allChunks, headingChunks)
    }

    /**
     * 生 Markdown をコードフェンス外の見出し行で区切る。各見出し行が新セクションの先頭になり、
     * 最初の見出しより前は見出しなしのプリアンブルとして 1 つ目のセクションに入る。
     * フェンス内の `# コメント` 等は見出しとみなさない。
     */
    private fun splitSections(markdown: String): List<String> {
        val sections = ArrayList<String>()
        val current = StringBuilder()
        var inFence = false
        for (line in markdown.lineSequence()) {
            if (FENCE.containsMatchIn(line)) inFence = !inFence
            if (!inFence && HEADING.containsMatchIn(line) && current.isNotBlank()) {
                sections.add(current.toString())
                current.setLength(0)
            }
            current.append(line).append('\n')
        }
        if (current.isNotBlank()) sections.add(current.toString())
        return sections
    }

    /** セクションの最初の非空行が見出しか。プリアンブルだけ false、見出し起点のセクションは true */
    private fun startsWithHeading(section: String): Boolean {
        for (line in section.lineSequence()) {
            if (line.isBlank()) continue
            return HEADING.containsMatchIn(line)
        }
        return false
    }
}
