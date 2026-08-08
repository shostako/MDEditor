package com.shostakovich.mdeditor.data.vault

import android.content.Context
import android.util.Log
import com.shostakovich.mdeditor.data.drive.DriveChange
import com.shostakovich.mdeditor.data.drive.DriveFile
import com.shostakovich.mdeditor.data.drive.hasDotFolderSegment
import com.shostakovich.mdeditor.data.drive.isDotEntry
import com.shostakovich.mdeditor.data.index.IndexDatabaseProvider
import com.shostakovich.mdeditor.data.index.MarkdownFileEntity
import com.shostakovich.mdeditor.data.index.PageTokenStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Vault root 配下の **全ての .md ファイル** をメモリ + Room DB に保持する
 * プロセスシングルトン。
 *
 * M8-b 動作:
 *  1. `start()`: DB から既存インデックスを即ロード → state を Built に (起動高速)
 *     ロード結果が空なら初回構築扱い → Building → 全件走査 → DB 保存 → Built
 *  2. ロード後はバックグラウンドで `sync()` 起動。
 *     - pageToken あり → Drive `changes` API で **差分のみ取得** (1500件全件走査を回避)
 *     - pageToken なし → 全件 syncFull (フォールバック)
 *  3. `forceResync()`: ユーザ明示の再インデックス。pageToken を捨てて全件 syncFull。
 *     フォルダリネーム等の差分同期で取りこぼした不整合はここで直す。
 *
 * 状態:
 *  - NotBuilt: 未開始
 *  - Building: 初回構築中 (DB に何も無く全件走査が必要)
 *  - Built(files, isSyncing): 利用可能。isSyncing = true なら裏で差分取得中
 *  - Error: 失敗
 *
 * 注意: メモリ上の files は常に DB と一致するよう保つ (Built 更新時にコピーを差し替え)。
 */
object VaultIndex {
    private const val TAG = "VaultIndex"

    sealed interface IndexState {
        object NotBuilt : IndexState
        data class Building(val progress: Int) : IndexState
        data class Built(
            val files: List<IndexedFile>,
            val isSyncing: Boolean = false,
        ) : IndexState
        data class Error(val message: String) : IndexState
    }

    data class IndexedFile(
        val file: DriveFile,
        val folderPath: String,
    )

    private val _state = MutableStateFlow<IndexState>(IndexState.NotBuilt)
    val state: StateFlow<IndexState> = _state.asStateFlow()

    /** 同時に build/sync が走らないようにする mutex */
    private val mutex = Mutex()

    val allMarkdownFiles: List<IndexedFile>
        get() = (_state.value as? IndexState.Built)?.files.orEmpty()

    val isBuilt: Boolean
        get() = _state.value is IndexState.Built

    /**
     * インデックス利用のエントリポイント。アプリ起動時に MainActivity から呼ぶ。
     *
     *  - DB に既存データあり → 即 Built 状態にして、バックグラウンドで sync を起動
     *  - DB に何も無い → 全件 build (Building 表示、時間かかる)
     */
    suspend fun start(
        context: Context,
        vaultRootId: String,
        vaultRootName: String,
        backgroundScope: CoroutineScope,
    ) {
        mutex.withLock {
            IndexDatabaseProvider.init(context)
            val dao = IndexDatabaseProvider.get().markdownFileDao()
            val existing = dao.getAll()
            if (existing.isNotEmpty()) {
                val files = existing.map { it.toIndexed() }
                _state.value = IndexState.Built(files, isSyncing = true)
                Log.d(TAG, "start: loaded ${files.size} from DB, kicking sync")
                // 同期はバックグラウンドで
                backgroundScope.launch {
                    sync(vaultRootId, vaultRootName)
                }
            } else {
                Log.d(TAG, "start: DB empty, building from scratch")
                build(vaultRootId, vaultRootName)
            }
        }
    }

