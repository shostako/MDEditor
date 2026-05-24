package com.shostakovich.mdeditor.markdown

import android.util.Log
import com.shostakovich.mdeditor.data.vault.VaultIndex
import com.shostakovich.mdeditor.data.vault.VaultRepository

/**
 * Obsidian Wikilink を Markwon が扱える標準 MD に変換する。
 *
 * 2 種類の Wikilink を扱う:
 *
 * **画像 (`![[file.ext]]`)** — `[!` の有無で識別。同フォルダの画像を fileId に解決して
 *   `![alt](mdeditor-drive://fileId)` に置換 (DriveSchemeHandler で読み込み)。
 *   解決失敗時は `*[image not found: name]*` テキストに退避。
 *
 * **ノートリンク (`[[note]]`)** — 同名 .md ファイルを VaultIndex 経由で解決して
 *   `[note](mdeditor-note://fileId)` に置換 (Markwon の LinkResolver で受ける)。
 *   解決失敗時は元の `[[note]]` テキストをそのまま残す (壊れた目印になる)。
 *
 * ## ノートリンクの構文バリエーション
 *
 *  - `[[note]]`              基本
 *  - `[[note.md]]`           拡張子付き (Obsidian も両許容)
 *  - `[[note|表示]]`         エイリアス → 表示テキストは "表示"
 *  - `[[note#見出し]]`       セクションリンク → `#` 以降は無視してファイル単位で遷移
 *  - `[[note^block-id]]`     ブロック参照 → `^` 以降は無視
 *  - 上記の組み合わせ        例: `[[note#見出し|表示]]`
 *
 * ## ノートの解決ルール
 *
 *  1. VaultIndex.allMarkdownFiles から拡張子省略の **完全一致** 候補を集める
 *  2. 1件 → 採用
 *  3. 複数 → `currentFolderPath` と同 folderPath を優先、それでも複数なら最初の1件
 *  4. 0件 → 解決失敗、元のテキストを残す
 *
 * @param parentFolderId 画像解決用 Drive フォルダ ID。null なら画像 Wikilink は素通り
 * @param currentFolderPath ノートリンク同名複数候補時の優先元。null なら優先なし
 */
