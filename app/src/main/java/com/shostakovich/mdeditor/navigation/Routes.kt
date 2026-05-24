package com.shostakovich.mdeditor.navigation

/**
 * 画面遷移先のルート定数を集約する object。
 *
 * 注意:
 * - 現状は String ベースの古典的なルート定義。
 *   Navigation Compose 2.8+ では型安全な @Serializable Route も使えるが、
 *   学習段階を絞るため文字列ベースで進める。M6+ で必要になれば移行。
 *
 * - FILE_TREE_PATTERN / EDITOR_PATTERN は NavHost 登録用 (パスパラメータを含む)。
 * - fileTree() / editor() は実際の遷移時に呼ぶ関数 (具体値を埋めた route 文字列を返す)。
 */
object Routes {
    const val LOGIN = "login"
    const val VAULT = "vault"

    /**
     * FileTreeScreen のルート。
     * folderId は Drive の fileId (英数字なので URL エンコード不要)。
     * Vault root を見るときも子フォルダを掘るときも同じ画面、folderId だけが変わる。
     */
    const val FILE_TREE_PATTERN = "file_tree/{folderId}"

    fun fileTree(folderId: String): String = "file_tree/$folderId"

    const val EDITOR_PATTERN = "editor/{fileId}"

    fun editor(fileId: String): String = "editor/$fileId"

    /** Vault 検索画面。引数なし */
    const val SEARCH = "search"

    /** 設定画面。引数なし */
    const val SETTINGS = "settings"
}
