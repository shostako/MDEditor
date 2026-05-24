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
}
