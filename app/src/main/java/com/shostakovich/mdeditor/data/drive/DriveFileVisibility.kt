package com.shostakovich.mdeditor.data.drive

/**
 * ファイル一覧で既定として隠す対象の判定。
 *
 * 一覧はこれまで Drive の子を全件出していたが、タップが効くのはフォルダと .md だけで、
 * それ以外は「押しても何も起きない行」として並んでいた。反応しない行は見た目が嘘になり、
 * Vault 直下の `.obsidian/` や添付フォルダの大量ファイルでノート本体が埋もれる。
 *
 * 隠す / 残すの線引きは「このアプリで意味のある操作ができるか」で引いている:
 *  - フォルダ  … 残す (辿れる)
 *  - .md       … 残す (開ける)
 *  - 画像      … 残す (ImageViewerDialog で開ける。添付フォルダを開いて空に見えるのを防ぐ)
 *  - その他    … 隠す (pdf / zip / .canvas / .base / Google ドキュメント等。開く道が無い)
 *  - ドット始まり … 種別を問わず隠す。`.obsidian/` は**フォルダ**なので種別判定では
 *    引っかからない。Vault 直下で一番うるさいのがこれなので明示的に弾く。
 *
 * 設定の「すべてのファイルを表示」を ON にすると、この判定は無視して全件出す。
 */
val DriveFile.isHiddenByDefault: Boolean
    get() = isDotEntry || !(isFolder || isMarkdown || isImage)

/**
 * ドット始まりのファイル / フォルダ (`.obsidian/`, `.trash/`, `.draft.md` 等)。
 *
 * 一覧の非表示判定と、検索インデックスの走査除外の**両方**がこれを使う。
 * 片方だけに入れると「一覧には出ないのに検索には出る」という食い違いになり、
 * 削除済みノートを現役ノートと同じ見た目で開いて編集できてしまう。
 */
val DriveFile.isDotEntry: Boolean
    get() = name.startsWith(".")

/**
 * `folderPath` ("Vault > 親A > 親B" 形式) がドット始まりのフォルダを含むか。
 *
 * 差分同期では、既に DB にあるファイルは保存済みの folderPath をそのまま使って
 * 親を辿り直さない。そのため DriveFile 単体では `.trash` 配下だと判定できず、
 * 保存済みのパス文字列から判定する必要がある。
 *
 * 先頭要素は Vault root 名なので判定から外す。Vault 自体がドット始まりの名前だと
 * 全ノートが除外されてしまうため。
 */
fun String.hasDotFolderSegment(): Boolean =
    split(" > ").drop(1).any { it.startsWith(".") }
