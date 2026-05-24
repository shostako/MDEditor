package com.shostakovich.mdeditor.markdown

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.util.Log
import com.shostakovich.mdeditor.data.image.ImageCache
import com.shostakovich.mdeditor.data.vault.VaultRepository
import io.noties.markwon.image.ImageItem
import io.noties.markwon.image.SchemeHandler
import kotlinx.coroutines.runBlocking

/**
 * Markwon の SchemeHandler 実装。
 *
 * `mdeditor-drive://<fileId>` という URL を受け取り、
 * Drive API でその fileId のバイナリを取ってきて、画面幅にダウンサンプルしてから
 * BitmapDrawable として Markwon に渡す。
 *
 * 動作タイミング:
 *  - Markwon の ImagesPlugin が画像を描画する直前に呼ばれる
 *  - 呼び出しは worker thread 上 (UI スレッドではない) なので runBlocking でブロックして OK
 *  - VaultRepository.downloadBinaryFile は内部で AppAuth.freshAccessToken を suspend で呼ぶので、
 *    coroutine スコープが必要 → runBlocking で同期化
 *
 * パフォーマンス対策 (M5-b):
 *  - ImageCache (LruCache) で同一 fileId のバイト列を再利用
 *  - BitmapFactory の inSampleSize で画面幅に応じて 1/2 / 1/4 ... に縮小
 *  - これにより 439KB の JPG (例: 肉厚マップ) が OOM やフリーズを起こさず表示できる
 *
 * @param maxImageWidthPx 画像の最大幅 (ピクセル)。通常は画面幅 - padding を渡す
 */
class DriveSchemeHandler(
    private val maxImageWidthPx: Int,
) : SchemeHandler() {

    override fun handle(raw: String, uri: Uri): ImageItem {
        val fileId = uri.host
            ?: throw IllegalArgumentException("Missing fileId in URI: $raw")

        // 1. キャッシュ参照
        val bytes = ImageCache.get(fileId) ?: run {
            // 2. miss なら Drive から取得して put
            Log.d(TAG, "MISS fileId=${fileId.take(10)}... → Drive download")
            val fresh = runBlocking { VaultRepository.downloadBinaryFile(fileId) }
            ImageCache.put(fileId, fresh)
            fresh
        }

        // 3. ダウンサンプルしてデコード
        val drawable = decodeAndDownsample(bytes, maxImageWidthPx)
        Log.d(
            TAG,
            "Decoded fileId=${fileId.take(10)}... " +
                "to ${drawable.intrinsicWidth}x${drawable.intrinsicHeight} " +
                "(maxWidth=$maxImageWidthPx)"
        )
        return ImageItem.withResult(drawable)
    }

    override fun supportedSchemes(): Collection<String> = listOf(WikilinkResolver.DRIVE_SCHEME)

    companion object {
        private const val TAG = "DriveSchemeHandler"

        /**
         * バイト列を BitmapFactory でデコードしつつ、**常に画面幅にスケール**する。
         *
         * 手順:
         *  1. inJustDecodeBounds=true で original 幅高さだけ取得
         *  2. inSampleSize (2のべき乗) で粗くデコード (メモリ節約)
         *  3. createScaledBitmap で正確に maxWidth に強制スケール (アスペクト比保持)
         *
         * これにより:
         *  - 大画像 (例: 2000x1500 JPG) → 1パスで 1000x750 にデコード → 1080x810 に拡縮
         *  - 小画像 (例: 200x100 PNG)   → そのままデコード → 1080x540 に拡大 (粗くなるが画面幅に合う)
         *
         * inSampleSize での 1段階目はメモリ・速度のため。最終 createScaledBitmap で画面幅に合わせる。
         */
        private fun decodeAndDownsample(
            bytes: ByteArray,
            maxWidth: Int,
        ): BitmapDrawable {
            // 1パス目: メタデータだけ
            val boundsOpts = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)
            val originalWidth = boundsOpts.outWidth.coerceAtLeast(1)
            val originalHeight = boundsOpts.outHeight.coerceAtLeast(1)

            // 2パス目: inSampleSize でメモリ節約しながらデコード
            val sampleSize = calculateInSampleSize(originalWidth, maxWidth)
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
            }
            val decodedBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
                ?: throw IllegalStateException(
                    "Bitmap decode failed (originalSize=${originalWidth}x$originalHeight)"
                )

            // 3パス: アスペクト比保持で maxWidth に強制スケール
            val targetWidth = maxWidth
            val targetHeight = (originalHeight.toLong() * maxWidth / originalWidth).toInt()
                .coerceAtLeast(1)
            val finalBitmap = if (decodedBitmap.width == targetWidth &&
                decodedBitmap.height == targetHeight
            ) {
                decodedBitmap
            } else {
                Bitmap.createScaledBitmap(decodedBitmap, targetWidth, targetHeight, true)
                    .also {
                        // 中間 Bitmap が別オブジェクトになった場合は recycle
                        if (it != decodedBitmap) decodedBitmap.recycle()
                    }
            }

            // Resources.getSystem() は system theme の Resources。ここでは DPI 解釈に使われるだけ
            return BitmapDrawable(Resources.getSystem(), finalBitmap)
        }

        /**
         * オリジナル幅と最大幅から inSampleSize (2 のべき乗) を計算する。
         * 例: originalWidth=2000, maxWidth=720 → 4 (1/4 縮小で 500 px)
         */
        private fun calculateInSampleSize(originalWidth: Int, maxWidth: Int): Int {
            if (originalWidth <= maxWidth || maxWidth <= 0) return 1
            var sampleSize = 1
            while ((originalWidth / sampleSize) > maxWidth) {
                sampleSize *= 2
            }
            return sampleSize
        }
    }
}
