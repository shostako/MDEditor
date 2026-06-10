package com.shostakovich.mdeditor.tts

/**
 * Markdown 本文を音声読み上げ用のプレーンテキストへ正規化する。
 *
 * Markdown をそのまま TTS に渡すと「シャープシャープ見出し」「アスタリスク」など
 * 記号がそのまま読まれて聞くに堪えない。表示用の正規化 ([MathNormalizer]) とは
 * 目的が違うため意図的に別実装とする（あちらは Markwon 記法への変換、こちらは記号の除去）。
 *
 * ## 変換方針
 *  - フェンスドコードブロック → 「コードブロック、省略。」（コードの逐語読みは無意味）
 *  - 数式 `$$...$$` / `$...$` → 「数式」（LaTeX の逐語読みは無意味）
 *  - インラインコード `` `x` `` → 中身だけ残す（短い識別子は読んだ方が文意が通る）
 *  - 画像 → 除去 / wikilink → 表示名だけ / リンク → テキストだけ
 *  - 見出し・引用・リスト記号・強調マーカー・罫線 → 除去
 *  - テーブル → セル中身を読点区切りで読む（ノートの表は情報源なので捨てない）
 *
 * 純粋関数なのでユニットテスト可能（TtsTextNormalizerTest）。
 */
object TtsTextNormalizer {

    // ---- ブロック要素（MathNormalizer の Regex を流用。流用元: markdown/MathNormalizer.kt） ----

    // フェンスドコードブロック: ``` または ~~~ が3つ以上。開きと同種・同数 (\2) で閉じる。
    private val FENCED_CODE = Regex("""(?s)(^|\n)[ \t]*(`{3,}|~{3,})[^\n]*\n.*?\n[ \t]*\2[^\n]*""")

    // ブロック数式 $$...$$（改行可・最短）。
    private val BLOCK_MATH = Regex("""(?s)[$][$].+?[$][$]""")

    // インライン数式 $...$（通貨 `$100 〜 $200` やエスケープ `\$` は対象外）。
    private val INLINE_MATH =
        Regex("""(?<![\\$])[$](?![\s$])((?:[^$\n\\]|\\.)+?)(?<!\s)[$](?![$])""")

    // インラインコード: バッククォート1つで囲む（改行を跨がない）。
    private val INLINE_CODE = Regex("""`([^`\n]+)`""")

    // HTML コメント
    private val HTML_COMMENT = Regex("""(?s)<!--.*?-->""")

    // ---- インライン要素 ----

    // 画像: ![alt](url) と ![[wikilink]]
    private val IMAGE_MD = Regex("""!\[[^\]\n]*\]\([^)\n]*\)""")
    private val IMAGE_WIKILINK = Regex("""!\[\[[^\]\n]+?\]\]""")

    // wikilink: [[target|alias]] / [[target]]（WikilinkResolver と同じ中身パターン）
    private val WIKILINK = Regex("""\[\[([^\]\n]+?)\]\]""")

    // 通常リンク: [text](url)
    private val LINK_MD = Regex("""\[([^\]\n]*)\]\([^)\n]*\)""")

    // 強調マーカー（** __ ~~ == の対は単純除去。* の対は中身を残す）
    private val EMPHASIS_PAIR_MARKERS = Regex("""(\*\*|__|~~|==)""")
    private val SINGLE_ASTERISK_EM = Regex("""\*([^*\n]+)\*""")

    // ---- 行頭要素（行単位で処理） ----

    private val HEADING_PREFIX = Regex("""^#{1,6}\s+""")
    private val BLOCKQUOTE_PREFIX = Regex("""^(?:>\s?)+""")
    private val CHECKBOX_PREFIX = Regex("""^\s*[-*+]\s+\[[ xX]\]\s+""")
    private val LIST_PREFIX = Regex("""^\s*(?:[-*+]|\d+\.)\s+""")
    private val HORIZONTAL_RULE = Regex("""^\s*(?:-{3,}|_{3,}|\*{3,})\s*$""")
    private val TABLE_SEPARATOR_ROW = Regex("""^\s*\|?(?:\s*:?-+:?\s*\|)+\s*:?-*:?\s*\|?\s*$""")
    private val TABLE_ROW = Regex("""^\s*\|.*\|\s*$""")

    // 3行以上の連続改行 → 2行（空行の読み上げ無音を防ぐ）
    private val BLANK_LINES = Regex("""\n{3,}""")

    /** Markdown 本文 → 読み上げ用プレーンテキスト */
    fun normalize(markdown: String): String {
        var work = markdown

        // 1. ブロック要素（行構造が壊れる前に処理する）
        work = HTML_COMMENT.replace(work, "")
        work = FENCED_CODE.replace(work, "\nコードブロック、省略。")
        work = BLOCK_MATH.replace(work, "数式。")
        work = INLINE_MATH.replace(work, "数式")
        work = INLINE_CODE.replace(work) { m -> m.groupValues[1] }

        // 2. リンク・画像（wikilink 画像 → 通常画像 → wikilink → 通常リンクの順。
        //    ![[...]] を先に消さないと [[...]] が誤マッチする）
        work = IMAGE_WIKILINK.replace(work, "")
        work = IMAGE_MD.replace(work, "")
        work = WIKILINK.replace(work) { m -> wikilinkDisplayName(m.groupValues[1]) }
        work = LINK_MD.replace(work) { m -> m.groupValues[1] }

        // 3. 行単位の処理
        work = work.lines().mapNotNull { line -> normalizeLine(line) }.joinToString("\n")

        // 4. インライン強調マーカー除去
        work = EMPHASIS_PAIR_MARKERS.replace(work, "")
        work = SINGLE_ASTERISK_EM.replace(work) { m -> m.groupValues[1] }

        // 5. 空行圧縮
        work = BLANK_LINES.replace(work, "\n\n")
        return work.trim()
    }

    /** wikilink の中身 → 読み上げる表示名（alias 優先、なければパス末尾） */
    private fun wikilinkDisplayName(inner: String): String {
        val alias = inner.substringAfter('|', missingDelimiterValue = "")
        if (alias.isNotBlank()) return alias.trim()
        return inner.substringBefore('|').substringAfterLast('/').trim()
    }

    /** 1行を正規化。null を返すと行ごと削除 */
    private fun normalizeLine(line: String): String? {
        if (HORIZONTAL_RULE.matches(line)) return null
        if (TABLE_SEPARATOR_ROW.matches(line)) return null
        if (TABLE_ROW.matches(line)) {
            // |a|b|c| → 「a、b、c。」セル中身は情報源なので読む
            val cells = line.trim().trim('|').split('|').map { it.trim() }.filter { it.isNotEmpty() }
            if (cells.isEmpty()) return null
            return cells.joinToString("、") + "。"
        }
        // 引用 → チェックボックス → 見出し → リスト の順（`> # 見出し` `> - 項目` 対応）
        var l = line
        l = BLOCKQUOTE_PREFIX.replace(l, "")
        l = CHECKBOX_PREFIX.replace(l, "")
        l = HEADING_PREFIX.replace(l, "")
        l = LIST_PREFIX.replace(l, "")
        return l
    }
}
