package com.shostakovich.mdeditor.ui.screen

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.shostakovich.mdeditor.data.image.ImageCache
import com.shostakovich.mdeditor.data.vault.VaultRepository
import androidx.compose.foundation.Image

/**
 * ビューア用のデコード。
 *
 * ピンチズームで拡大するので基本は原寸のままデコードするが、上限だけ設ける。
 * 12MP のカメラ写真 (4000x3000) を原寸デコードすると ARGB_8888 で約 48MB を
 * 一括確保することになり、低メモリ端末では OutOfMemoryError になる。
 * 通常の Vault 画像 (スクショ・図) は上限に届かないので原寸のまま = 従来どおり。
 *
 * デコードできない形式では null が返る。呼び出し側で必ず判定すること。
 */
private fun decodeForViewer(bytes: ByteArray): android.graphics.Bitmap? {
    // 1パス目: 寸法だけ取る
    val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)
    val longestSide = maxOf(boundsOpts.outWidth, boundsOpts.outHeight)

    // 2パス目: 上限を超える分だけ 2 のべき乗で間引く
    var sampleSize = 1
    while (longestSide > 0 && longestSide / sampleSize > MAX_VIEWER_DIMENSION_PX) {
        sampleSize *= 2
    }
    val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
}

/** ビューアでデコードする画像の最長辺の上限 (px)。 */
private const val MAX_VIEWER_DIMENSION_PX = 4096

/**
 * 画像のフルスクリーン拡大表示用 Dialog。
 *
 * 動作:
 *  - 表示時にキャッシュ参照 → ヒットすればそれを使う
 *  - miss なら Drive から取得 (ただし通常は EditorScreen で既にキャッシュ済み)
 *  - フルスクリーンに収まるよう ContentScale.Fit で表示
 *  - ピンチで拡大縮小、ドラッグで移動 (基本的なズーム機能)
 *  - 画像以外の領域 or ダブルタップで閉じる
 *
 * 導線は 2つ: EditorScreen の `![[画像.png]]` タップと、FileTreeScreen の一覧タップ。
 * 後者は Markwon の描画を経由しないので、デコード可否の判定をここで自前で持つ必要がある。
 *
 * Dialog は usePlatformDefaultWidth = false にしてフルスクリーンに広げる。
 */
@Composable
fun ImageViewerDialog(
    fileId: String,
    onDismiss: () -> Unit,
) {
    var bitmap by remember(fileId) {
        mutableStateOf<android.graphics.Bitmap?>(null)
    }
    var errorMessage by remember(fileId) { mutableStateOf<String?>(null) }

    // ピンチズーム / パン用の transform 状態
    var scale by remember(fileId) { mutableFloatStateOf(1f) }
    var offsetX by remember(fileId) { mutableFloatStateOf(0f) }
    var offsetY by remember(fileId) { mutableFloatStateOf(0f) }

    LaunchedEffect(fileId) {
        try {
            // キャッシュ参照 (EditorScreen で先に Drive から取得済みのはず)
            val bytes = ImageCache.get(fileId)
                ?: VaultRepository.downloadBinaryFile(fileId).also {
                    ImageCache.put(fileId, it)
                }
            // decodeByteArray は非対応フォーマット (svg 等) に対して例外ではなく null を返す。
            // 描画側は bitmap == null を「読み込み中」として扱うので、null のまま置くと
            // スピナーが永久に回る。ここで明示的にエラーへ落とす。
            bitmap = decodeForViewer(bytes)
                ?: throw IllegalStateException("この形式の画像は表示できない")
        } catch (e: Throwable) {
            errorMessage = "画像読み込み失敗: ${e.message ?: e::class.simpleName}"
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(fileId) {
                    // ダブルタップで閉じる、シングルタップでも閉じる (シンプル動作)
                    detectTapGestures(
                        onTap = { onDismiss() }
                    )
                }
                .pointerInput(fileId) {
                    // ピンチ + ドラッグ
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(0.5f, 8f)
                        offsetX += pan.x
                        offsetY += pan.y
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            when {
                errorMessage != null -> {
                    Text(
                        text = errorMessage!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                bitmap == null -> {
                    CircularProgressIndicator(color = Color.White)
                }
                else -> {
                    Image(
                        bitmap = bitmap!!.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offsetX,
                                translationY = offsetY,
                            ),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
        }
    }
}
