# ファンド・ウォッチャー（投信の基準価額＆指数）

保有投信（eMAXIS Slim オルカン / S&P500）の**基準価額**と参照指数をグラフ表示し、下落（バーゲン）を可視化する Web アプリ。

## 構成・技術
- `index.html`：アプリ本体（Chart.js）。`data.js` を読んで描画
- `data.js`：**自動生成データ**（基準価額の履歴。公開の市場データのみ＝個人の保有額等は含まない）
- `update.ps1` / `update.bat`：ローカルでデータ更新。**Yahoo Finance** と **投信ライブラリー**（どちらも公開・キー不要）から取得して `data.js` を生成
- `gas/Code.gs`：Google Apps Script 版（参考）

## デプロイ＆データ更新
- `main` に push → GitHub Actions で `<リポジトリ名>.pages.dev` へ自動デプロイ。リポジトリは **public**（組織シークレット使用）。
- **データ更新の流れ**：ローカルで `update.ps1` 実行 → `data.js` 更新 → commit/push → 自動デプロイで反映（将来 GitHub Actions の定期実行に置き換えも可）。

## ルール
- **public なので秘密を置かない**（使用APIは全て公開・キー不要）。
- `data.js` は自動生成。手で編集しない。
