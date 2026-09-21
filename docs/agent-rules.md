# fund-tracker 固有ルール

本文中のコードパスは repo ルート基準（Markdownリンクは文書位置基準）。
- `data.js` は自動生成。手で編集しない。生成元は `update.ps1` と `.github/workflows/update-data.yml` を参照する。
- 個人の保有額等は公開データへ混ぜない。
- Actions のデータ更新で push 拒否された場合は共通 Git 手順で確認して rebase する。
- Chart.js を使う `index.html` と、生成データとの契約を維持する。`gas/Code.gs` は参考。