    /**
     * 全件再走査して DB に書き戻す (初回構築用)。
     * UI はこの間ブロックされる (Building 状態)。
     *
     * **重要**: build 完了直後に getStartPageToken を呼んで保存する。
     * これより前のタイミングで取ると、build 中に行われた変更を取りこぼす。
     */
    private suspend fun build(vaultRootId: String, vaultRootName: String) {
        Log.d(TAG, "build start: vaultRoot=$vaultRootName ($vaultRootId)")
        _state.value = IndexState.Building(progress = 0)
        val collected = mutableListOf<IndexedFile>()
        try {
            walkRecursive(
                folderId = vaultRootId,
                folderPath = vaultRootName,
                collector = collected,
                onProgress = { count ->
                    _state.value = IndexState.Building(progress = count)
                },
            )
            val dao = IndexDatabaseProvider.get().markdownFileDao()
            dao.upsertAll(collected.map { it.toEntity() })
            Log.d(TAG, "build done: ${collected.size} markdown file(s) saved to DB")
            // pageToken 取得 (失敗しても build 自体は成功扱い、次回 sync で全件にフォールバック)
            try {
                val token = VaultRepository.getStartPageToken()
                PageTokenStorage.savePageToken(token)
                Log.d(TAG, "build: saved startPageToken=$token")
            } catch (e: Throwable) {
                Log.w(TAG, "build: getStartPageToken failed (will fall back to full sync next time)", e)
            }
            _state.value = IndexState.Built(collected.toList(), isSyncing = false)
        } catch (e: Throwable) {
            Log.e(TAG, "build failed", e)
            _state.value = IndexState.Error(e.message ?: e::class.simpleName ?: "unknown")
        }
    }

    /**
     * バックグラウンド同期のエントリポイント。
     *
     *  - pageToken あり → changes API で差分のみ取得 (`syncIncremental`)
     *  - pageToken なし、または差分処理が致命的に失敗 → 全件走査 (`syncFull`) にフォールバック
     *
     * UI は古いデータで Built のまま、完了時に新しいリストに差し替える。
     */
    private suspend fun sync(vaultRootId: String, vaultRootName: String) {
        val pageToken = PageTokenStorage.loadPageToken()
        if (pageToken == null) {
            Log.d(TAG, "sync: no pageToken, doing full sync")
            syncFull(vaultRootId, vaultRootName)
            return
        }
        try {
            syncIncremental(vaultRootId, vaultRootName, pageToken)
        } catch (e: Throwable) {
            // 差分同期の事故 (invalid token、parents 辿り失敗等) は全件にフォールバック。
            // changes API は古い token を invalid にすると 404 を返すケースがある。
            Log.w(TAG, "syncIncremental failed, falling back to full sync", e)
            syncFull(vaultRootId, vaultRootName)
        }
    }

