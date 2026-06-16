# ファンドウィジェット（Android ホーム画面ウィジェット）

`fund-tracker` の指数データ（S&P500 / オルカン）を、Android の**本物のホーム画面ウィジェット**として表示するアプリ。
Web版（index.html）の CFD 風カードを Canvas で画像描画して RemoteViews に表示している。

## できること
- ホーム画面ウィジェットに CFD 風カードを表示（旗＋名前＋変動率＋ミニチャート＋最新値ピル＋日付軸＋更新情報）
- **小・中・大の3サイズ**をウィジェット一覧から選択（`FundWidgetSmall/Medium/Large` の3 provider、描画は `ChartRenderer.Mode` COMPACT/MEDIUM/FULL で出し分け）
- **上下スワイプで S&P500 ⇄ オルカン を切替**（`StackView` のカードめくり）
- **タップでアプリ(MainActivity)を起動**
- **ダークモード自動対応**（端末のダーク設定に追従、`ChartRenderer` が明/暗パレットで描画）
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
- `FundWidgetProvider.kt` … AppWidgetProvider。StackView アダプタ設定・タップ起動テンプレート・更新
- `WidgetRemoteViewsService.kt` … StackView の各カード(2ファンド)を供給。サイズ/明暗を判定して Bitmap 化
- `DataRepo.kt` … data.js を取得・パース（キャッシュ付き）
- `ChartRenderer.kt` … Canvas でカードを Bitmap 描画。明/暗パレット対応（Web版 renderWidget の移植）
- `MainActivity.kt` … 使い方を表示＋起動時にウィジェットを更新

## 操作
- **スワイプ（上下）**＝銘柄切替（StackView のカードめくり。任意スワイプ検知は不可なので StackView を採用）
- **タップ**＝アプリ起動
- ダークモードは端末設定に追従

## メモ
- RemoteViews のビットマップ転送上限のため、描画サイズは最大 520×420px にクランプ
- minSdk 26 / targetSdk 34 / Kotlin 1.9 / AGP 8.5 / Gradle 8.7
- debug 署名（個人のサイドロード用途）
