package com.shostakovich.mdeditor.tts

/**
 * 読み上げテキストを TTS に渡すチャンクへ分割する。
 *
 * TextToSpeech.speak の上限は約4000字だが、チャンクは意図的に小さくする:
 *  - 疑似 pause（tts.stop → 現在チャンクから再開）の巻き戻り幅がチャンクサイズで決まる
 *  - 進捗表示（チャンク i / n）の粒度
 *
 * 分割は文末（。．！？!? と改行）優先。1文が上限を超える場合は読点で再分割し、
 * それでも超えるなら機械的に切る。純粋関数（TtsChunkerTest）。
 */
object TtsChunker {

    const val MAX_CHUNK_LENGTH = 500

    private val SENTENCE_END = charArrayOf('。', '．', '！', '？', '!', '?', '\n')

    /** 文末優先で maxLength 以下のチャンク列に分割する。空白のみのチャンクは出さない */
    fun chunk(text: String, maxLength: Int = MAX_CHUNK_LENGTH): List<String> {
        require(maxLength > 0) { "maxLength must be positive: $maxLength" }
        if (text.isBlank()) return emptyList()

        val sentences = splitSentences(text)
        val chunks = ArrayList<String>()
        val buffer = StringBuilder()

        fun flush() {
            val s = buffer.toString().trim()
            if (s.isNotEmpty()) chunks.add(s)
            buffer.setLength(0)
        }

        for (sentence in sentences) {
            if (sentence.length > maxLength) {
                // 長すぎる単文: いったん溜まりを吐いてから細分化
                flush()
                chunks.addAll(splitLongSentence(sentence, maxLength))
                continue
            }
            if (buffer.length + sentence.length + 1 > maxLength) flush()
            if (buffer.isNotEmpty()) buffer.append('\n')
            buffer.append(sentence)
        }
        flush()
        return chunks
    }

    /** 文末文字の直後で分割。文末文字は文に含める（改行は含めない）。空文は捨てる */
    private fun splitSentences(text: String): List<String> {
        val result = ArrayList<String>()
        var start = 0
        for (i in text.indices) {
            if (text[i] in SENTENCE_END) {
                val end = if (text[i] == '\n') i else i + 1
                val s = text.substring(start, end).trim()
                if (s.isNotEmpty()) result.add(s)
                start = i + 1
            }
        }
        val tail = text.substring(start).trim()
        if (tail.isNotEmpty()) result.add(tail)
        return result
    }

    /** maxLength 超の単文を読点優先で分割し、それでも超える断片は機械切り */
    private fun splitLongSentence(sentence: String, maxLength: Int): List<String> {
        val result = ArrayList<String>()
        val buffer = StringBuilder()

        fun flush() {
            val s = buffer.toString().trim()
            if (s.isNotEmpty()) result.add(s)
            buffer.setLength(0)
        }

        // 読点（、,）の直後で切った断片を詰め直す
        val parts = ArrayList<String>()
        var start = 0
        for (i in sentence.indices) {
            if (sentence[i] == '、' || sentence[i] == ',') {
                parts.add(sentence.substring(start, i + 1))
                start = i + 1
            }
        }
        if (start < sentence.length) parts.add(sentence.substring(start))

        for (part in parts) {
            if (part.length > maxLength) {
                // 読点でも収まらない: 機械切り
                flush()
                var p = 0
                while (p < part.length) {
                    val end = minOf(p + maxLength, part.length)
                    result.add(part.substring(p, end))
                    p = end
                }
                continue
            }
            if (buffer.length + part.length > maxLength) flush()
            buffer.append(part)
        }
        flush()
        return result
    }
}
