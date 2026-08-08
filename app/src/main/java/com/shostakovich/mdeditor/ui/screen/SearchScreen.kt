package com.shostakovich.mdeditor.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.shostakovich.mdeditor.data.search.SearchRepository
import com.shostakovich.mdeditor.data.search.SearchResult
import com.shostakovich.mdeditor.data.vault.VaultIndex
import com.shostakovich.mdeditor.data.vault.VaultRootStorage
import com.shostakovich.mdeditor.ui.theme.MDEditorTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 検索画面。
 *
 * 流れ:
 *  1. 初回起動時、VaultIndex が未構築なら自動で build() を実行 (進捗表示)
 *  2. 構築完了後、検索キーワード入力欄が活性化
 *  3. 入力に応じて SearchRepository.search を起動、結果を Flow で逐次受け取りリストに追加
 *  4. 入力が変わったら前の検索 Job をキャンセルして新規開始
 *  5. 結果タップで EditorScreen に遷移
 */
@Composable
fun SearchScreen(
    onFileClick: (fileId: String) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val indexState by VaultIndex.state.collectAsState()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var searchJob by remember { mutableStateOf<Job?>(null) }

    // 初回マウント時に VaultIndex 起動 (未開始 or エラー時のフォールバック)。
    // 通常は MainActivity が起動時に呼んでるが、Vault 切替直後など start 未呼びの状態もありうる。
    LaunchedEffect(Unit) {
        if (indexState is VaultIndex.IndexState.NotBuilt ||
            indexState is VaultIndex.IndexState.Error
        ) {
            val rootId = VaultRootStorage.loadVaultId()
            val rootName = VaultRootStorage.loadVaultName()
            if (rootId != null && rootName != null) {
                scope.launch {
                    VaultIndex.start(context, rootId, rootName)
                }
            }
        }
    }

    // クエリが変わるたびに検索を再起動 (前の Job キャンセル)
    LaunchedEffect(query, indexState) {
        searchJob?.cancel()
        results = emptyList()
        if (query.isBlank() || indexState !is VaultIndex.IndexState.Built) {
            isSearching = false
            return@LaunchedEffect
        }
        searchJob = scope.launch {
            isSearching = true
            try {
                // toList で全部集めるのではなく collect で逐次更新
                SearchRepository.search(query).collect { result ->
                    results = results + result
                }
            } finally {
                isSearching = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // ヘッダ: 戻る
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) {
                Text("← 戻る")
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "検索",
                style = MaterialTheme.typography.titleMedium
            )
        }
        HorizontalDivider()

        // インデックス構築状態の表示
        when (val s = indexState) {
            is VaultIndex.IndexState.NotBuilt -> {
                Text(
                    text = "インデックス未構築",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            is VaultIndex.IndexState.Building -> {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    Text(
                        text = "Vault を走査中... ${s.progress} ファイル発見",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.padding(top = 4.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            is VaultIndex.IndexState.Built -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 8.dp)
                ) {
                    Text(
                        text = "インデックス: ${s.files.size} ファイル" +
                            if (s.isSyncing) "  ・  同期中..." else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.weight(1f)
                    )
                    if (s.isSyncing) {
                        // 注意: Modifier.width(N.dp) **だけ** だと内部で size(40.dp) 相当の
                        // デフォルト描画領域が残り、配置原点と描画中心がズレて
                        // スピナーが「別の軸を公転してる」ように見える。
                        // 必ず size(N.dp) で正方形に固定する。
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        TextButton(onClick = {
                            val rootId = VaultRootStorage.loadVaultId()
                            val rootName = VaultRootStorage.loadVaultName()
                            if (rootId != null && rootName != null) {
                                VaultIndex.forceResync(rootId, rootName)
                            }
                        }) {
                            Text("再同期", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            is VaultIndex.IndexState.Error -> {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    Text(
                        text = "インデックス構築失敗: ${s.message}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Button(
                        onClick = {
                            val rootId = VaultRootStorage.loadVaultId()
                            val rootName = VaultRootStorage.loadVaultName()
                            if (rootId != null && rootName != null) {
                                scope.launch {
                                    VaultIndex.start(context, rootId, rootName)
                                }
                            }
                        },
                    ) { Text("再構築") }
                }
            }
        }

        // 検索入力欄。構築中でも入力は可能 (検索結果が出るのは構築完了後)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            label = { Text("ファイル名と本文を検索") },
            singleLine = true,
            trailingIcon = {
                if (isSearching) {
                    // 同じ罠: width 単独指定だと描画中心がズレる。size で正方形固定。
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(20.dp),
                        strokeWidth = 2.dp
                    )
                }
            },
            supportingText = {
                // 構築中のヒント
                if (indexState is VaultIndex.IndexState.Building) {
                    Text(
                        text = "インデックス構築中。完了後に検索を開始します",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        )

        // 結果リスト
        if (results.isEmpty() && query.isNotBlank() && !isSearching &&
            indexState is VaultIndex.IndexState.Built
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "該当なし",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(
                    items = results,
                    key = { it.file.id + it.matchType.name },
                ) { result ->
                    SearchResultRow(
                        result = result,
                        onClick = { onFileClick(result.file.id) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(
    result: SearchResult,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // 1行目: ファイル名
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (result.matchType == SearchResult.MatchType.FILENAME) "📝" else "🔍",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = result.file.name,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        // 2行目: フォルダパス
        Text(
            text = result.folderPath,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        // 3行目: 本文一致時はスニペット
        if (result.snippet != null) {
            Text(
                text = result.snippet,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SearchScreenPreview() {
    MDEditorTheme {
        SearchScreen(onFileClick = {}, onBack = {})
    }
}
