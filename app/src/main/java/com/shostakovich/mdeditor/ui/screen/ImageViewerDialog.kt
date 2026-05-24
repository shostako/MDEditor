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
 * 画像のフルスクリーン拡大表示用 Dialog。
 *
 * 動作:
 *  - 表示時にキャッシュ参照 → ヒットすればそれを使う
 *  - miss なら Drive から取得 (ただし通常は EditorScreen で既にキャッシュ済み)
 *  - フルスクリーンに収まるよう ContentScale.Fit で表示
 *  - ピンチで拡大縮小、ドラッグで移動 (基本的なズーム機能)
 *  - 画像以外の領域 or ダブルタップで閉じる
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
            // フル解像度でデコード (ダウンサンプリングしない、ピンチズームで拡大できるように)
            bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
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
