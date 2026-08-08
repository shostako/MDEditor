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
    get() = name.startsWith(".") || !(isFolder || isMarkdown || isImage)