    /**
     * Drive `changes` API を用いた差分同期。
     *
     * 流れ:
     *  1. pageToken からスタートして nextPageToken が無くなるまで listChanges をループ
     *  2. 各 change を見て:
     *     - removed = true / file.trashed = true → DB から削除候補
     *     - file あり / .md ファイル → Vault root 配下か判定して upsert 候補
     *     - .md でない / フォルダ → 無視 (フォルダリネームは folderPath が古いままになるが、
     *       手動の forceResync で直す方針)
     *  3. DB に書き戻し
     *  4. newStartPageToken を保存 (次回の起点)
     *
     * Vault 配下判定 (DB に既存 fileId があるかでまず判定、無ければ parents を辿る) は
     * [computeFolderPath] で処理する。
     */
    private suspend fun syncIncremental(
        vaultRootId: String,
        vaultRootName: String,
        startToken: String,
    ) {
        Log.d(TAG, "syncIncremental start: token=$startToken")
        val allChanges = mutableListOf<DriveChange>()
        var currentToken: String? = startToken
        var newStartToken: String? = null
        var pageIndex = 0
        while (currentToken != null && pageIndex < 100) { // 安全弁: 10万件で打ち切り
            val resp = VaultRepository.listChanges(currentToken)
            allChanges += resp.changes
            if (resp.nextPageToken != null) {
                currentToken = resp.nextPageToken
            } else {
                newStartToken = resp.newStartPageToken
                currentToken = null
            }
            pageIndex++
        }
        Log.d(TAG, "syncIncremental: fetched ${allChanges.size} change(s) in $pageIndex page(s)")

        if (allChanges.isEmpty()) {
            // 変更ゼロ。新トークンだけ保存して終わり。
            if (newStartToken != null) PageTokenStorage.savePageToken(newStartToken)
            val current = _state.value
            if (current is IndexState.Built) {
                _state.value = current.copy(isSyncing = false)
            }
            return
        }

        val dao = IndexDatabaseProvider.get().markdownFileDao()
        val existingMap = dao.getAll().associateBy { it.id }

        val diff = classifyChanges(
            changes = allChanges,
            existingMap = existingMap,
            vaultRootId = vaultRootId,
            vaultRootName = vaultRootName,
            getMetadata = { id -> VaultRepository.getFileMetadata(id) },
        )

        if (diff.toUpsert.isNotEmpty()) dao.upsertAll(diff.toUpsert)
        if (diff.toDelete.isNotEmpty()) dao.deleteByIds(diff.toDelete)
        if (newStartToken != null) PageTokenStorage.savePageToken(newStartToken)

        Log.d(
            TAG,
            "syncIncremental done: upserted=${diff.toUpsert.size}, deleted=${diff.toDelete.size}, " +
                "newToken=$newStartToken"
        )

        // メモリ反映: DB から取り直す (toUpsert に既存ファイルの再 upsert も含まれるので safe)
        val refreshed = dao.getAll().map { it.toIndexed() }
        _state.value = IndexState.Built(refreshed, isSyncing = false)
    }

    /**
     * 差分同期の **純粋ロジック部分**: changes リストと既存 DB の状態から
     * upsert / delete 候補を分類する。
     *
     * IO 依存 (parent 辿りの getFileMetadata 呼び出し) は `getMetadata` 引数で受け取る。
     * テスト時はフェイクラムダを注入できる。
     *
     * 規則:
     *  - `changeType != "file"` (例: "drive") は無視
     *  - `removed = true` or `file.trashed = true`: 既存 DB にあれば delete
     *  - `file` が .md でない (フォルダ等): 既存 DB にあれば delete (.md → 他形式リネーム等)
     *  - `file` が .md:
     *      - 既存 DB にある → folderPath 維持で upsert (フォルダリネームは forceResync 任せ)
     *      - 新規 → parents 辿って Vault root 配下なら upsert、そうでなければ無視
     */
    internal suspend fun classifyChanges(
        changes: List<DriveChange>,
        existingMap: Map<String, MarkdownFileEntity>,
        vaultRootId: String,
        vaultRootName: String,
        getMetadata: suspend (String) -> DriveFile,
    ): SyncDiff {
        val existingIds = existingMap.keys
        val toUpsert = mutableListOf<MarkdownFileEntity>()
        val toDelete = mutableListOf<String>()
        // 同一 fileId が複数回 change に出現することがあるので、削除済みを記録して重複排除
        val seenDeleteIds = mutableSetOf<String>()

        for (change in changes) {
            if (change.changeType != null && change.changeType != "file") continue
            val fileId = change.fileId ?: continue
            // 削除 or ゴミ箱
            if (change.removed || change.file?.trashed == true) {
                if (fileId in existingIds && seenDeleteIds.add(fileId)) toDelete += fileId
                continue
            }
            val file = change.file ?: continue
            if (!file.isMarkdown) {
                if (fileId in existingIds && seenDeleteIds.add(fileId)) toDelete += fileId
                continue
            }
            // .md ファイル: Vault root 配下か判定 + folderPath 計算
            val folderPath = computeFolderPath(
                file = file,
                vaultRootId = vaultRootId,
                vaultRootName = vaultRootName,
                existingMap = existingMap,
                getMetadata = getMetadata,
            ) ?: continue
            toUpsert += MarkdownFileEntity(
                id = file.id,
                name = file.name,
                mimeType = file.mimeType,
                folderPath = folderPath,
                modifiedTime = file.modifiedTime,
                size = file.size,
            )
        }
        return SyncDiff(toUpsert = toUpsert, toDelete = toDelete)
    }

