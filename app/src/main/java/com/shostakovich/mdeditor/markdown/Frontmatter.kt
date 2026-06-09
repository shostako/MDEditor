package com.shostakovich.mdeditor.markdown

/**
 * Markdown 冒頭の YAML frontmatter (`---\n...\n---\n`) を分離するユーティリティ。
 *
 * Obsidian や Hugo, Jekyll などで広く使われる形式。MDEditor では Preview/Edit 共に
 * 本文だけ表示し、ユーザは frontmatter を意識せず編集できるようにする。
 * 保存時に呼び出し側が `frontmatter + editedBody` で再結合してから Drive に書き戻す
 * 必要がある (さもないと frontmatter が消える保存事故になる)。
 *
 * 仕様:
 *  - **冒頭の `---` のみ反応** する (本文中の `---` 区切りには反応しない)
 *  - 開始 `---` と終了 `---` の **両方が必要**。閉じが無ければ frontmatter として認識しない
 *  - 行末の余分なスペース、CR/LF 改行混在を許容
 *  - frontmatter 部分には開始・終了 delimiter とその直後の改行まで含める
 *    → これにより `body` 側は frontmatter の余韻 (delimiter 直後の改行) を含まない
 */
object Frontmatter {

    /**
     * - `^---` 冒頭の `---`
     * - `[ \t]*\r?\n` 行末空白を許容して改行
     * - `(.*?)` 任意 (非貪欲)
     * - `\r?\n---[ \t]*(?:\r?\n|$)` 改行 + `---` + 行末空白 + 改行 or 文字列末尾
     * - DOT_MATCHES_ALL で `.` が改行にもマッチ
     */
    private val FRONTMATTER_REGEX = Regex(
        """^---[ \t]*\r?\n(.*?)\r?\n---[ \t]*(?:\r?\n|$)""",
        RegexOption.DOT_MATCHES_ALL,
    )

    /** 分離結果。frontmatter が無ければ `frontmatter = null`、`body = markdown` */
    data class Split(
        /** `---\n...\n---\n` 全体 (delimiter 込み、末尾改行も含む)。無ければ null */
        val frontmatter: String?,
        /** frontmatter を取り除いた残り。frontmatter 無しなら markdown 原本 */
        val body: String,
    )

    fun split(markdown: String): Split {
        val match = FRONTMATTER_REGEX.find(markdown) ?: return Split(null, markdown)
        // ^ アンカーが付いてるので match.range.first は 0 のはずだが念のため
        if (match.range.first != 0) return Split(null, markdown)
        val endExclusive = match.range.last + 1
        val frontmatterText = markdown.substring(0, endExclusive)
        val body = markdown.substring(endExclusive)
        return Split(frontmatterText, body)
    }

    /** body だけ取り出す簡易版 (frontmatter 不要なケース用) */
    fun stripBody(markdown: String): String = split(markdown).body

    /**
     * frontmatter 内の 1 プロパティ。
     * 単一値は values が 1 要素、リスト値 (`tags:` + `- x` や `[a, b]`) は複数要素。
     * 値なしキー (`key:` のみ) は values が空リスト。
     */
    data class Property(
        val key: String,
        val values: List<String>,
    )

    /**
     * frontmatter 文字列 (delimiter 込み/無しどちらでも可) を key-value のリストにパースする。
     *
     * Obsidian のプロパティは実質「フラットな key: value + リスト」なので、
     * 外部 YAML ライブラリは使わず行ベースの簡易パーサで賄う。対応するのは:
     *  - `key: value` 単一値 (クォートは剥がす)
     *  - `key:` の次行以降の `- item` 連続 → リスト値
     *  - `key: [a, b, c]` インラインリスト
     *  - `key:` 単独 (値なし) → 空リスト
     * 非対応 (そのまま生テキストとして単一値扱い):
     *  - ネストしたマップ、複数行文字列 (`|` / `>`)
     * パース失敗しても例外は投げず、読めた分だけ返す。
     */
    fun parseProperties(frontmatter: String): List<Property> {
        val lines = frontmatter
            .lines()
            .filterNot { it.trim() == "---" }

        val result = mutableListOf<Property>()
        var pendingKey: String? = null
        val pendingValues = mutableListOf<String>()

        fun flushPending() {
            val key = pendingKey ?: return
            result.add(Property(key, pendingValues.toList()))
            pendingKey = null
            pendingValues.clear()
        }

        for (rawLine in lines) {
            val line = rawLine.trimEnd()
            if (line.isBlank()) continue
            val trimmed = line.trimStart()
            if (trimmed.startsWith("#")) continue // YAML コメント

            // リスト項目 (`- item`)。直前に key 行があればその値として積む
            if (trimmed.startsWith("- ") || trimmed == "-") {
                if (pendingKey != null) {
                    val item = trimmed.removePrefix("-").trim().unquote()
                    if (item.isNotEmpty()) pendingValues.add(item)
                }
                continue
            }

            // `key: value` 行。コロンが無い行はネスト等の非対応構文なのでスキップ
            val colonIndex = trimmed.indexOf(':')
            if (colonIndex <= 0) continue

            flushPending()
            val key = trimmed.substring(0, colonIndex).trim().unquote()
            val rawValue = trimmed.substring(colonIndex + 1).trim()

            when {
                rawValue.isEmpty() -> {
                    // 値なし → 次行からのリスト項目を待つ
                    pendingKey = key
                }
                rawValue.startsWith("[") && rawValue.endsWith("]") -> {
                    // インラインリスト `[a, b, c]`
                    val items = rawValue
                        .substring(1, rawValue.length - 1)
                        .split(',')
                        .map { it.trim().unquote() }
                        .filter { it.isNotEmpty() }
                    result.add(Property(key, items))
                }
                else -> {
                    result.add(Property(key, listOf(rawValue.unquote())))
                }
            }
        }
        flushPending()
        return result
    }

    /** 前後の `"` / `'` クォートを 1 組だけ剥がす */
    private fun String.unquote(): String {
        if (length >= 2) {
            val first = first()
            val last = last()
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return substring(1, length - 1)
            }
        }
        return this
    }
}
