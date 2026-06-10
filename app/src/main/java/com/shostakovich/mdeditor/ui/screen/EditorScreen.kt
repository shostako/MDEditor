package com.shostakovich.mdeditor.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.shostakovich.mdeditor.data.prefs.UiPrefsStorage
import com.shostakovich.mdeditor.data.vault.FileContentCache
import com.shostakovich.mdeditor.data.vault.VaultIndex
import com.shostakovich.mdeditor.data.vault.VaultRepository
import com.shostakovich.mdeditor.markdown.Frontmatter
import com.shostakovich.mdeditor.ui.markdown.MarkdownText
import com.shostakovich.mdeditor.ui.theme.MDEditorTheme
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Markdown ファイル閲覧 / 編集画面 (M4 〜 M7 実装)。
 *
 * 動作:
 *  1. fileId から並列に メタデータ (ファイル名) と 本文 を Drive から取得
 *  2. デフォルトは Preview モード — Markwon でレンダリング表示 + 画像
 *  3. Edit モードに切替えると生 Markdown を編集可能
 *  4. M7: 「保存」ボタンで Drive Files.update により本文を上書き保存
 *
 * 設計:
 *  - 並列フェッチ: coroutineScope { async {} } で 2 リクエスト同時実行
 *  - dirty 判定: editingContent != content (保存基準)
 *  - 戻るボタンは dirty なら確認 Dialog を出す (保存忘れ防止)
 *  - mode の状態は remember で十分 (画面回転で消えるが、M8+ の ViewModel 化で対応)
 */