    /** classifyChanges の戻り値。upsert / delete 候補を分類した結果。 */
    internal data class SyncDiff(
        val toUpsert: List<MarkdownFileEntity>,
        val toDelete: List<String>,
    )

    /**
     * .md ファイルの folderPath を計算する。
     *
     *  - 既存 DB に同 fileId → 既存の folderPath をそのまま (リネームや移動は次回 forceResync で直す)
     *  - 新規 → parents を辿って Vault root に到達するか判定
     *      - 到達: "Vault > 親A > 親B" 形式で返す
     *      - 到達せず: null (= Vault 外の変更なので無視)
     *
     * parents 辿りは getMetadata を都度呼ぶので新規が大量だと遅くなるが、
     * 通常の日常編集では新規は 1〜数件なので問題なし。
     * テスト時は getMetadata ラムダを差し替えて純粋関数として検証する。
     */
    internal suspend fun computeFolderPath(
        file: DriveFile,
        vaultRootId: String,
        vaultRootName: String,
        existingMap: Map<String, MarkdownFileEntity>,
        getMetadata: suspend (String) -> DriveFile,
    ): String? {
        // ファイル名自体がドット始まりなら対象外 (一覧の判定と揃える)。
        if (file.isDotEntry) return null

        existingMap[file.id]?.let {
            // 既存エントリは親を辿り直さないので、保存済みパスから判定する。
            // 過去のインデックスに入った `.trash` 配下のノートが、更新のたびに
            // 上書き保存されて居座り続けるのを止める。
            return if (it.folderPath.hasDotFolderSegment()) null else it.folderPath
        }

        val parents = file.parents
        if (parents.isEmpty()) return null
        var currentId = parents[0]
        val pathParts = mutableListOf<String>()
        var depth = 0
        while (depth < 30) { // 安全弁
            if (currentId == vaultRootId) {
                return (listOf(vaultRootName) + pathParts.reversed()).joinToString(" > ")
            }
            val parentMeta = try {
                getMetadata(currentId)
            } catch (e: Throwable) {
                Log.w(TAG, "computeFolderPath: getMetadata($currentId) failed", e)
                return null
            }
            // 祖先にドット始まりのフォルダがあれば Vault 配下でも対象外。
            // 親を辿る過程で名前は既に手元にあるので、追加の API 呼び出しは要らない。
            if (parentMeta.isDotEntry) return null
            pathParts += parentMeta.name
            val nextParents = parentMeta.parents
            if (nextParents.isEmpty()) return null
            currentId = nextParents[0]
            depth++
        }
        return null
    }

