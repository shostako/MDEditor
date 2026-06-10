package com.shostakovich.mdeditor.tts

/**
 * 「ここから読み上げ」の開始位置の手がかり。
 *
 * 表示テキストと読み上げチャンクは別経路の変換結果なので、文字オフセットの
 * 厳密な対応が取れない。そこで:
 *  - 第一手がかり: 選択位置の**直前の見出し** (Markwon の HeadingSpan から構造的に取得)。
 *    見出しテキストは正規化側にも行としてそのまま残るため、行一致で確実に特定できる
 *  - フォールバック: 選択位置からの表示テキスト片の部分文字列マッチ
 *
 * @param heading 選択位置の直前の見出しテキスト。見出しが無ければ null
 * @param headingOccurrence 同一テキストの見出しが複数ある場合の出現番号 (0-based)
 * @param snippet 選択位置からの表示テキスト片 (見出しが使えない時のフォールバック)
 */
data class TtsStartHint(
    val heading: String?,
    val headingOccurrence: Int,
    val snippet: String,
)
