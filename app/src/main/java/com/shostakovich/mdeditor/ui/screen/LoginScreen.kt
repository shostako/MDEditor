package com.shostakovich.mdeditor.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.shostakovich.mdeditor.auth.AuthManager
import com.shostakovich.mdeditor.auth.TokenStorage
import com.shostakovich.mdeditor.ui.theme.MDEditorTheme

/**
 * ログイン画面 (M2-b 実装済)。
 *
 * フロー:
 * 1. ボタン押下で AppAuth が Chrome Custom Tabs を起動 → Google ログインページ
 * 2. ユーザーがログイン + 同意 → アプリに戻り、Authorization Code を取得
 * 3. Token Endpoint に POST して access_token / refresh_token を取得
 * 4. 成功したら onLoginSuccess() を呼んで VaultScreen に遷移
 *
 * 注: Token Exchange は非同期 (ネットワーク I/O)。
 * onResult コールバックは別スレッドから呼ばれる可能性があるが、
 * Compose State の書き換えは内部でメインスレッドにマーシャルされるので問題ない。
 */
@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit
) {
    // 認証結果メッセージ。トークン交換中も "認証中..." を表示する用途で残す。
    var statusMessage by remember { mutableStateOf<String?>(null) }

    // AppAuth の戻りを受け取る ActivityResult launcher。
    // ブラウザで完了 (or キャンセル) すると onActivityResult のコールバックが走る。
    val authLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        statusMessage = "認証中... (トークン交換)"
        AuthManager.handleAuthorizationResponse(result.data) { success, message ->
            statusMessage = if (success) "✓ $message" else "✗ $message"
            if (success) {
                // トークン取得成功 → VaultScreen に遷移
                onLoginSuccess()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "MDEditor",
            style = MaterialTheme.typography.headlineLarge
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Google Drive 上の Obsidian Vault を編集",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        // v1.12: 暗号化認証ストレージの復旧告知。
        // 端末修理・OS更新等で Keystore と暗号データが不整合になった場合、
        // TokenStorage が認証情報だけをリセットして起動を継続する (クラッシュ回避)。
        // その事実をユーザーに日本語で伝える。Drive 上のデータは無事である旨も明記。
        when (TokenStorage.initResult) {
            TokenStorage.InitResult.Recovered -> {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "端末のセキュリティ状態が変わったため、" +
                        "保存されていたログイン情報をリセットしました。\n" +
                        "お手数ですが再度ログインしてください。\n" +
                        "(Drive 上のノートや画像は無事です)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
            TokenStorage.InitResult.Degraded -> {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "ログイン情報の暗号化保存が現在利用できません。\n" +
                        "ログインは可能ですが、次回起動時に再ログインが必要になる場合があります。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }
            TokenStorage.InitResult.Ok -> Unit
        }
        Spacer(Modifier.height(32.dp))
        Button(onClick = {
            // AppAuth の Authorization Intent を取得して launcher で起動。
            // 起動すると Chrome Custom Tabs が立ち上がり、Google ログインページに飛ぶ。
            val intent = AuthManager.getAuthorizationRequestIntent()
            authLauncher.launch(intent)
        }) {
            Text("Google でログイン")
        }
        statusMessage?.let {
            Spacer(Modifier.height(24.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun LoginScreenPreview() {
    MDEditorTheme {
        LoginScreen(onLoginSuccess = {})
    }
}