    /**
     * 全件再走査して DB との差分を更新する (フォールバック用)。
     * `forceResync` および pageToken が無い初回 sync で使う。
     */
    private suspend fun syncFull(vaultRootId: String, vaultRootName: String) {
        Log.d(TAG, "syncFull start")
        try {
            val collected = mutableListOf<IndexedFile>()
            walkRecursive(
                folderId = vaultRootId,
                folderPath = vaultRootName,
                collector = collected,
                onProgress = { /* sync 中は UI に progress 出さない */ },
            )
            val dao = IndexDatabaseProvider.get().markdownFileDao()
            val existingIds = dao.getAll().map { it.id }.toSet()
            val newIds = collected.map { it.file.id }.toSet()
            val removedIds = existingIds - newIds
            dao.upsertAll(collected.map { it.toEntity() })
            if (removedIds.isNotEmpty()) {
                dao.deleteByIds(removedIds.toList())
            }
            // 全件 sync が成功したので、新しい pageToken を採取して保存。
            // これで次回からまた差分のみで済む。
            try {
                val token = VaultRepository.getStartPageToken()
                PageTokenStorage.savePageToken(token)
                Log.d(TAG, "syncFull: saved new startPageToken=$token")
            } catch (e: Throwable) {
                Log.w(TAG, "syncFull: getStartPageToken failed", e)
            }
            Log.d(
                TAG,
                "syncFull done: ${collected.size} total, " +
                    "${removedIds.size} removed (was ${existingIds.size})"
            )
            _state.value = IndexState.Built(collected.toList(), isSyncing = false)
        } catch (e: Throwable) {
            Log.e(TAG, "syncFull failed (keeping existing index)", e)
            val current = _state.value
            if (current is IndexState.Built) {
                _state.value = current.copy(isSyncing = false)
            }
        }
    }

    /**
     * 明示的に再同期。設定画面の「再インデックス」ボタンから呼ばれる想定 (将来)。
     * **必ず全件走査する** (pageToken を捨てて新規取得)。差分同期だと取りこぼした
     * フォルダリネーム等が直らないため、ユーザが明示的に押した時は全件で直す。
     */
    suspend fun forceResync(
        vaultRootId: String,
        vaultRootName: String,
        backgroundScope: CoroutineScope,
    ) {
        val current = _state.value
        if (current is IndexState.Built) {
            _state.value = current.copy(isSyncing = true)
        }
        backgroundScope.launch {
            mutex.withLock {
                // pageToken を捨ててから全件 sync。syncFull の最後で新 token が再採取される。
                PageTokenStorage.clear()
                syncFull(vaultRootId, vaultRootName)
            }
        }
    }

    /**
     * DB と メモリ両方完全リセット。Vault 切替時等に。
     */
    suspend fun clear() {
        IndexDatabaseProvider.get().markdownFileDao().clear()
        PageTokenStorage.clear()
        _state.value = IndexState.NotBuilt
    }

    /** フォルダ走査の再帰本体 */
    private suspend fun walkRecursive(
        folderId: String,
        folderPath: String,
        collector: MutableList<IndexedFile>,
        onProgress: (Int) -> Unit,
    ) {
        val children = VaultRepository.listChildren(folderId)
        for (child in children) {
            // ドット始まりは潜らない / 拾わない。`.trash/` を拾うと、削除済みノートが
            // 一覧には出ないのに検索にだけ現役ノートと同じ見た目で出てきて、
            // そのまま開いて編集・保存までできてしまう。
            if (child.isDotEntry) continue
            when {
                child.isFolder -> {
                    val childPath = "$folderPath > ${child.name}"
                    walkRecursive(child.id, childPath, collector, onProgress)
                }
                child.isMarkdown -> {
                    collector += IndexedFile(file = child, folderPath = folderPath)
                    onProgress(collector.size)
                }
                else -> Unit
            }
        }
    }

    // --- Entity <-> IndexedFile 変換 ---

    private fun IndexedFile.toEntity(): MarkdownFileEntity = MarkdownFileEntity(
        id = file.id,
        name = file.name,
        mimeType = file.mimeType,
        folderPath = folderPath,
        modifiedTime = file.modifiedTime,
        size = file.size,
    )

    private fun MarkdownFileEntity.toIndexed(): IndexedFile = IndexedFile(
        file = DriveFile(
            id = id,
            name = name,
            mimeType = mimeType,
            parents = emptyList(),   // entity に持たせていない (検索用途で不要)
            modifiedTime = modifiedTime,
            size = size,
        ),
        folderPath = folderPath,
    )
}
