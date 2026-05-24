package com.shostakovich.mdeditor.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.shostakovich.mdeditor.data.drive.DriveFile
import com.shostakovich.mdeditor.data.vault.VaultRepository
import com.shostakovich.mdeditor.data.vault.VaultRootStorage
import com.shostakovich.mdeditor.ui.theme.MDEditorTheme
import kotlinx.coroutines.launch

/**
 * Vault root 選択画面。
 *
 * 動作:
 * 1. デフォルトで "BrainDump" を検索キーワードとして起動時に自動検索
 * 2. ユーザーは別の名前を入れて再検索もできる
 * 3. ヒットしたフォルダ一覧から1つタップで選択 → VaultRootStorage に保存 → FileTreeScreen 遷移
 *
 * 注: 名前検索だと同名フォルダが複数あった場合に困るが、Vault は通常1つしかないので
 * UX 的にこれで十分。複数ヒット時はユーザーがリストから選ぶ。
 */
@Composable
fun VaultScreen(
    onVaultSelected: (folderId: String, folderName: String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var queryText by remember { mutableStateOf("BrainDump") }
    var isLoading by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<DriveFile>>(emptyList()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var hasSearchedOnce by remember { mutableStateOf(false) }

    // 検索アクション。queryText を使って Drive を検索。
    fun runSearch() {
        val name = queryText.trim()
        if (name.isEmpty()) {
            errorMessage = "検索名を入力しろ"
            return
        }
        scope.launch {
            isLoading = true
            errorMessage = null
            results = emptyList()
            try {
                results = VaultRepository.searchFoldersByName(name)
                if (results.isEmpty()) {
                    errorMessage = "「$name」というフォルダが見つからない"
                }
            } catch (e: Throwable) {
                errorMessage = "検索失敗: ${e.message ?: e::class.simpleName}"
            } finally {
                isLoading = false
                hasSearchedOnce = true
            }
        }
    }

    // 初回起動時に "BrainDump" で自動検索
    LaunchedEffect(Unit) {
        if (!hasSearchedOnce) runSearch()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Vault root を選択",
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Google Drive 上の Obsidian Vault root フォルダ名で検索する",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = queryText,
            onValueChange = { queryText = it },
            label = { Text("フォルダ名") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { runSearch() },
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isLoading) "検索中..." else "検索")
        }
        Spacer(Modifier.height(16.dp))

        // ローディング表示
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }

        // エラー表示
        errorMessage?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }

        // 検索結果リスト
        if (results.isNotEmpty()) {
            Text(
                text = "ヒット: ${results.size} 件 (タップで選択)",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(results, key = { it.id }) { folder ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        onClick = {
                            VaultRootStorage.save(folder.id, folder.name)
                            onVaultSelected(folder.id, folder.name)
                        }
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = folder.name,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "fileId: ${folder.id.take(20)}...",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun VaultScreenPreview() {
    MDEditorTheme {
        VaultScreen(onVaultSelected = { _, _ -> })
    }
}
