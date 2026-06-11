package com.shostakovich.mdeditor.ui.markdown

import android.text.Spannable
import android.text.Spanned
import android.text.style.ClickableSpan
import android.text.util.Linkify
import android.util.Log
import android.view.MotionEvent
import android.widget.TextView
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import com.shostakovich.mdeditor.data.vault.VaultIndex
import com.shostakovich.mdeditor.markdown.DriveSchemeHandler
import com.shostakovich.mdeditor.markdown.MathNormalizer
import com.shostakovich.mdeditor.markdown.WikilinkResolver
import io.noties.markwon.core.spans.HeadingSpan
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.LinkResolverDef
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.image.AsyncDrawableSpan
import io.noties.markwon.image.ImagesPlugin
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import io.noties.markwon.linkify.LinkifyPlugin

/**
 * Markdown 内の見出し1つ分。アウトライン (目次ジャンプ) 用。
 * @param text 見出しテキスト (記号を剥がした表示文字列)
 * @param level 見出しレベル (1〜6)。アウトラインのインデントに使う
 * @param yPx レンダリング済み TextView 内での見出し上端の Y 座標 (px)。
 *   TextView の Compose 上の配置 Y と足すとスクロール目標 px になる
 */
data class MarkdownHeading(val text: String, val level: Int, val yPx: Int)

/**
 * Markdown をレンダリング表示する Composable。
 *
 * 実装方針:
 *  - Markwon は TextView ベース。AndroidView で TextView を埋め込む。
 *  - parentFolderId が指定されれば、Obsidian Wikilink `![[file.png]]` を
 *    `![](mdeditor-drive://fileId)` に置換してから Markwon に渡す。
 *  - Markwon の SchemeHandler (DriveSchemeHandler) が mdeditor-drive スキームを受けて
 *    Drive からバイト列を取得 → 画面幅にダウンサンプル → 表示
 *  - 画像タップで onImageClick(fileId) を呼ぶ (フルスクリーン拡大用)
 *  - ノートリンク `[[note]]` も WikilinkResolver で解決して
 *    `[display](mdeditor-note://fileId)` に置換。Markwon の linkResolver で受けて
 *    onNoteClick(fileId) を呼ぶ。
 *
 * 対応している記法:
 *  - 標準 Markdown (見出し / リスト / リンク / 引用 / コードブロック)
 *  - 取り消し線 ~~text~~
 *  - テーブル
 *  - タスクリスト
 *  - 限定 HTML タグ
 *  - 裸 URL の自動リンク化
 *  - Obsidian Wikilink 画像 `![[name.ext]]` (parentFolderId 指定時、同フォルダ解決)
 *  - Obsidian Wikilink ノート `[[note]]` (VaultIndex 経由、エイリアス・セクション対応)
 *
 * @param markdown 表示する Markdown ソース
 * @param parentFolderId 同フォルダの画像解決用 Drive フォルダ ID。null なら Wikilink 画像は素通り
 * @param currentFolderPath ノートリンク同名複数候補時の優先元 (VaultIndex の folderPath)
 * @param onImageClick 画像タップ時のコールバック (fileId 引数)。null ならタップ無視
 * @param onNoteClick ノートリンクタップ時のコールバック (fileId 引数)。null なら通常リンクと同じ挙動
 * @param onHeadingsChanged レンダリング後に抽出した見出しリストを渡す (アウトライン表示用)。null なら抽出しない
 * @param modifier Compose modifier
 */