class WikilinkResolver(
    private val parentFolderId: String?,
    private val currentFolderPath: String?,
) {
    private var nameToImageFileIdCache: Map<String, String>? = null

    suspend fun resolveAll(markdown: String): String {
        // 画像 → ノートの順。画像は `![[...]]` で必ず `!` 付き、置換結果は `![](...)` 形式に変わるので、
        // その後にノート用の `[[...]]` パターンを処理すれば誤認しない (`[[` の連続が消える)。
        var result = markdown
        if (parentFolderId != null) {
            result = resolveImages(result)
        }
        result = resolveNotes(result)
        return result
    }

    // -----------------------------------------------------------------------------------
    // 画像 Wikilink (`![[...]]`) — M5 と同じロジック
    // -----------------------------------------------------------------------------------

    private suspend fun resolveImages(markdown: String): String {
        val matches = WIKILINK_IMAGE_REGEX.findAll(markdown).toList()
        if (matches.isEmpty()) return markdown

        if (nameToImageFileIdCache == null) {
            try {
                val children = VaultRepository.listChildren(parentFolderId!!)
                nameToImageFileIdCache = children
                    .filter { it.isImage }
                    .associate { it.name to it.id }
                Log.d(
                    TAG,
                    "image index built: ${nameToImageFileIdCache!!.size} image(s) in folder"
                )
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to build image wikilink index", e)
                nameToImageFileIdCache = emptyMap()
            }
        }
        val map = nameToImageFileIdCache!!

        return WIKILINK_IMAGE_REGEX.replace(markdown) { match ->
            val raw = match.groupValues[1].trim()
            val name = raw.substringBefore("|").trim()
            val fileId = map[name]
            if (fileId != null) {
                "![$name]($DRIVE_SCHEME://$fileId)"
            } else {
                "*[image not found: $name]*"
            }
        }
    }

    // -----------------------------------------------------------------------------------
    // ノート Wikilink (`[[...]]`)
    // -----------------------------------------------------------------------------------

    private fun resolveNotes(markdown: String): String =
        resolveNotesPure(
            markdown = markdown,
            allFiles = VaultIndex.allMarkdownFiles,
            currentFolderPath = currentFolderPath,
        )

    companion object {
        private const val TAG = "WikilinkResolver"

        /** Drive 上のファイルへの参照を表すカスタムスキーム。DriveSchemeHandler がハンドルする */
        const val DRIVE_SCHEME = "mdeditor-drive"

        /** ノートリンクを表すカスタムスキーム。MarkdownText の LinkResolver がハンドルする */
        const val NOTE_SCHEME = "mdeditor-note"

        /** `![[...]]` の中身を1グループ目で捕捉 (画像) */
        private val WIKILINK_IMAGE_REGEX = Regex("""!\[\[([^\]\n]+?)\]\]""")

        /**
         * `[[...]]` の中身を1グループ目で捕捉 (ノートリンク)。
         * 画像 `![[...]]` は **先に処理して** `![](...)` 形式に変換済みなので、
         * その後に走るこの正規表現は誤って画像をマッチしない。
         */
        private val WIKILINK_NOTE_REGEX = Regex("""\[\[([^\]\n]+?)\]\]""")

        /**
         * ノート Wikilink 解決の純粋関数版。VaultIndex 参照を外から注入できる形にして
         * ユニットテストできるようにした。
         *
         * - `allFiles` が空 → 元の markdown をそのまま返す
         * - 個別の `[[note]]` ごとに:
         *    - 空、`#`/`^` だけ → 元のまま
         *    - 拡張子省略の完全一致を探す。0件 → 元のまま (解決失敗マーク)
         *    - 複数 → `currentFolderPath` 一致を優先、それでも複数なら最初の1件
         *    - 表示テキスト = `|` 後ろの override or raw 全文
         */
        internal fun resolveNotesPure(
            markdown: String,
            allFiles: List<VaultIndex.IndexedFile>,
            currentFolderPath: String?,
        ): String {
            if (allFiles.isEmpty()) return markdown
            val nameToFiles: Map<String, List<VaultIndex.IndexedFile>> = allFiles.groupBy { f ->
                f.file.name.removeSuffix(".md").removeSuffix(".MD")
            }

            return WIKILINK_NOTE_REGEX.replace(markdown) { match ->
                val raw = match.groupValues[1].trim()
                if (raw.isEmpty()) return@replace match.value

                val (linkRef, displayOverride) = raw.split("|", limit = 2)
                    .let { it[0].trim() to it.getOrNull(1)?.trim() }

                val rawCorePart = linkRef
                    .substringBefore("#")
                    .substringBefore("^")
                    .trim()
                if (rawCorePart.isEmpty()) return@replace match.value

                // Obsidian の Vault 相対パス指定 (`folder/subfolder/note`) を分解。
                // `/` が無ければ単一の basename だけになる。
                val pathParts = rawCorePart.split("/")
                val rawNoteName = pathParts.last()
                val pathHint = pathParts.dropLast(1).filter { it.isNotEmpty() }
                val noteName = rawNoteName.removeSuffix(".md").removeSuffix(".MD")
                if (noteName.isEmpty()) return@replace match.value

                val candidates = nameToFiles[noteName]
                if (candidates.isNullOrEmpty()) {
                    Log.d(TAG, "note not found: '$noteName' (raw='$raw')")
                    return@replace match.value
                }

                // 優先順位:
                //  1. pathHint と folderPath の末尾セグメントが一致する候補
                //     (例: pathHint=[entities] → folderPath="... > entities" を優先)
                //  2. currentFolderPath と完全一致する候補
                //  3. 最初の候補
                val chosen = (
                    pathHint.takeIf { it.isNotEmpty() }
                        ?.let { hint -> candidates.firstOrNull { matchesFolderHint(it.folderPath, hint) } }
                ) ?: candidates.firstOrNull {
                    currentFolderPath != null && it.folderPath == currentFolderPath
                } ?: candidates.first()

                val display = displayOverride?.takeIf { it.isNotEmpty() } ?: raw
                // 表示テキスト中の `]` は WIKILINK_NOTE_REGEX で raw から除外済みなので
                // ここに来る時点で含まれない。`\` だけバックスラッシュエスケープして
                // Markwon のリンク構文を壊さないようにする。
                val safeDisplay = display.replace("\\", "\\\\")
                "[$safeDisplay]($NOTE_SCHEME://${chosen.file.id})"
            }
        }

        /**
         * folderPath (`"Vault > A > B"`) の **末尾** セグメントが pathHint と完全一致するか。
         * 例: folderPath=`"BrainDump > Knowledge > entities"`, hint=`["entities"]` → true
         *     folderPath=`"BrainDump > Knowledge > entities"`, hint=`["Knowledge","entities"]` → true
         */
        private fun matchesFolderHint(folderPath: String, hint: List<String>): Boolean {
            val segments = folderPath.split(" > ")
            if (segments.size < hint.size) return false
            return segments.takeLast(hint.size) == hint
        }
    }
}
