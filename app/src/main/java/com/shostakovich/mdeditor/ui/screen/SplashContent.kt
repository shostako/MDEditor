package com.shostakovich.mdeditor.ui.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shostakovich.mdeditor.R
import com.shostakovich.mdeditor.ui.theme.MDEditorTheme

/**
 * 擬似スプラッシュ画面 Composable。
 *
 * システムの SplashScreen API (アイコン+背景色) と同じ見た目を Compose で再現し、
 * 下に「MDEditor」のアプリ名テキストを追加することで「アイコン + アプリ名」の
 * スプラッシュ UX を実現する。MainActivity から 0.8 秒間表示される。
 *
 * 背景色は ic_launcher_background.xml / colors.xml の splash_background と一致させ、
 * システムスプラッシュ → 擬似スプラッシュの遷移が視覚的に滑らかになるようにする。
 */
@Composable
fun SplashContent() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colorResource(R.color.splash_background)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.size(160.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "MDEditor",
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SplashContentPreview() {
    MDEditorTheme {
        SplashContent()
    }
}
