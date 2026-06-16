# ファンドウィジェット（Android ホーム画面ウィジェット）

`fund-tracker` の指数データ（S&P500 / オルカン）を、Android の**本物のホーム画面ウィジェット**として表示するアプリ。
Web版（index.html）の CFD 風カードを Canvas で画像描画して RemoteViews に表示している。

## できること
- ホーム画面ウィジェットに CFD 風カードを表示（旗＋名前＋変動率＋ミニチャート＋最新値ピル＋日付軸＋更新情報）
- **小・中・大の3サイズ**をウィジェット一覧から選択（`FundWidgetSmall/Medium/Large` の3 provider、描画は `ChartRenderer.Mode` COMPACT/MEDIUM/FULL で出し分け）
- **タップで S&P500 ⇄ オルカン を切替**
- **ライト/ダークを手動切替**（中・大の右下 ☀／🌙 ボタン。既定はライト＝白）
- **横軸の目盛り線は固定位置**（銘柄を切り替えても線の高さが揃う。ラベルは各銘柄の価格）
- 約6時間ごと＋アプリ起動時に自動更新（オフライン時は前回キャッシュ）
- データは `https://raw.githubusercontent.com/uniboo-apps/fund-tracker/main/data.js` を直接取得（認証不要）

## ビルド（GitHub Actions）
`android/**` を push すると `.github/workflows/build-android.yml` が debug APK をビルドし、
Release `widget-latest` に `fund-widget.apk` を添付する。

## インストール（スマホ）
1. リポジトリの **Releases →「ファンドウィジェット（最新ビルド）」** を開く
2. `fund-widget.apk` をタップ → 「提供元不明のアプリ」を許可してインストール
3. ホーム画面長押し → ウィジェット → 「ファンドウィジェット」を配置

## 構成
- `FundWidgetProvider.kt` … AppWidgetProvider。タップ=銘柄切替 / 右下ボタン=テーマ切替・サイズ算出・更新
- `DataRepo.kt` … data.js を取得・パース（キャッシュ付き）
- `ChartRenderer.kt` … Canvas でカードを Bitmap 描画。明/暗 Palette 対応＋テーマボタン描画（Web版 renderWidget の移植）
- `MainActivity.kt` … 使い方を表示＋起動時にウィジェットを更新

## 操作
- **タップ**＝S&P500 ⇄ オルカン 切替（全サイズ）
- **右下の ☀／🌙 ボタン**＝ライト／ダーク切替（中・大のみ。SharedPreferences に保存、既定ライト）
- 横線は固定位置で両銘柄を揃え、ラベルにその高さの価格を表示

## メモ
- RemoteViews のビットマップ転送上限のため、描画サイズは最大 520×420px にクランプ
- minSdk 26 / targetSdk 34 / Kotlin 1.9 / AGP 8.5 / Gradle 8.7
- debug 署名（個人のサイドロード用途）
