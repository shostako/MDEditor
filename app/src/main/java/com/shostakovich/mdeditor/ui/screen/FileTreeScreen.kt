package com.shostakovich.mdeditor.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.shostakovich.mdeditor.data.drive.DriveFile
import com.shostakovich.mdeditor.data.vault.VaultRepository
import com.shostakovich.mdeditor.data.vault.VaultRootStorage
import com.shostakovich.mdeditor.ui.theme.MDEditorTheme
import kotlinx.coroutines.launch

/**
 * フォルダ内のファイル一覧画面。
 *
 * 同じ画面を再帰的に使う:
 *  - Vault root の fileId で開けば root 直下の一覧
 *  - その中のフォルダをタップすると、子フォルダの fileId で別 backStackEntry として再描画
 *  - 戻る (popBackStack) で親フォルダに戻れる
 *
 * 表示順:
 *  1. フォルダを先 (アルファベット順)
 *  2. その後 .md ファイル
 *  3. その後 画像
 *  4. その後 その他
 */
@Composable
fun FileTreeScreen(
    folderId: String,
    onFolderClick: (folderId: String) -> Unit,
    onMarkdownClick: (fileId: String) -> Unit,
    onSearchClick: () -> Unit,
    onBack: () -> Unit,
    onUpClick: ((parentFolderId: String) -> Unit)? = null,
    onSettingsClick: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(true) }
    var items by remember { mutableStateOf<List<DriveFile>>(emptyList()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    // 「📁↑」用: 親フォルダの fileId。Vault root or 取得失敗時は null (ボタン非表示)
    var parentFolderId by remember { mutableStateOf<String?>(null) }

    // folderId が変わったら再フェッチ。
    LaunchedEffect(folderId) {
        isLoading = true
        errorMessage = null
        items = emptyList()
        parentFolderId = null
        scope.launch {
            try {
                val raw = VaultRepository.listChildren(folderId)
                // 並び替え: フォルダ → md → 画像 → その他 の順、各カテゴリ内は名前昇順
                items = raw.sortedWith(
                    compareBy<DriveFile> {
                        when {
                            it.isFolder -> 0
                            it.isMarkdown -> 1
                            it.isImage -> 2
                            else -> 3
                        }
                    }.thenBy { it.name.lowercase() }
                )
            } catch (e: Throwable) {
                errorMessage = "読み込み失敗: ${e.message ?: e::class.simpleName}"
            } finally {
                isLoading = false
            }
        }
        // 親フォルダ取得 (Vault root にいる時は不要 = 「↑」非表示)
        scope.launch {
            val vaultRootId = VaultRootStorage.loadVaultId()
            if (folderId == vaultRootId) {
                parentFolderId = null
                return@launch
            }
            parentFolderId = try {
                VaultRepository.getFileMetadata(folderId).parents.firstOrNull()
            } catch (_: Throwable) {
                null
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // ヘッダ: 戻る + 親フォルダ + 件数表示 + 検索
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text("← 戻る")
            }
            // Vault root では非表示。それ以外は「📁↑」で親フォルダへ。
            val parent = parentFolderId
            if (parent != null && onUpClick != null) {
                TextButton(onClick = { onUpClick(parent) }) {
                    Text("📁↑")
                }
            }
            Spacer(Modifier.width(8.dp))
            if (!isLoading) {
                Text(
                    text = "${items.size} 件",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            TextButton(onClick = onSearchClick) {
                Text("🔍 検索")
            }
            if (onSettingsClick != null) {
                TextButton(onClick = onSettingsClick) {
                    Text("⚙")
                }
            }
        }
        HorizontalDivider()

        // ローディング
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            return@Column
        }

        // エラー
        errorMessage?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(16.dp)
            )
            return@Column
        }

        // 一覧
        if (items.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "(空のフォルダ)",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(items, key = { it.id }) { file ->
                    FileRow(
                        file = file,
                        onClick = {
                            when {
                                file.isFolder -> onFolderClick(file.id)
                                file.isMarkdown -> onMarkdownClick(file.id)
                                else -> Unit // 画像など、M3 段階では tap 無効
                            }
                        }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

/**
 * 1行 (フォルダ or ファイル) の描画。
 * 種別ごとに先頭アイコン (絵文字) と濃淡を変える。
 */
@Composable
private fun FileRow(
    file: DriveFile,
    onClick: () -> Unit,
) {
    val isClickable = file.isFolder || file.isMarkdown
    val icon = when {
        file.isFolder -> "📁"
        file.isMarkdown -> "📝"
        file.isImage -> "🖼"
        else -> "📄"
    }
    val nameWeight = if (file.isFolder) FontWeight.SemiBold else FontWeight.Normal

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (isClickable) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 8.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = icon,
            modifier = Modifier.size(28.dp),
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = file.name,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = nameWeight),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun FileTreeScreenPreview() {
    MDEditorTheme {
        FileTreeScreen(
            folderId = "dummy",
            onFolderClick = {},
            onMarkdownClick = {},
            onSearchClick = {},
            onBack = {},
        )
    }
}