@Composable
fun MarkdownText(
    markdown: String,
    parentFolderId: String? = null,
    currentFolderPath: String? = null,
    onImageClick: ((fileId: String) -> Unit)? = null,
    onNoteClick: ((fileId: String) -> Unit)? = null,
    onHeadingsChanged: ((List<MarkdownHeading>) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // ダーク/ライト対応: Markwon は TextView ベースなので、MaterialTheme と自動連動しない。
    // 明示的に MaterialTheme.colorScheme から色を引いて TextView と Markwon プラグインに注入する。
    // これが無いと、システムがダークモード時に Compose 背景だけ暗色に切替 → TextView の文字色は
    // AppCompat 既定 (黒) のまま → 「暗背景 + 黒文字」で読めなくなる (典型的なバグ)。
    //  - textColor: 本文の文字色。MaterialTheme.colorScheme.onSurface (ライト=黒, ダーク=白)。
    //  - linkColor: リンク (Wikilink + URL) の色。MaterialTheme.colorScheme.primary。
    val textColorArgb = MaterialTheme.colorScheme.onSurface.toArgb()
    val linkColorArgb = MaterialTheme.colorScheme.primary.toArgb()

    // 数式 (JLatexMath) の文字サイズ。TextView のデフォルト (AppCompat 既定 14sp 相当) に
    // 依存させず、本文と同じ px を Compose 側から導出し、数式プラグインと TextView の両方に
    // 同じ値を注入する。これで本文テキストと数式のフォントサイズが揃う。
    val bodyFontSizeSp = MaterialTheme.typography.bodyLarge.fontSize
    val density = LocalDensity.current
    val textSizePx = with(density) { bodyFontSizeSp.toPx() }

    // クリックハンドラはタッチリスナや Markwon プラグインのクロージャに captured されるので、
    // 最新参照を保持するため rememberUpdatedState で包む。
    val currentOnImageClick by rememberUpdatedState(onImageClick)
    val currentOnNoteClick by rememberUpdatedState(onNoteClick)
    val currentOnHeadingsChanged by rememberUpdatedState(onHeadingsChanged)

    // 画面幅 (ピクセル)。padding を多少考慮して 32dp 程度引いておく。
    // density は context.resources.displayMetrics.density、px = dp * density。
    val maxImageWidthPx = remember(context) {
        val displayMetrics = context.resources.displayMetrics
        val paddingPx = (32 * displayMetrics.density).toInt() // 左右 16dp ずつ程度想定
        (displayMetrics.widthPixels - paddingPx).coerceAtLeast(320)
    }

    // VaultIndex の Built 状態を購読。インデックス件数が変わるたびに WikilinkResolver を
    // 再実行する。これが無いと、EditorScreen を開いた瞬間に VaultIndex がまだ空だった場合
    // (アプリ起動直後など)、resolveNotesPure が冒頭で素通りして以後再計算されず、
    // ノートリンクが永遠に解決されないバグになる。
    val indexState by VaultIndex.state.collectAsState()
    val indexedCount = (indexState as? VaultIndex.IndexState.Built)?.files?.size ?: 0

    // 前処理結果。markdown / parentFolderId / currentFolderPath / インデックス件数が
    // 変わるたびに再計算。
    var processedMarkdown by remember(markdown, parentFolderId, currentFolderPath) {
        mutableStateOf(markdown)
    }
    LaunchedEffect(markdown, parentFolderId, currentFolderPath, indexedCount) {
        processedMarkdown = try {
            val resolved = WikilinkResolver(
                parentFolderId = parentFolderId,
                currentFolderPath = currentFolderPath,
            ).resolveAll(markdown)
            // Obsidian のインライン数式 $...$ を Markwon が要求する $$...$$ に正規化する。
            // (Wikilink 解決と独立。$ は [[ ]] と無関係なので順序依存なし)
            MathNormalizer.normalizeInlineMath(resolved)
        } catch (e: Throwable) {
            Log.e("MarkdownText", "markdown preprocess threw", e)
            markdown
        }
    }

    // Markwon インスタンスはプラグイン構築が重いので remember でキャッシュ。
    // linkColor は MarkwonTheme に焼き付けるため key に含める (テーマ切替時に rebuild)。
    // linkResolver はクロージャ内で currentOnNoteClick の最新参照を見る (rememberUpdatedState 効果)。
    val markwon = remember(context, maxImageWidthPx, linkColorArgb, textColorArgb, textSizePx) {
        Markwon.builder(context)
            // インラインパーサ。$...$ のインライン数式に必須。create() は commonmark-java の
            // InlineParserImpl 相当のデフォルトを全て含むため、既存のインライン記法
            // (強調 / コード / リンク / 画像 / オートリンク) は維持される。他プラグインの土台に
            // なるのでチェーン先頭に置く。
            .usePlugin(MarkwonInlineParserPlugin.create())
            // MarkwonTheme でリンク色を上書き。デフォルトは TextView.linkTextColor を見るが、
            // 明示しておく方が確実 (build 環境ごとの差を防ぐ)。
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureTheme(builder: MarkwonTheme.Builder) {
                    builder.linkColor(linkColorArgb)
                }
            })
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create(context))
            .usePlugin(TaskListPlugin.create(context))
            .usePlugin(HtmlPlugin.create())
            // Linkify は autoLink の対象を URL とメールのみに絞る。
            // デフォルト (Linkify.ALL) だと PHONE_NUMBERS が含まれ、これが「2016-05-13」のような
            // 日付パターンを電話番号として誤検出する (Obsidian Vault 用途では致命的)。
            // MAP_ADDRESSES も誤検出が多いので除外。
            .usePlugin(LinkifyPlugin.create(Linkify.WEB_URLS or Linkify.EMAIL_ADDRESSES))
            // 数式 (LaTeX)。ブロック $$...$$ + インライン $...$。色は本文と同じ onSurface にして
            // ダーク/ライト切替に追従させる。数式は Drawable 画像なので TextView.setTextColor は
            // 効かず、色はプラグイン構築時に焼き込む。だから textColorArgb を remember key に含める。
            .usePlugin(
                JLatexMathPlugin.create(textSizePx) { builder ->
                    builder.blocksEnabled(true)
                    builder.blocksLegacy(false)   // 4.3.0+ の新ブロックパーサ ($$...$$)
                    builder.inlinesEnabled(true)  // インライン $...$ (要 inline-parser)
                    builder.theme().textColor(textColorArgb)
                    builder.errorHandler { _, _ -> null }  // パース失敗は空表示にしクラッシュ回避
                }
            )
            .usePlugin(
                ImagesPlugin.create { plugin ->
                    plugin.addSchemeHandler(DriveSchemeHandler(maxImageWidthPx))
                }
            )
            // ノートリンク mdeditor-note://fileId を捕まえる linkResolver を登録。
            // それ以外の URL (http(s)/mailto 等) はデフォルト挙動 (Intent で開く)。
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                    val default = LinkResolverDef()
                    builder.linkResolver { view, link ->
                        val notePrefix = "${WikilinkResolver.NOTE_SCHEME}://"
                        if (link.startsWith(notePrefix)) {
                            val fileId = link.removePrefix(notePrefix)
                            if (fileId.isNotBlank()) {
                                currentOnNoteClick?.invoke(fileId)
                                return@linkResolver
                            }
                        }
                        default.resolve(view, link)
                    }
                }
            })
            .build()
    }

    // アウトライン用: 同じ見出しリストで毎回 callback して無限再コンポーズするのを防ぐ
    val lastHeadings = remember { mutableStateOf<List<MarkdownHeading>>(emptyList()) }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextView(ctx).apply {
                // 本文 px を数式 px と同一にする (textSizePx は MaterialTheme.bodyLarge 由来)。
                // これをしないと TextView は AppCompat 既定サイズになり、本文と数式でサイズがズレる。
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, textSizePx)
                // 本文選択を有効化。これは movementMethod を ArrowKeyMovementMethod に
                // 切り替えるため、URLSpan のクリック (LinkMovementMethod 任せ) は効かなくなる。
                // 代わりに setOnTouchListener で URLSpan / AsyncDrawableSpan を自前で
                // 逆引きしてクリック処理する。
                setTextIsSelectable(true)
                setOnTouchListener { v, event ->
                    if (event.action != MotionEvent.ACTION_UP) return@setOnTouchListener false
                    val tv = v as? TextView ?: return@setOnTouchListener false
                    val spannable = tv.text as? Spannable ?: return@setOnTouchListener false
                    // 選択中の確定タップは selection 終了に渡す (リンクと競合させない)
                    if (tv.hasSelection()) return@setOnTouchListener false

                    // タップ位置 → 文字オフセット
                    val x = (event.x - tv.totalPaddingLeft + tv.scrollX).toInt()
                    val y = (event.y - tv.totalPaddingTop + tv.scrollY).toInt()
                    val layout = tv.layout ?: return@setOnTouchListener false
                    val line = layout.getLineForVertical(y)
                    val offset = layout.getOffsetForHorizontal(line, x.toFloat())

                    // (1) 画像 span 優先
                    val imageSpans = spannable.getSpans(
                        offset, offset, AsyncDrawableSpan::class.java
                    )
                    if (imageSpans.isNotEmpty()) {
                        val handler = currentOnImageClick
                        if (handler != null) {
                            val destination = imageSpans[0].drawable.destination
                            val fileId = destination
                                .removePrefix("${WikilinkResolver.DRIVE_SCHEME}://")
                            if (fileId.isNotEmpty()) {
                                handler(fileId)
                                return@setOnTouchListener true
                            }
                        }
                    }

                    // (2) クリック可能 span (Markwon の LinkSpan / Linkify の URLSpan 両方)
                    //   ClickableSpan#onClick(view) を呼ぶだけで Markwon の linkResolver にも
                    //   URLSpan のデフォルト ACTION_VIEW にも自動でディスパッチされる。
                    val clickableSpans = spannable.getSpans(
                        offset, offset, ClickableSpan::class.java
                    )
                    if (clickableSpans.isNotEmpty()) {
                        try {
                            clickableSpans[0].onClick(tv)
                            return@setOnTouchListener true
                        } catch (e: Throwable) {
                            Log.w("MarkdownText", "ClickableSpan.onClick threw", e)
                        }
                    }

                    // span 無しなら false → selection 開始 / 終了などのデフォルト処理に渡す
                    false
                }
            }
        },
        update = { textView ->
            // ダーク/ライト切替時に色を更新。setTextColor は Markwon が span を当てる前に
            // 設定しておくと、span が無いプレーン部分の文字色として使われる。
            textView.setTextColor(textColorArgb)
            textView.setLinkTextColor(linkColorArgb)
            markwon.setMarkdown(textView, processedMarkdown)

            // アウトライン用の見出し抽出。layout が確定してからでないと Y 座標が取れないので
            // post で measure/layout 後のキューに載せる。HeadingSpan の開始オフセットから
            // 行 → 行上端 Y を引く。表示テキストとの照合は不要 (span が構造を保持している)。
            val headingsCb = currentOnHeadingsChanged
            if (headingsCb != null) {
                textView.post {
                    val spanned = textView.text as? Spanned ?: return@post
                    val layout = textView.layout ?: return@post
                    val headings = spanned
                        .getSpans(0, spanned.length, HeadingSpan::class.java)
                        .sortedBy { spanned.getSpanStart(it) }
                        .mapNotNull { span ->
                            val start = spanned.getSpanStart(span)
                            val end = spanned.getSpanEnd(span)
                            if (start < 0 || end <= start) return@mapNotNull null
                            val headingText = spanned.subSequence(start, end).toString().trim()
                            if (headingText.isEmpty()) return@mapNotNull null
                            val line = layout.getLineForOffset(start)
                            val y = layout.getLineTop(line) + textView.totalPaddingTop
                            // HeadingSpan.getLevel() はバージョン差があるのでリフレクションで安全に取る
                            val level = runCatching {
                                HeadingSpan::class.java.getMethod("getLevel").invoke(span) as Int
                            }.getOrDefault(1)
                            MarkdownHeading(headingText, level, y)
                        }
                    if (headings != lastHeadings.value) {
                        lastHeadings.value = headings
                        headingsCb(headings)
                    }
                }
            }
        }
    )
}
