package com.shostakovich.mdeditor.tts

/**
 * 画面で選択されたテキスト片から、読み上げを開始すべきチャンクを特定する。
 *
 * ## なぜヒューリスティックか
 * 画面表示テキスト (Markwon レンダリング後) と読み上げチャンク
 * ([TtsTextNormalizer] → [TtsChunker]) は別経路の変換結果で、文字単位の
 * オフセット対応が取れない。ただしどちらも「Markdown 記法を剥がした本文」なので、
 * 散文部分の文字列はほぼ一致する。そこで空白・画像置換文字を除去した上での
 * 部分文字列マッチで該当位置を探す。
 *
 * ## マッチ戦略
 * 全チャンクを連結した凝縮テキストに対して、snippet の**最長一致プレフィックス**を
 * 二分探索で求め、その出現位置からチャンク index を逆引きする。
 *  - 連結全文に対して探すので、チャンク境界を跨ぐ選択でも正しい開始チャンクに当たる
 *    （チャンク単位の contains 判定だと境界跨ぎで全滅 → 短いプローブが手前の
 *    無関係チャンクに誤マッチする欠陥があった）
 *  - 最長一致なので、繰り返しフレーズがあっても snippet の続きの文字列で
 *    自然に曖昧性が解消される
 *  - snippet 先頭が数式・表など変換差異の大きい要素で化けている場合に備え、
 *    先頭を少しずつ捨てて再試行する
 * 特定できなければ 0（先頭から）。純粋関数（TtsChunkLocatorTest）。
 */
object TtsChunkLocator {

    /** これ未満の一致長は偶然の一致とみなす（日本語6字あれば実用上ほぼ一意） */
    private const val MIN_MATCH_LENGTH = 6

    /** snippet 先頭の捨て幅（先頭が数式・表の変換差異で化けている場合の再試行） */
    private val SNIPPET_SKIP_OFFSETS = intArrayOf(0, 8, 16)

    /** 候補スコアリングで snippet の続きを探す後方ウィンドウ幅 (凝縮文字数) */
    private const val SNIPPET_WINDOW = 1500

    /**
     * 見出しの同一性判定キー。
     * **表示側 (MarkdownText の occurrence 計数) と検索側で必ず同じ正規化を使うこと**。
     * 片側 trim・片側 condense のような不一致は occurrence のズレ → 誤ジャンプに直結する。
     */
    fun headingKey(s: String): String = condense(s)

    /**
     * [TtsStartHint] から開始チャンクを決める。
     * 見出し手がかりを優先し、ダメならスニペット一致、それでもダメなら 0。
     */
    fun findStartChunk(chunks: List<String>, hint: TtsStartHint): Int {
        hint.heading?.let { heading ->
            findChunkByHeading(chunks, heading, hint.headingOccurrence, hint.snippet)?.let { return it }
        }
        return findStartChunk(chunks, hint.snippet)
    }

