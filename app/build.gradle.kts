import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// local.properties から OAuth secret を読む。
// このファイルは .gitignore 済なので Git にも GitHub にも入らない。
// 新規環境でのセットアップ手順は local.properties.example 参照。
val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val authClientId: String = localProperties.getProperty("auth.clientId")
    ?: error("local.properties に auth.clientId が無い。local.properties.example を参照して設定すること。")
val authClientSecret: String = localProperties.getProperty("auth.clientSecret")
    ?: error("local.properties に auth.clientSecret が無い。local.properties.example を参照して設定すること。")
// Google Desktop OAuth は CLIENT_ID から下記の reverse-domain スキームが導出される。
// 例: "573929485653-abc.apps.googleusercontent.com"
//  -> "com.googleusercontent.apps.573929485653-abc"
// AndroidManifest の <data android:scheme> 値と AuthConfig.REDIRECT_URI の両方で必要。
val authReverseClientId: String =
    "com.googleusercontent.apps." +
        authClientId.removeSuffix(".apps.googleusercontent.com")

// release 署名設定。local.properties に release.* が無ければスキップする
// (debug ビルドは影響なし。release ビルドは未署名 -> インストール不可)。
// keystore ファイル本体は app/keystore/ 配下 (.gitignore 済)。
// storeFile は app/ ディレクトリ起点の相対パスとして解釈される。
val releaseStoreFile: String? = localProperties.getProperty("release.storeFile")
val releaseStorePassword: String? = localProperties.getProperty("release.storePassword")
val releaseKeyAlias: String? = localProperties.getProperty("release.keyAlias")
val releaseKeyPassword: String? = localProperties.getProperty("release.keyPassword")
val hasReleaseSigning: Boolean = releaseStoreFile != null && releaseStorePassword != null &&
    releaseKeyAlias != null && releaseKeyPassword != null

android {
    namespace = "com.shostakovich.mdeditor"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.shostakovich.mdeditor"
        minSdk = 26
        targetSdk = 36
        versionCode = 18
        versionName = "1.13"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // OAuth 値を BuildConfig 経由でランタイムに渡す。
        // AuthConfig.kt はこれらを参照するだけで、ソース直書きはしない。
        buildConfigField("String", "AUTH_CLIENT_ID", "\"$authClientId\"")
        buildConfigField("String", "AUTH_CLIENT_SECRET", "\"$authClientSecret\"")

        // AppAuth が使う Redirect URI のスキーム。
        // Google Desktop タイプ OAuth クライアントは
        //   com.googleusercontent.apps.<reverse-client-id>:/oauth2redirect
        // という Google 予約スキームを受理する。
        // AuthConfig.REVERSE_CLIENT_ID と一致必須。CLIENT_ID から導出して同期させる。
        manifestPlaceholders["appAuthRedirectScheme"] = authReverseClientId
    }

    // release ビルド用署名設定 (local.properties に release.* がある時のみ)。
    // 自前 keystore で長期間 (25年) 有効。debug は ~/.android/debug.keystore 自動。
    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // signingConfigs.release が設定されてれば適用、無ければ未署名 (= 警告のみ)。
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        // BuildConfig.VERSION_NAME / VERSION_CODE を生成 (設定画面でバージョン表示に使う)。
        // AGP 8+ ではデフォルト無効、オプトイン必須。
        buildConfig = true
    }
    // 単体テスト (app/src/test/) で android.util.Log 等の Android API を呼んでも
    // UnsatisfiedLinkError にならず default 値 (Log.* は 0 を返す) で通るようにする。
    // Robolectric を入れる前段の楽な解決策。
    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.appauth)
    // Drive API REST 呼び出し (google-api-services-drive は重いので素の REST + Retrofit を採用)
    implementation(libs.retrofit)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.retrofit.kotlinx.serialization.converter)
    // Markwon: Android 用 Markdown レンダリングライブラリ。Compose には AndroidView 経由で組み込む。
    // 画像は M5 で別途 image plugin を追加するので、ここでは入れない。
    implementation(libs.markwon.core)
    implementation(libs.markwon.ext.tables)
    implementation(libs.markwon.ext.strikethrough)
    implementation(libs.markwon.ext.tasklist)
    implementation(libs.markwon.html)
    implementation(libs.markwon.linkify)
    // M5: 画像表示。SchemeHandler で Drive 取得をカスタムする。
    implementation(libs.markwon.image)
    // 数式 (LaTeX): JLatexMath ベース。inline-parser はインライン $...$ に必須。
    implementation(libs.markwon.ext.latex)
    implementation(libs.markwon.inline.parser)
    // M8: Room (検索インデックス永続化)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    // M12: Android 12+ SplashScreen API (古い API では Theme.SplashScreen backport)
    implementation(libs.androidx.core.splashscreen)
    // TTS 読み上げ: MediaSessionCompat + NotificationCompat.MediaStyle 用。
    // media3-session は Player 実装前提で TTS には過剰なので androidx.media を使う。
    implementation(libs.androidx.media)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // JVM 単体テストで AuthState.jsonSerializeString() (org.json 使用) を実動させる。
    // android.jar の org.json はスタブ (isReturnDefaultValues でも null を返すだけ) なので、
    // 実装入りの本家 org.json をテスト classpath に足す (android.jar より先に解決される)。
    testImplementation(libs.org.json)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}