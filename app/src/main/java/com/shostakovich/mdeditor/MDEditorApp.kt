package com.shostakovich.mdeditor

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.shostakovich.mdeditor.auth.AuthManager
import com.shostakovich.mdeditor.data.vault.VaultRootStorage
import com.shostakovich.mdeditor.navigation.Routes
import com.shostakovich.mdeditor.ui.screen.EditorScreen
import com.shostakovich.mdeditor.ui.screen.FileTreeScreen
import com.shostakovich.mdeditor.ui.screen.LoginScreen
import com.shostakovich.mdeditor.ui.screen.SearchScreen
import com.shostakovich.mdeditor.ui.screen.SettingsScreen
import com.shostakovich.mdeditor.ui.screen.VaultScreen

/**
 * アプリ全体のトップレベル Composable。
 * NavController を持ち、画面遷移ロジックを集約する。
 *
 * 設計方針:
 * - 各 Screen は遷移コールバック (() -> Unit) を受け取る純粋関数。
 *   NavController は Screen 内に持ち込まず、ここで集約管理する。
 * - これで Screen 単体の Preview / テストが容易になる。
 *
 * 起動時の遷移先 (startDestination) は 3 段階で決定:
 *  1. 未認証               → LOGIN
 *  2. 認証済 & Vault未選択 → VAULT (root フォルダ選択)
 *  3. 認証済 & Vault選択済 → file_tree/{vaultRootId} (直接ファイル一覧へ)
 */
@Composable
fun MDEditorApp() {
    val navController = rememberNavController()

    // M3: 3段階の startDestination 判定。
    // AuthManager.init / VaultRootStorage.init は MainActivity.onCreate で実行済み。
    val startDestination: String = when {
        !AuthManager.isAuthorized() -> Routes.LOGIN
        !VaultRootStorage.isSelected() -> Routes.VAULT
        else -> Routes.fileTree(VaultRootStorage.loadVaultId()!!)
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Routes.LOGIN) {
                LoginScreen(
                    onLoginSuccess = {
                        // ログイン完了後は LOGIN をスタックから除去して次画面へ。
                        // v1.12: 認証リセット復旧後の再ログインでは Vault 選択 (別ストレージ) が
                        // 生き残っているので、VAULT を飛ばして直接ファイル一覧へ行く。
                        val vaultId = VaultRootStorage.loadVaultId()
                        val next = if (vaultId != null) Routes.fileTree(vaultId) else Routes.VAULT
                        navController.navigate(next) {
                            popUpTo(Routes.LOGIN) { inclusive = true }
                        }
                    }
                )
            }
            composable(Routes.VAULT) {
                VaultScreen(
                    onVaultSelected = { folderId, _ ->
                        // Vault 選択完了 → file_tree に遷移、VAULT はスタックから除去
                        navController.navigate(Routes.fileTree(folderId)) {
                            popUpTo(Routes.VAULT) { inclusive = true }
                        }
                    }
                )
            }
            composable(Routes.FILE_TREE_PATTERN) { backStackEntry ->
                val folderId = backStackEntry.arguments?.getString("folderId").orEmpty()
                FileTreeScreen(
                    folderId = folderId,
                    onFolderClick = { childId ->
                        // 子フォルダを開く: 同じ画面パターンで別 backStackEntry を積む
                        // → 戻るボタンで親フォルダに戻れる
                        navController.navigate(Routes.fileTree(childId))
                    },
                    onMarkdownClick = { fileId ->
                        navController.navigate(Routes.editor(fileId))
                    },
                    onSearchClick = { navController.navigate(Routes.SEARCH) },
                    onBack = { navController.popBackStack() },
                    // 親フォルダへ (空間軸の Up)。新規 push なので 戻る (Back) で前画面に戻れる。
                    onUpClick = { parentId ->
                        navController.navigate(Routes.fileTree(parentId))
                    },
                    onSettingsClick = { navController.navigate(Routes.SETTINGS) },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onLoggedOut = {
                        // 全スタック削除 → LoginScreen から再スタート
                        navController.navigate(Routes.LOGIN) {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                )
            }
            composable(Routes.SEARCH) {
                SearchScreen(
                    onFileClick = { fileId -> navController.navigate(Routes.editor(fileId)) },
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Routes.EDITOR_PATTERN) { backStackEntry ->
                val fileId = backStackEntry.arguments?.getString("fileId").orEmpty()
                EditorScreen(
                    fileId = fileId,
                    onBack = { navController.popBackStack() },
                    // M9: ノートリンク `[[note]]` タップで別ノートを push。
                    // backstack で前のノートに戻れる (戻るボタン)。
                    onNoteClick = { targetFileId ->
                        navController.navigate(Routes.editor(targetFileId))
                    },
                    // 「📁↑」: このノートが属するフォルダの一覧へ (空間軸 Up)。
                    onUpClick = { folderId ->
                        navController.navigate(Routes.fileTree(folderId))
                    },
                    // 同じフォルダの前/次ノートへ。現在の editor を pop して新 editor を push する
                    // = 置換なので、連続で送ってもバックスタックは積まれない (戻る一発で一覧へ)。
                    // Wikilink で積んだ下層 editor は残るので、戻るとそのノートへ帰れる。
                    onNavigateSibling = { sibId ->
                        navController.navigate(Routes.editor(sibId)) {
                            popUpTo(Routes.EDITOR_PATTERN) { inclusive = true }
                        }
                    },
                )
            }
        }
    }
}