@Composable
fun EditorScreen(
    fileId: String,
    onBack: () -> Unit,
    onNoteClick: ((fileId: String) -> Unit)? = null,
    onUpClick: ((folderId: String) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(true) }
    var fileName by remember { mutableStateOf<String?>(null) }
    // M5: 親フォルダ ID。Wikilink 画像解決 (同フォルダ優先) で MarkdownText に渡す。
    var parentFolderId by remember { mutableStateOf<String?>(null) }
    // M9: 自分の folderPath。Wikilink ノートリンク `[[note]]` 解決で同 folderPath を優先するため
    //     MarkdownText に渡す。VaultIndex.allMarkdownFiles から fileId で逆引き。
    //     VaultIndex.state を購読することで、画面表示時点で未 Built でも Built 化のタイミングで
    //     再評価される。
    val indexStateForFolder by VaultIndex.state.collectAsState()
    val currentFolderPath = remember(fileId, indexStateForFolder) {
        VaultIndex.allMarkdownFiles.firstOrNull { it.file.id == fileId }?.folderPath
    }
    // M10: frontmatter / body 分離管理。
    //   - frontmatter: `---\n...\n---\n` 全体 (delimiter 込み)。無ければ null
    //   - body: frontmatter を抜いた本文。Preview/Edit とも body だけ表示する
    //   - editingBody: Edit モードの編集中バッファ (本文)
    //   - editingFrontmatter: Edit モードの編集中バッファ (frontmatter 中身の生 YAML、
    //     delimiter なし)。トグル ON 時のみ UI に出るが、状態自体は常に保持する
    //   - 保存時は `(wrap(editingFrontmatter) ?: "") + editingBody` を Drive に書き戻す
    //     → frontmatter が消える事故を防ぎつつ、編集も反映する
    var frontmatter by remember { mutableStateOf<String?>(null) }
    var body by remember { mutableStateOf("") }
    var editingBody by remember { mutableStateOf("") }
    var editingFrontmatter by remember { mutableStateOf("") }
    // プロパティ (frontmatter) パネルの表示トグル。永続化される (UiPrefsStorage)。
    var showFrontmatter by remember { mutableStateOf(UiPrefsStorage.loadShowFrontmatter()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var mode by remember { mutableStateOf(EditorMode.Preview) }
    var reloadKey by remember { mutableStateOf(0) }
    // M5-b: 画像タップで開くフルスクリーン Dialog 用。null なら閉じている状態
    var viewerFileId by remember { mutableStateOf<String?>(null) }
    // M7: 保存処理の状態
    var saveState by remember { mutableStateOf<SaveState>(SaveState.Idle) }
    // M7: dirty 状態で戻ろうとした時の確認 Dialog 表示フラグ
    var showDiscardDialog by remember { mutableStateOf(false) }

    // 保存済み frontmatter の中身 (生 YAML)。editingFrontmatter との比較基準
    val savedFrontmatterInner = frontmatter?.let { Frontmatter.innerText(it) } ?: ""
    val isDirty = editingBody != body || editingFrontmatter != savedFrontmatterInner

    LaunchedEffect(fileId, reloadKey) {
        isLoading = true
        errorMessage = null
        fileName = null
        parentFolderId = null
        frontmatter = null
        body = ""
        editingBody = ""
        editingFrontmatter = ""
        saveState = SaveState.Idle
        try {
            coroutineScope {
                val metaDeferred = async { VaultRepository.getFileMetadata(fileId) }
                val contentDeferred = async { VaultRepository.downloadTextFile(fileId) }
                val rawContent = contentDeferred.await()
                val split = Frontmatter.split(rawContent)
                frontmatter = split.frontmatter
                body = split.body
                editingBody = split.body
                editingFrontmatter = split.frontmatter?.let { Frontmatter.innerText(it) } ?: ""
                try {
                    val meta = metaDeferred.await()
                    fileName = meta.name
                    parentFolderId = meta.parents.firstOrNull()
                } catch (_: Throwable) {
                    fileName = null
                    parentFolderId = null
                }
            }
        } catch (e: Throwable) {
            errorMessage = "読み込み失敗: ${e.message ?: e::class.simpleName}"
        } finally {
            isLoading = false
        }
    }

    // 保存処理。成功すると body / frontmatter を編集バッファで上書き、キャッシュ無効化。
    // frontmatter は editingFrontmatter を delimiter 込みに包み直して書き戻す。
    // 編集バッファが空白のみなら frontmatter なしファイルになる (wrap が null を返す)。
    fun doSave(onAfter: () -> Unit = {}) {
        if (saveState is SaveState.Saving) return
        scope.launch {
            saveState = SaveState.Saving
            try {
                val newFrontmatter = Frontmatter.wrap(editingFrontmatter)
                val toSave = (newFrontmatter ?: "") + editingBody
                VaultRepository.updateTextFile(fileId, toSave)
                body = editingBody
                frontmatter = newFrontmatter
                editingFrontmatter = newFrontmatter?.let { Frontmatter.innerText(it) } ?: ""
                FileContentCache.invalidate(fileId)
                saveState = SaveState.Success
                onAfter()
            } catch (e: Throwable) {
                saveState = SaveState.Error(e.message ?: e::class.simpleName ?: "unknown")
            }
        }
    }

    // 戻る処理。dirty なら確認 Dialog、それ以外は普通に戻る。
    fun handleBack() {
        if (isDirty) {
            showDiscardDialog = true
        } else {
            onBack()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // ヘッダ: 戻る + 親フォルダ + ファイル名
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { handleBack() }) {
                Text("← 戻る")
            }
            // parentFolderId が取れて onUpClick が渡されているときだけ表示。
            // これはノートが入ってる Drive フォルダの FileTreeScreen に飛ぶ。
            val parent = parentFolderId
            if (parent != null && onUpClick != null) {
                TextButton(onClick = { onUpClick(parent) }) {
                    Text("📁↑")
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = (fileName ?: "読み込み中...") + if (isDirty) " *" else "",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        // モード切替 + 保存ボタン
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ModeButton(
                text = "Preview",
                selected = mode == EditorMode.Preview,
                onClick = { mode = EditorMode.Preview }
            )
            ModeButton(
                text = "Edit",
                selected = mode == EditorMode.Edit,
                onClick = { mode = EditorMode.Edit }
            )
            // プロパティ表示トグル。frontmatter があるファイルでのみ表示。
            // Preview / Edit どちらのモードでも切替可能 (パネル自体は Preview で描画)。
            if (frontmatter != null) {
                ModeButton(
                    text = "📋",
                    selected = showFrontmatter,
                    onClick = {
                        showFrontmatter = !showFrontmatter
                        UiPrefsStorage.saveShowFrontmatter(showFrontmatter)
                    }
                )
            }
            Spacer(Modifier.weight(1f))
            // 編集モード時のみ保存ボタンを表示。dirty かつ Idle/Success の時に活性。
            if (mode == EditorMode.Edit) {
                Button(
                    onClick = { doSave() },
                    enabled = isDirty && saveState !is SaveState.Saving
                ) {
                    if (saveState is SaveState.Saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("保存中...")
                    } else {
                        Text("保存")
                    }
                }
            }
            if (!isLoading && errorMessage != null) {
                OutlinedButton(onClick = { reloadKey++ }) {
                    Text("再読み込み")
                }
            }
        }
        // 保存結果表示
        when (val s = saveState) {
            is SaveState.Success -> Text(
                text = "✓ 保存完了",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(vertical = 2.dp)
            )
            is SaveState.Error -> Text(
                text = "保存失敗: ${s.message}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 2.dp)
            )
            else -> Unit
        }
        HorizontalDivider()

        // 本文表示エリア
        when {
            isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            errorMessage != null -> {
                Text(
                    text = errorMessage!!,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp)
                )
            }
            mode == EditorMode.Preview -> {
                val scrollState = rememberScrollState()
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(vertical = 8.dp)
                ) {
                    // プロパティパネル: トグル ON かつ frontmatter があれば本文の上に表示
                    val fm = frontmatter
                    if (showFrontmatter && fm != null) {
                        FrontmatterPanel(
                            frontmatter = fm,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp)
                        )
                    }
                    // Preview は保存済み body を表示 (編集中バッファは表示しない)
                    MarkdownText(
                        markdown = body,
                        parentFolderId = parentFolderId,
                        currentFolderPath = currentFolderPath,
                        onImageClick = { fid -> viewerFileId = fid },
                        onNoteClick = onNoteClick,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            mode == EditorMode.Edit -> {
                // Edit でも frontmatter と body は分離したまま。
                // トグル ON なら frontmatter を生 YAML として編集できるフィールドを
                // エディタ上部に表示する (案A: 構文の責任はユーザー持ち)。
                // 保存時に doSave 内で wrap + 結合して書き戻す。
                Column(modifier = Modifier.fillMaxSize()) {
                    if (showFrontmatter && frontmatter != null) {
                        OutlinedTextField(
                            value = editingFrontmatter,
                            onValueChange = { editingFrontmatter = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 220.dp)
                                .padding(bottom = 8.dp),
                            label = { Text("プロパティ (YAML)") },
                            textStyle = MaterialTheme.typography.bodySmall,
                        )
                    }
                    OutlinedTextField(
                        value = editingBody,
                        onValueChange = { editingBody = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        label = { Text("本文 (Markdown)") }
                    )
                }
            }
        }
    }

    // 画像フルスクリーン Dialog
    viewerFileId?.let { fid ->
        ImageViewerDialog(
            fileId = fid,
            onDismiss = { viewerFileId = null }
        )
    }

    // M7: 未保存変更があるまま戻ろうとした時の確認 Dialog
    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("未保存の変更があります") },
            text = { Text("変更を破棄して戻るか、保存して戻るか選んでくれ。") },
            confirmButton = {
                Button(onClick = {
                    showDiscardDialog = false
                    doSave(onAfter = onBack)
                }) {
                    Text("保存して戻る")
                }
            },
            dismissButton = {
                Row {
                    OutlinedButton(onClick = {
                        showDiscardDialog = false
                        onBack()
                    }) {
                        Text("破棄して戻る")
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { showDiscardDialog = false }) {
                        Text("キャンセル")
                    }
                }
            }
        )
    }
}

private enum class EditorMode { Preview, Edit }

/**
 * frontmatter のプロパティを Obsidian 風の key-value 表で表示するパネル (read-only)。
 * パース不能な行は Frontmatter.parseProperties 側でスキップされる。
 * 1 件もパースできなければ生テキストをそのまま表示する (情報を握り潰さない)。
 */
@Composable
private fun FrontmatterPanel(
    frontmatter: String,
    modifier: Modifier = Modifier,
) {
    val properties = remember(frontmatter) { Frontmatter.parseProperties(frontmatter) }
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "プロパティ",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            if (properties.isEmpty()) {
                // パース不能だった場合のフォールバック: delimiter を除いた生テキスト
                Text(
                    text = frontmatter
                        .lines()
                        .filterNot { it.trim() == "---" }
                        .joinToString("\n")
                        .trim(),
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                properties.forEach { prop ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            text = prop.key,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(110.dp)
                        )
                        Text(
                            text = when {
                                prop.values.isEmpty() -> "—"
                                else -> prop.values.joinToString(", ")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 保存処理の状態。
 */
private sealed interface SaveState {
    data object Idle : SaveState
    data object Saving : SaveState
    data object Success : SaveState
    data class Error(val message: String) : SaveState
}

/**
 * Preview/Edit 切替ボタン。選択中は filled、未選択は outlined。
 */
@Composable
private fun ModeButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    if (selected) {
        Button(onClick = onClick, colors = ButtonDefaults.buttonColors()) {
            Text(text)
        }
    } else {
        OutlinedButton(onClick = onClick) {
            Text(text)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun EditorScreenPreview() {
    MDEditorTheme {
        EditorScreen(fileId = "sample.md", onBack = {})
    }
}
