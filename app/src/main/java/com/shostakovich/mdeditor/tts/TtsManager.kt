package com.shostakovich.mdeditor.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.shostakovich.mdeditor.data.prefs.UiPrefsStorage
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 読み上げの中枢。TextToSpeech エンジンと再生状態を一元管理するプロセスシングルトン。
 *
 * ## 設計メモ
 *  - TextToSpeech にネイティブ pause は無い → 本文を [TtsChunker] でチャンク分割し、
 *    pause = `tts.stop()` + 現在チャンク番号の保持、resume = そこから再 enqueue（疑似 pause）。
 *  - チャンク間ギャップ回避のため、全チャンクを一括 enqueue（先頭 QUEUE_FLUSH + 残り QUEUE_ADD）。
 *    utteranceId に "世代_チャンク番号" を埋め、onStart で進捗更新する。
 *  - `tts.stop()` 後にも進行中 utterance のコールバックが残発火しうる → 世代カウンタ
 *    [generation] で旧世代のコールバックを無視する。
 *  - エンジン init は非同期（onInit 前の speak は失敗）→ Preparing 状態で pending に積む。
 *  - UI ([com.shostakovich.mdeditor.ui.screen.EditorScreen]) と通知サービス ([TtsService]) は
 *    [state] を購読するだけ。画面離脱・回転後も状態が自動復元される。
 *
 * ## スレッドモデル
 *  play/pause/resume/stop/setSpeed はメインスレッドから呼ばれる前提。
 *  UtteranceProgressListener はバインダースレッドで来るため、本処理を [mainHandler] へ
 *  post してメインスレッドに直列化する（世代チェックと状態更新の TOCTOU 競合を防ぐ。
 *  @Volatile は複合操作を原子的にしない）。
 */
object TtsManager {

    sealed interface TtsState {
        data object Idle : TtsState
        data class Preparing(val fileId: String) : TtsState
        data class Playing(val fileId: String, val fileName: String?, val chunk: Int, val total: Int) : TtsState
        data class Paused(val fileId: String, val fileName: String?, val chunk: Int, val total: Int) : TtsState
        data class Error(val message: String) : TtsState
    }

    /** 速度サイクルボタンの順序 */
    val SPEED_STEPS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

    private val _state = MutableStateFlow<TtsState>(TtsState.Idle)
    val state: StateFlow<TtsState> = _state.asStateFlow()

    /** 全状態変更をメインスレッドへ直列化するためのハンドラ */
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var appContext: Context? = null

    // エンジンは初回 play で遅延生成し、プロセス生存中は保持する（init コストが高いため shutdown しない）
    private var tts: TextToSpeech? = null

    @Volatile
    private var engineReady = false

    /** エンジン init 完了待ちの再生要求（init 前の speak は失敗するため積んでおく） */
    private var pendingOnReady: (() -> Unit)? = null

    // ---- 再生位置（currentChunk は onStart=バインダースレッドからも書かれる） ----
    // content はチャンク列 + 見出しチャンク index を併せ持つ。chunks は後者を見ない既存処理向けの別名
    private var content: TtsContent = TtsContent.EMPTY
    private val chunks: List<String> get() = content.chunks

    @Volatile
    private var currentChunk = 0  // 0-based

    private var fileId: String? = null
    private var fileName: String? = null

    /** enqueue のたびに進める世代。旧世代の utterance コールバックは無視する */
    @Volatile
    private var generation = 0

    // ---- AudioFocus ----
    private var focusRequest: AudioFocusRequest? = null

