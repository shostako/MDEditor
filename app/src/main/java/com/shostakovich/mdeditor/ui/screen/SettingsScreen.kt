package com.shostakovich.mdeditor.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.shostakovich.mdeditor.BuildConfig
import com.shostakovich.mdeditor.auth.AuthManager
import com.shostakovich.mdeditor.data.index.PageTokenStorage
import com.shostakovich.mdeditor.data.prefs.UiPrefsStorage
import com.shostakovich.mdeditor.data.vault.VaultIndex
import com.shostakovich.mdeditor.data.vault.VaultRootStorage
import com.shostakovich.mdeditor.ui.theme.MDEditorTheme
import kotlinx.coroutines.launch

/**
 * 設定画面 (M11)。4 項目:
 *  1. バージョン情報 (BuildConfig)
 *  2. ファイル一覧の表示フィルタ (UiPrefsStorage.showAllFiles)
 *  3. Vault 再インデックス (VaultIndex.forceResync)
 *  4. ログアウト (AlertDialog 確認 → 全 storage クリア → LoginScreen)
 *
 * 導線: FileTreeScreen ヘッダの ⚙ ボタンから。
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onLoggedOut: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val indexState by VaultIndex.state.collectAsState()
    var showLogoutConfirm by remember { mutableStateOf(false) }
    val showAllFiles by UiPrefsStorage.showAllFiles.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // ヘッダ
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← 戻る") }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "設定",
                style = MaterialTheme.typography.titleMedium,
            )
        }
        HorizontalDivider()

        // -- アプリ情報 --
        SectionHeader("アプリ情報")
        Row(modifier = Modifier.padding(vertical = 8.dp, horizontal = 8.dp)) {
            Text("バージョン", modifier = Modifier.width(120.dp), style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        HorizontalDivider()

        // -- 表示 --
        SectionHeader("表示")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("すべてのファイルを表示", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "OFF: フォルダ・ノート(.md)・画像 だけを一覧に出す\n" +
                        "ON: 開けないファイルや .obsidian なども含めて全件出す",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            Switch(
                checked = showAllFiles,
                onCheckedChange = { UiPrefsStorage.saveShowAllFiles(it) },
            )
        }
        HorizontalDivider()

        // -- Vault --
        SectionHeader("Vault")
        val builtState = indexState as? VaultIndex.IndexState.Built
        val isSyncing = builtState?.isSyncing == true
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "インデックスを再構築",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = if (builtState != null) {
                        "${builtState.files.size} 件 インデックス済" +
                            if (isSyncing) " ・ 同期中..." else ""
                    } else {
                        "インデックス未構築"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            if (isSyncing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                OutlinedButton(
                    enabled = builtState != null,
                    onClick = {
                        val vaultId = VaultRootStorage.loadVaultId()
                        val vaultName = VaultRootStorage.loadVaultName()
                        if (vaultId != null && vaultName != null) {
                            scope.launch {
                                VaultIndex.forceResync(vaultId, vaultName, scope)
                            }
                        }
                    },
                ) { Text("再インデックス") }
            }
        }
        HorizontalDivider()

        // -- アカウント --
        SectionHeader("アカウント")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("ログアウト", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "認証情報・Vault 選択・検索インデックスを全て削除",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            Button(
                onClick = { showLogoutConfirm = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                ),
            ) { Text("ログアウト") }
        }
    }

    // ログアウト確認 Dialog
    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            title = { Text("ログアウトしますか?") },
            text = {
                Text(
                    "認証情報・Vault 選択・検索インデックスをすべて削除します。\n" +
                        "Drive 側の本文には影響しません。"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showLogoutConfirm = false
                        scope.launch {
                            // 全 storage をクリア。順序は依存関係を考慮して:
                            //   VaultIndex.clear() → DB + メモリ + pageToken
                            //   VaultRootStorage.clear() → vault root 選択
                            //   AuthManager.logout() → AuthState + TokenStorage
                            VaultIndex.clear()
                            VaultRootStorage.clear()
                            PageTokenStorage.clear()
                            AuthManager.logout()
                            onLoggedOut()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("ログアウト") }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirm = false }) {
                    Text("キャンセル")
                }
            },
        )
    }

}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp, start = 4.dp),
    )
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    MDEditorTheme {
        SettingsScreen(onBack = {}, onLoggedOut = {})
    }
}
