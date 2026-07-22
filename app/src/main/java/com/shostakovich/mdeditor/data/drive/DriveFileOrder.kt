package com.shostakovich.mdeditor.data.drive

/**
 * ファイル一覧の表示順。FileTreeScreen の一覧描画と、EditorScreen の兄弟ノート送り
 * （前/次のノート）で**同じ並び**を保証するために共有する。
 *
 *  1. フォルダ → 2. .md → 3. 画像 → 4. その他
 *  各カテゴリ内は名前の昇順（大文字小文字を無視）
 *
 * ここを一覧と兄弟ナビで揃えないと「一覧で次のはずのノートと違うノートに飛ぶ」ズレが出る。
 */
val DRIVE_FILE_DISPLAY_ORDER: Comparator<DriveFile> = compareBy<DriveFile> {
    when {
        it.isFolder -> 0
        it.isMarkdown -> 1
        it.isImage -> 2
        else -> 3
    }
}.thenBy { it.name.lowercase() }
