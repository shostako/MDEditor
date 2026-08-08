# MDEditor

Google Drive 上の Obsidian Vault をスマホで読み書きする Android 用 Markdown エディタ。

Obsidian Mobile や Markor のような既存アプリの代替ではなく、**Google Drive API を直接叩いて Vault を扱う**個人開発アプリ。Google Drive for desktop で PC と同期している Vault を、スマホからは Drive API 経由で直接読み書きする構成を想定している。

<!-- TODO: スクリーンショットを docs/screenshots/ に置いてここに貼る
![ファイル一覧](docs/screenshots/file_tree.png)
![Markdownプレビュー](docs/screenshots/preview.png)
-->

## 特徴

- **Markdown プレビュー** — Markwon ベース。テーブル / 打ち消し線 / タスクリスト / HTML 対応
- **Obsidian Wikilink** — `[[ノート]]` `[[note|alias]]` `[[folder/note]]` のノート間ジャンプ、`![[画像.png]]` のインライン画像表示（タップでピンチズーム可能なフルスクリーン表示）
- **LaTeX 数式** — `$$...$$` / `$...$` を JLatexMath で描画
- **全文検索** — ファイル名 + 本文。インデックスは Room で永続化し、Drive changes API で差分同期
- **編集・保存** — Drive へ直接 PATCH。未保存変更の破棄確認つき
- **YAML frontmatter** — 本文とは分離して表示 / 生 YAML 編集（トグルで切替）
- **TTS 読み上げ** — 見出し単位のステップナビゲーション方式
- **アウトライン** — 見出しジャンプ
- **兄弟ノート送り** — 同フォルダのノートを一覧と同じ並びで前後にめくる
- **ダークモード** — Material3 テーマに Markdown 描画も追従
- **起動クラッシュ耐性** — 端末修理等で Keystore と暗号化ストレージが不整合になっても、認証情報だけリセットして起動を継続（Drive 上のデータには触れない）

## 技術スタック

| 項目 | 内容 |
|---|---|
| 言語 / UI | Kotlin + Jetpack Compose (Material3) |
| minSdk / targetSdk | 26 (Android 8.0) / 36 (Android 16) |
| 認証 | AppAuth-Android (OAuth 2.0 PKCE, Google Desktop タイプクライアント) |
| Drive アクセス | Drive REST API v3 を Retrofit + OkHttp + kotlinx-serialization で直叩き（SAF・Google API Client 不使用） |
| Markdown | Markwon (core / tables / strikethrough / tasklist / html / linkify / image / ext-latex) |
| 検索インデックス | Room + Drive changes API 差分同期 |
| トークン保存 | EncryptedSharedPreferences (Android Keystore) |

アーキテクチャの決め事: Navigation は文字列ルートの素朴な `composable("route")`、DI フレームワークなし、各 Screen はコールバックを受け取る純粋関数で NavController は `MDEditorApp.kt` に集約。

## セットアップ

自分専用 APK として設計しているため、動かすには **自分の GCP プロジェクト**が要る。

### 1. GCP プロジェクトの準備

1. [Google Cloud Console](https://console.cloud.google.com/) でプロジェクトを作成
2. **Google Drive API** を有効化
3. OAuth 同意画面を構成（External / Testing のままで OK。**自分の Google アカウントをテストユーザーに登録**すること — 忘れると `Error 403: access_denied` になる）
4. OAuth クライアント ID を作成 — **タイプは「デスクトップアプリ」を選ぶこと**。
   Android タイプは Google Sign-In SDK 専用で、AppAuth + Drive scope では `invalid_request` になる（このプロジェクト最大の罠）

### 2. local.properties の設定

```bash
git clone https://github.com/shostako/MDEditor.git
cd MDEditor
cp local.properties.example local.properties
# local.properties を開いて auth.clientId / auth.clientSecret を自分の値に書き換える
```

詳細（release 署名用 keystore の生成手順を含む）は [`local.properties.example`](local.properties.example) のコメント参照。

### 3. ビルド

Android Studio で開いてそのまま Run するか、CLI で:

```bash
./gradlew :app:assembleDebug
```

release ビルド（要 keystore 設定）:

```bash
./gradlew :app:assembleRelease
```

### 4. 使い始め

1. 起動 → 「Google でログイン」→ ブラウザで認証
2. Vault 選択画面で自分の Vault フォルダ名を検索して選択（検索欄の初期値は開発者の Vault 名なので書き換えて使う）
3. ファイル一覧から `.md` をタップして閲覧・編集

## 制約・注意

- **OAuth スコープは `drive`（フルアクセス）**。Google の sensitive scope なので、Play ストア公開には第三者セキュリティ評価（CASA）が必要になる。このアプリはサイドロード用の個人 APK 前提
- OAuth 同意画面が Testing のままなので、登録したテストユーザー（最大 100 人）しかログインできない
- 同時編集の競合検知はない（**最後の保存が勝つ**）。PC と スマホで同じノートを同時に編集しないこと
- Vault の画像添付は Obsidian の「ノートと同じフォルダに添付」モードを想定。Wikilink 画像の解決は同フォルダ内のみ
- 更新インストール時は `versionCode` を上げること（同一だと Android が更新と認識しない）

## 変更履歴

バージョンごとの変更点は [CHANGELOG.md](CHANGELOG.md) を参照。

## ライセンス

[MIT](LICENSE)

アプリアイコンは [Material Symbols](https://fonts.google.com/icons)（Apache License 2.0）の `edit_note` を使用。