    /**
     * 見出しテキストと**行単位で一致**するチャンクを探す。
     * 正規化テキストでは見出しは記号を剥がした素のテキストが1行として残り、
     * チャンク内では '\n' 区切りで保持されている。
     *
     * 同名候補が複数ある場合 (同名見出しの繰り返し、本文中の同一独立行) は:
     *  1. **snippet (選択位置からのテキスト片) が直後に続く候補**をスコアで選ぶ。
     *     選択位置は見出しの後ろにあるはずなので、これが最も信頼できる
     *  2. スコアで決まらなければ occurrence (表示側と同じ [headingKey] 基準) で選ぶ
     *
     * @param occurrence 同一テキストの見出しが複数ある場合に何個目を取るか (0-based)
     * @param snippet 選択位置からの表示テキスト片 (候補の曖昧性解消用)
     * @return チャンク index。見つからなければ null
     */
    fun findChunkByHeading(
        chunks: List<String>,
        heading: String,
        occurrence: Int,
        snippet: String = "",
    ): Int? {
        val target = condense(heading)
        if (target.isEmpty()) return null

        // 連結凝縮テキストを構築しつつ、見出しと行一致する候補 (チャンク index + 全文位置) を収集
        val candidateChunks = ArrayList<Int>()
        val candidatePositions = ArrayList<Int>()
        val full = buildString {
            chunks.forEachIndexed { index, chunk ->
                for (line in chunk.split('\n')) {
                    val condensedLine = condense(line)
                    if (condensedLine == target) {
                        candidateChunks.add(index)
                        candidatePositions.add(length)
                    }
                    append(condensedLine)
                }
            }
        }
        if (candidateChunks.isEmpty()) return null
        if (candidateChunks.size == 1) return candidateChunks[0]

        // 複数候補: snippet の先頭一致長を各候補の後方ウィンドウでスコアリング。
        // 同スコア (複数候補のウィンドウが snippet を含む) なら見出しからの距離が
        // 近い候補を選ぶ — 選択位置は読みたい見出しのすぐ後ろにあるはずだから
        val condensedSnippet = condense(snippet)
        if (condensedSnippet.length >= MIN_MATCH_LENGTH) {
            var bestIndex = -1
            var bestScore = 0
            var bestDistance = Int.MAX_VALUE
            for (i in candidateChunks.indices) {
                val pos = candidatePositions[i]
                val window = full.substring(pos, minOf(pos + SNIPPET_WINDOW, full.length))
                var score = 0
                var len = MIN_MATCH_LENGTH
                val maxLen = minOf(condensedSnippet.length, 24)
                while (len <= maxLen) {
                    if (window.contains(condensedSnippet.take(len))) score = len else break
                    len++
                }
                if (score == 0) continue
                val distance = window.indexOf(condensedSnippet.take(score))
                if (score > bestScore || (score == bestScore && distance < bestDistance)) {
                    bestScore = score
                    bestDistance = distance
                    bestIndex = i
                }
            }
            if (bestIndex >= 0) return candidateChunks[bestIndex]
        }
        // スコアで決まらなければ occurrence で選ぶ (範囲外なら最初の候補)
        return candidateChunks.getOrNull(occurrence) ?: candidateChunks[0]
    }

    /** snippet に対応するチャンク index を返す。特定できなければ 0 */
    fun findStartChunk(chunks: List<String>, snippet: String): Int {
        if (chunks.isEmpty()) return 0
        val condensedSnippet = condense(snippet)
        if (condensedSnippet.isEmpty()) return 0

        // 連結凝縮テキストと、各チャンクの開始オフセット
        val condensedChunks = chunks.map { condense(it) }
        val chunkStarts = IntArray(condensedChunks.size)
        val full = buildString {
            condensedChunks.forEachIndexed { i, c ->
                chunkStarts[i] = length
                append(c)
            }
        }
        if (full.isEmpty()) return 0

        for (skip in SNIPPET_SKIP_OFFSETS) {
            if (skip >= condensedSnippet.length) break
            val query = condensedSnippet.substring(skip)
            // 先頭捨て後の残りが短すぎる場合は弱い一致で前方に誤ジャンプするより諦める
            // (skip=0 の短い query は「選択そのものが短い」ケースなので全長一致を許す)
            if (skip > 0 && query.length < MIN_MATCH_LENGTH) break
            val pos = longestPrefixMatchPosition(full, query)
            if (pos >= 0) return chunkIndexAt(chunkStarts, pos)
        }
        return 0
    }

    /**
     * query のプレフィックスのうち full に出現する最長のものを求め、その出現位置を返す。
     * 一致長が [MIN_MATCH_LENGTH] 未満（query 自体が短い場合は query 全長未満）なら -1。
     * 「長さ L のプレフィックスが出現する」は L について単調減少なので二分探索できる。
     */
    private fun longestPrefixMatchPosition(full: String, query: String): Int {
        var lo = MIN_MATCH_LENGTH.coerceAtMost(query.length)
        if (!full.contains(query.take(lo))) return -1
        var hi = query.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (full.contains(query.take(mid))) lo = mid else hi = mid - 1
        }
        return full.indexOf(query.take(lo))
    }

    /** 連結テキスト上の位置 pos が属するチャンク index */
    private fun chunkIndexAt(chunkStarts: IntArray, pos: Int): Int {
        var index = 0
        for (i in chunkStarts.indices) {
            if (chunkStarts[i] <= pos) index = i else break
        }
        return index
    }

    /**
     * マッチ用に文字列を凝縮する:
     *  - 空白類を全除去（表示とチャンクで改行・スペースの入り方が違うため）
     *  - U+FFFC (Object Replacement Character) を除去（画像・数式 span の代替文字）
     */
    private fun condense(s: String): String = buildString(s.length) {
        for (c in s) {
            if (c.isWhitespace() || c == '￼') continue
            append(c)
        }
    }
}
