package com.shostakovich.mdeditor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.shostakovich.mdeditor.auth.AuthManager
import com.shostakovich.mdeditor.data.index.IndexDatabaseProvider
import com.shostakovich.mdeditor.data.index.PageTokenStorage
import com.shostakovich.mdeditor.data.prefs.UiPrefsStorage
import com.shostakovich.mdeditor.data.vault.VaultIndex
import com.shostakovich.mdeditor.data.vault.VaultRootStorage
import com.shostakovich.mdeditor.ui.screen.SplashContent
import com.shostakovich.mdeditor.ui.theme.MDEditorTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // M12: SplashScreen API。super.onCreate より **前** に呼ぶ必要がある。
        // Android 12+ ではシステムが Activity 起動瞬間にスプラッシュを描画してくれる。
        // それ未満では core-splashscreen が backport で同等の描画を担う。
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // AppAuth の AuthorizationService を初期化 (プロセス内で1回)。
        // applicationContext を渡すので Activity 寿命とは独立。
        AuthManager.init(this)
        // Vault root の永続化ストレージを初期化 (Drive の選択フォルダ fileId 用)
        VaultRootStorage.init(this)
        // M8: Room (検索インデックス) を初期化 + ロード + バックグラウンド同期を起動
        IndexDatabaseProvider.init(this)
        // M8-b: Drive `changes` API の pageToken を保存する SharedPreferences を初期化
        PageTokenStorage.init(this)
        // UI 表示設定 (frontmatter パネル表示など) の SharedPreferences を初期化
        UiPrefsStorage.init(this)
        bootstrapVaultIndex()

        enableEdgeToEdge()
        setContent {
            MDEditorTheme {
                // M12: 起動 UX = システム SplashScreen (アイコン+背景色) →
                //   Compose 擬似スプラッシュ (アイコン + "MDEditor" テキスト) 0.8秒 →
                //   MDEditorApp 本体。間はクロスフェード。
                var showSplash by remember { mutableStateOf(true) }
                LaunchedEffect(Unit) {
                    delay(SPLASH_DURATION_MS)
                    showSplash = false
                }
                AnimatedVisibility(
                    visible = showSplash,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    SplashContent()
                }
                AnimatedVisibility(
                    visible = !showSplash,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    MDEditorApp()
                }
            }
        }
    }

    companion object {
        /** 擬似スプラッシュの表示時間。ユーザがアプリ名を視認できる最短ライン */
        private const val SPLASH_DURATION_MS = 800L
    }

    /**
     * Vault root が選択済みなら、VaultIndex を起動 (DB ロード + バックグラウンド同期)。
     * 未選択なら何もしない (Vault 選択完了後にどこかで kick する想定だが、現状は
     * 検索画面初回起動時に SearchScreen 側でフォールバック)。
     */
    private fun bootstrapVaultIndex() {
        val vaultId = VaultRootStorage.loadVaultId() ?: return
        val vaultName = VaultRootStorage.loadVaultName() ?: return
        // Activity の lifecycleScope を使う。プロセス終了時にキャンセルされる。
        lifecycleScope.launch {
            VaultIndex.start(
                context = this@MainActivity,
                vaultRootId = vaultId,
                vaultRootName = vaultName,
                backgroundScope = lifecycleScope,
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 注意: AuthorizationService は本来 Application スコープで持つべき。
        // 現状は MainActivity onDestroy で dispose しているが、
        // Activity 再生成 (configuration change) のたびに dispose/init するのは無駄。
        // 将来 MDEditorApplication を導入する際にここを整理する。
        if (isFinishing) {
            AuthManager.dispose()
        }
    }
}