    private val audioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // 喪失したら一時停止。復帰 (GAIN) しても自動再開はしない
                // （読み上げが勝手に喋り出すのは不快。手動 resume で十分）
                if (_state.value is TtsState.Playing) pause()
            }
        }
    }

    /** MainActivity.onCreate から呼ぶ。applicationContext を保持するだけの軽量 init */
    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    /**
     * 読み上げ開始（常に先頭から）。別ノート再生中なら止めて切り替える。
     * 開始後のセクション移動は [stepNext]/[stepPrev] で行う。
     * @return 読み上げを開始した（= TtsService を起動すべき）なら true
     */
    fun play(body: String, fileId: String, fileName: String?): Boolean {
        stopInternal()
        val newContent = TtsContentBuilder.build(body)
        if (newContent.chunks.isEmpty()) return false

        content = newContent
        currentChunk = 0
        this.fileId = fileId
        this.fileName = fileName
        _state.value = TtsState.Preparing(fileId)
        ensureEngine { enqueueFrom(0) }
        return true
    }

    /** Playing → Paused。現在チャンクの先頭まで巻き戻る（チャンク=最大500字なので許容） */
    fun pause() {
        val st = _state.value
        if (st !is TtsState.Playing) return
        generation++  // 以後の残コールバックを無効化
        tts?.stop()
        abandonAudioFocus()
        _state.value = TtsState.Paused(st.fileId, st.fileName, st.chunk, st.total)
    }

    /** Paused → Playing。保持している現在チャンクから再 enqueue */
    fun resume() {
        if (_state.value !is TtsState.Paused) return
        ensureEngine { enqueueFrom(currentChunk) }
    }

    /** 次の見出し（セクション頭）へジャンプして再生。次が無ければ無反応 */
    fun stepNext() {
        val st = _state.value
        if (st !is TtsState.Playing && st !is TtsState.Paused) return
        val target = content.nextHeading(currentChunk) ?: return
        jumpTo(target)
    }

    /**
     * 前の見出し（セクション頭）へジャンプして再生。
     * セクション途中なら現セクション頭、既に頭なら前セクション頭（メディアプレイヤー標準）。
     * 戻り先が無ければ無反応。
     */
    fun stepPrev() {
        val st = _state.value
        if (st !is TtsState.Playing && st !is TtsState.Paused) return
        val target = content.prevHeading(currentChunk) ?: return
        jumpTo(target)
    }

    /** 完全停止。状態は Idle に戻り、TtsService はこれを観測して自殺する */
    fun stop() {
        stopInternal()
        _state.value = TtsState.Idle
    }

    /**
     * 読み上げ速度の変更（永続化込み）。再生中は現在チャンクから即反映で再 enqueue。
     * setSpeechRate は enqueue 済み utterance に効かないため stop → 再投入が必要。
     */
    fun setSpeed(rate: Float) {
        UiPrefsStorage.saveTtsSpeed(rate)
        tts?.setSpeechRate(rate)
        val st = _state.value
        if (st is TtsState.Playing) {
            generation++
            tts?.stop()
            enqueueFrom(currentChunk)
        }
    }

    fun currentSpeed(): Float = UiPrefsStorage.loadTtsSpeed()

    /** 現在速度の次のステップ（速度サイクルボタン用） */
    fun nextSpeed(): Float {
        val cur = currentSpeed()
        val idx = SPEED_STEPS.indexOfFirst { kotlin.math.abs(it - cur) < 0.01f }
        return SPEED_STEPS[(idx + 1).mod(SPEED_STEPS.size)]
    }

    // ---- 内部 ----

    /** 発話とフォーカスを止める（状態は触らない。呼び出し側で遷移させる） */
    private fun stopInternal() {
        generation++
        // エンジン初期化待ちの保留再生も破棄する。これを忘れると「Preparing 中に停止 →
        // 初期化完了後に勝手に読み上げが復活 (しかも FGS なし)」というゾンビ再生になる
        pendingOnReady = null
        tts?.stop()
        abandonAudioFocus()
    }

    /**
     * 指定チャンクへ移動して即再生する（Playing/Paused から呼ばれる）。
     * pause/setSpeed と同じ「generation++ → stop → 再 enqueue」パターン。
     * Paused から呼んでも再生を開始する（ステップ＝聞きたい位置への移動なので再生再開が自然）。
     */
    private fun jumpTo(index: Int) {
        generation++
        tts?.stop()
        currentChunk = index
        ensureEngine { enqueueFrom(index) }
    }

    /** エンジンが使える状態になったら onReady を呼ぶ（既に ready なら即時） */
    private fun ensureEngine(onReady: () -> Unit) {
        if (engineReady) {
            onReady()
            return
        }
        pendingOnReady = onReady
        if (tts != null) return  // init 進行中。onInit が pending を拾う
        val context = appContext ?: run {
            _state.value = TtsState.Error("TTS 未初期化（init 漏れ）")
            return
        }
        tts = TextToSpeech(context) { status -> onEngineInit(status) }
    }

    private fun onEngineInit(status: Int) {
        // onInit のスレッドはエンジン実装依存なのでメインへ直列化する
        mainHandler.post {
            val engine = tts
            if (status != TextToSpeech.SUCCESS || engine == null) {
                failEngineInit(engine, "TTS エンジンの初期化に失敗した")
                return@post
            }
            val langResult = engine.setLanguage(Locale.JAPANESE)
            if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                failEngineInit(engine, "日本語の音声データが無い。設定 > テキスト読み上げ を確認")
                return@post
            }
            engine.setAudioAttributes(audioAttributes)
            engine.setSpeechRate(UiPrefsStorage.loadTtsSpeed())
            engine.setOnUtteranceProgressListener(progressListener)
            engineReady = true
            pendingOnReady?.invoke()
            pendingOnReady = null
        }
    }

    /**
     * エンジン初期化失敗の後始末。tts を残したまま戻ると以後の再生要求が
     * 「init 進行中」と誤認して永遠に待ち続けるため、必ず破棄して再試行可能にする
     * (ユーザーが音声データをインストールした後にもう一度試せる)。
     */
    private fun failEngineInit(engine: TextToSpeech?, message: String) {
        pendingOnReady = null
        engineReady = false
        tts = null
        try {
            engine?.shutdown()
        } catch (_: Throwable) {
            // shutdown 失敗は握りつぶす (これ以上できることがない)
        }
        _state.value = TtsState.Error(message)
    }

    /** index 以降の全チャンクを一括 enqueue（先頭 QUEUE_FLUSH + 残り QUEUE_ADD でギャップレス） */
    private fun enqueueFrom(index: Int) {
        val engine = tts ?: return
        val fid = fileId ?: return
        if (index >= chunks.size) {
            _state.value = TtsState.Idle
            return
        }
        requestAudioFocus()
        val gen = ++generation
        // 先頭の speak が失敗したら以後も期待できない。戻り値を無視すると
        // コールバックが一切来ないまま Playing/通知が永久に残る
        val first = engine.speak(chunks[index], TextToSpeech.QUEUE_FLUSH, null, "${gen}_$index")
        if (first == TextToSpeech.ERROR) {
            abandonAudioFocus()
            _state.value = TtsState.Error("読み上げの開始に失敗した (speak エラー)")
            return
        }
        for (i in (index + 1) until chunks.size) {
            engine.speak(chunks[i], TextToSpeech.QUEUE_ADD, null, "${gen}_$i")
        }
        currentChunk = index
        _state.value = TtsState.Playing(fid, fileName, index + 1, chunks.size)
    }

    /**
     * バインダースレッドで呼ばれるため、本処理は mainHandler へ post して直列化する。
     * 世代チェック → 状態更新の間に main 側の stop()/play() が割り込む TOCTOU を防ぐ。
     */
    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            val (gen, idx) = parseId(utteranceId) ?: return
            mainHandler.post {
                if (gen != generation) return@post  // 旧世代の残発火
                currentChunk = idx
                val fid = fileId ?: return@post
                _state.value = TtsState.Playing(fid, fileName, idx + 1, chunks.size)
            }
        }

        override fun onDone(utteranceId: String?) {
            val (gen, idx) = parseId(utteranceId) ?: return
            mainHandler.post {
                if (gen != generation) return@post
                if (idx == chunks.size - 1) {
                    // 読了
                    abandonAudioFocus()
                    _state.value = TtsState.Idle
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            onError(utteranceId, -1)
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            val (gen, _) = parseId(utteranceId) ?: return
            mainHandler.post {
                if (gen != generation) return@post
                // 後続の QUEUE_ADD 済み utterance も無効化して止める。
                // これをしないと Error 遷移で Service が死んだ後も発話が続く (FGS なしゾンビ)
                generation++
                tts?.stop()
                abandonAudioFocus()
                _state.value = TtsState.Error("読み上げ中にエラーが発生した (code=$errorCode)")
            }
        }

        private fun parseId(id: String?): Pair<Int, Int>? {
            val parts = id?.split('_') ?: return null
            if (parts.size != 2) return null
            val gen = parts[0].toIntOrNull() ?: return null
            val idx = parts[1].toIntOrNull() ?: return null
            return gen to idx
        }
    }

    private fun requestAudioFocus() {
        val am = appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(audioAttributes)
            .setOnAudioFocusChangeListener(focusChangeListener)
            .build()
        focusRequest = request
        am.requestAudioFocus(request)
        // 拒否されても再生は試みる（黙って何も起きないよりエンジン任せの方がマシ）
    }

    private fun abandonAudioFocus() {
        val am = appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        focusRequest?.let { am.abandonAudioFocusRequest(it) }
        focusRequest = null
    }
}
