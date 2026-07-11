# fund-tracker セキュリティ・安定性・リファクタリング修正指示書

対象リポジトリ: `C:\work\Claude\fund-tracker`（GitHub: uniboo-apps/fund-tracker、**public**）
本番URL: https://fund-tracker-8pm.pages.dev/

## この指示書の扱い（重要）

- **この指示書自体（docs/ フォルダ）は、全修正が完了するまでコミットしないこと。**
  public リポジトリなので、未修正の脆弱性一覧を先に公開してはいけない。
  全フェーズ完了後の最終コミットに含めるのは可。
- コミットメッセージは **ASCII（英数字）のみ**（非ASCIIだと Cloudflare Pages デプロイが失敗する）。
- コード変更後は確認なしに `git commit & push` してよい（push で自動デプロイ）。
  push が rejected になったら Actions の data.js 自動更新が原因なので `git pull --rebase origin main` してから再 push。
- コミット後は短縮ハッシュを報告に明記すること。
- デプロイ完了の監視は不要（push できたら作業完了扱い）。ただしフェーズ6の受入確認だけは
  デプロイ反映後に `curl` で行う必要がある（数分待ってから1回確認すれば十分）。

## 現状の構成（前提知識)

- `index.html`（約700行）: アプリ本体。CSS・JS すべてインライン。`data.js` を読んで Chart.js で描画
- `data.js`（約250KB）: 自動生成データ。**手で編集しない**
- `update.py`: CI用データ生成（投信ライブラリー + Yahoo Finance → data.js）
- `update.ps1` / `update.bat`: ローカル用データ生成（update.py と同じ処理の PowerShell 実装）
- `sw.js`: Service Worker（network-first + キャッシュフォールバック）
- `.github/workflows/`: `deploy.yml`（push→Pages デプロイ）、`update-data.yml`（定時データ更新＋デプロイ）、`notion-commit.yml`（触らない）、`build-android.yml`（触らない）
- `android/`: Android ウィジェット。**今回は一切触らない**
- 手動更新機能: 画面右上 🔄 ボタン → GitHub PAT（localStorage キー `ghpat_fund_tracker`）で
  `update-data.yml` を workflow_dispatch → 完了をポーリング → reload

---

# フェーズ1: インライン JS/CSS の外部ファイル化（CSP の前提作業）

後のフェーズ3（CSP ヘッダー）でインラインスクリプトを禁止するための準備。**機能変更はしない。**

1. `index.html` 内の `<style>...</style>` の中身を新規ファイル `style.css` に移し、
   `<link rel="stylesheet" href="style.css">` で読み込む。
2. `index.html` 内の `<script>...</script>`（インラインの大きい方）の中身を新規ファイル `app.js` に移し、
   `<script src="data.js"></script>` の後に `<script src="app.js"></script>` で読み込む
   （data.js が先。defer は使わず現状の実行順を維持するのが安全）。
3. `index.html` 176行付近にある唯一のインライン style 属性
   `<div class="lbl" style="font-size:11px;color:var(--sub);margin:2px 2px 6px">` は
   クラス（例 `.sec-lbl`）に置き換えて style.css へ移す。
   ※ JS から `element.style.xxx =` で設定している箇所（badge の色など）は CSP に抵触しないのでそのままでよい。

**受入条件**: ローカルサーバーで表示・タブ切替・期間切替・チェックボックス・ウィジェットタップ拡大・⚙️モーダルがすべて修正前と同一に動く。

# フェーズ2: Chart.js に SRI を追加

`index.html` の CDN 読み込みを以下に変更（ハッシュは計算済み。そのまま使ってよいが、
疑わしければ自分で再計算して一致確認すること）:

```html
<script src="https://cdn.jsdelivr.net/npm/chart.js@4.4.1/dist/chart.umd.min.js"
        integrity="sha384-9nhczxUqK87bcKHh20fSQcTGD4qq5GhayNYSYWqwBkINBhOfQLg/P5HG5lF1urn4"
        crossorigin="anonymous"></script>
```

再計算方法: `curl -sL <URL> | openssl dgst -sha384 -binary | openssl base64 -A`

**受入条件**: ローカルサーバーでチャートが描画される（SRI 不一致だとスクリプトがブロックされ即分かる）。

# フェーズ3: `_headers` 追加（CSP・セキュリティヘッダー）

リポジトリ直下に `_headers` を新規作成（Cloudflare Pages が自動で読む）:

```
/*
  Content-Security-Policy: default-src 'none'; script-src 'self' https://cdn.jsdelivr.net; style-src 'self'; img-src 'self' data:; connect-src 'self' https://api.github.com https://cdn.jsdelivr.net; manifest-src 'self'; worker-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'
  X-Content-Type-Options: nosniff
  Referrer-Policy: strict-origin-when-cross-origin
  Permissions-Policy: camera=(), microphone=(), geolocation=()
```

注意点:
- `connect-src` に `cdn.jsdelivr.net` が必要（sw.js のプリキャッシュ fetch が SW 自身の CSP で connect-src 判定されるため）。
- `worker-src 'self'` が無いと Service Worker 登録が落ちる。
- フェーズ1が終わっていないと `script-src 'self'` でアプリ全体が動かなくなる。順序厳守。
- `python -m http.server` は `_headers` を返さないため、**CSP の最終確認は本番反映後**に行う（下の受入条件）。

あわせて `sw.js` の fetch ハンドラに同一オリジン外の除外を追加する:
クロスオリジンのリクエストは `https://cdn.jsdelivr.net` のみ処理し、それ以外
（api.github.com など）は `respondWith` せず素通しする。GitHub API のポーリング応答を
キャッシュに溜めないため。あわせてキャッシュ名を `fund-watcher-v2` に更新。

**受入条件**（push・デプロイ反映後）:
1. `curl -sI https://fund-tracker-8pm.pages.dev/ | grep -i content-security` で CSP ヘッダーが返る
2. 本番ページを開いてチャート描画・SW 登録が動く（Codex がブラウザ確認できない場合は、
   受入をユーザーに依頼する項目として報告に明記する）

# フェーズ4: PAT の扱いを安全化（index.html / app.js）

対象: 設定モーダルの案内文・URL共有ボタン・`#pat=` 自動設定（フェーズ1後は app.js 内）。

1. **案内文の変更**（現在 200〜205 行付近の `.note`）:
   - 「Classic Token 推奨」をやめ、**Fine-grained PAT を推奨**する文面に変更:
     - 発行先: https://github.com/settings/personal-access-tokens/new
     - Resource owner: `uniboo-apps`
     - Repository access: **Only select repositories → fund-tracker のみ**
     - Permissions: **Actions → Read and write** のみ
   - 補足として「組織設定で Fine-grained PAT が許可されていない場合のみ Classic Token
     （workflow スコープ）を使う。ただし Classic は全リポジトリに書き込める強い権限なので注意」と記載。
   - 「外部には送信されません」という虚偽の文言を
     「Token はこの端末の localStorage に保存され、GitHub API への認証のみに使われます」に修正。
2. **URLコピー（btnSharePat）に警告を追加**: クリック時にまず
   `confirm('コピーされるURLにはトークンそのものが含まれます。メール・チャット等に貼ると相手側に残ります。スマホのブラウザに直接貼り付ける用途だけに使ってください。続けますか？')`
   を出し、キャンセルなら何もしない。コピー成功メッセージにも「貼り付け先に注意」を一言添える。
3. **`#pat=` 自動設定にバリデーション追加**: 受け取った値が
   `/^(github_pat_|ghp_)[A-Za-z0-9_]{20,255}$/` にマッチしない場合は保存せず、
   `setMsg('URLのトークン形式が不正なため無視しました','#ef4444')` を表示する
   （第三者が任意文字列を植え付けるのを防ぐ）。マッチした場合の動作は現状どおり
   （保存 → `history.replaceState` でハッシュ除去 → メッセージ表示）。

**受入条件**: モーダル文面が新しくなっている。`#pat=xxx`（不正形式）で開くと無視される。
`#pat=github_pat_` + 適当な英数40字で開くと保存される（テスト後に ⚙️→削除で消すこと）。

# フェーズ5: GitHub Actions の secrets スコープ最小化

`.github/workflows/update-data.yml`:

1. ジョブレベルの `env:` から `CLOUDFLARE_API_TOKEN` と `CLOUDFLARE_ACCOUNT_ID` を削除し、
   **最後の「Deploy to Cloudflare Pages」ステップの `env:` に移す**
   （`pip install` や `update.py` 実行ステップから secrets を見えなくする）。
   `FORCE_JAVASCRIPT_ACTIONS_TO_NODE24` はジョブレベルのままでよい。
2. `pip install requests` をバージョン固定する: `pip install requests==2.32.3`
   （それ以降の安定版があればそれで固定してもよい。範囲指定は不可）。

**受入条件**: YAML 構文が正しい（`python -c "import yaml,sys;yaml.safe_load(open('.github/workflows/update-data.yml'))"` などで確認）。
push 後の定時実行またはユーザーの手動 🔄 で動くことは後日確認になるため、報告に「次回実行で要確認」と明記。

# フェーズ6: 本番 Pages から開発ファイルを除外（allowlist デプロイ）

現在 `wrangler pages deploy .` がリポジトリ全体をアップロードしているため、
`update.py`・`AGENTS.md`・`CLAUDE.md`・`android/` などの開発ファイルが
https://fund-tracker-8pm.pages.dev/update.py のように**本番で誰でも取得できる状態**。
（.assetsignore は Pages では効かない。過去に job-map で同種の事故対応済み）

`deploy.yml` と `update-data.yml` の両方のデプロイステップを **allowlist 方式**に変更する。
デプロイ直前に配信対象だけを `_site/` にコピーし、それをデプロイする:

```bash
mkdir -p _site/icons
cp index.html data.js sw.js manifest.json style.css app.js _headers _site/
cp icons/*.png _site/icons/
npx -y wrangler@4 pages deploy _site --project-name="$NAME" --branch=main --commit-dirty=true
```

（フェーズ1で作った style.css / app.js、フェーズ3の _headers を忘れないこと。
今後配信ファイルを追加したらこの cp リストにも追加が必要な旨、両 YAML にコメントを書く）

**受入条件**（push・デプロイ反映後、数分待って確認）:
- `curl -s -o /dev/null -w "%{http_code}" https://fund-tracker-8pm.pages.dev/` → 200
- 同 `/update.py` → 404、`/AGENTS.md` → 404
- 同 `/app.js`、`/style.css`、`/data.js` → 200

# フェーズ7: update.py にデータ縮小ガードを追加

外部ソース（投信ライブラリー / Yahoo）が欠損データを返したとき、短くなった履歴で
data.js を上書き・公開してしまう事故を防ぐ。

`update.py` の data.js 書き込み直前に以下のチェックを追加:

1. 既存 `data.js` が存在する場合、正規表現で旧データの系列ごとの行数を数える
   （例: `"nav": [` に続く `["YYYY-MM-DD",` の個数。キーごとに `"(nav|index)":\s*\[` から
   対応する `]` までを切り出して `\["\d{4}-\d{2}-\d{2}"` を数える実装で十分）。
2. 判定ルール:
   - 各ファンドの `nav`: **新しい件数 < 旧件数 なら失敗**（投信の全履歴 CSV は縮まない）
   - 各 `index` と `fx`: **新しい件数 < 旧件数 - 15 なら失敗**
     （Yahoo は 10 年ローリング窓なので古い日が少しずつ落ちるのは正常）
3. 失敗時は理由（どの系列が旧何件→新何件か）を stderr に出して `sys.exit(1)`。
   data.js は**書き込まない**。
4. 環境変数 `FORCE_WRITE=1` が設定されているときはガードをスキップして書き込む
   （手動リカバリ用）。
5. 既存 data.js が無い・パースできない場合はガードをスキップして通常書き込み。

**受入条件**: ローカルで `python update.py` が正常終了し data.js が更新される。
テスト: data.js をコピー退避 → data.js の nav 系列に手で数行追加（旧件数を水増し）→
`python update.py` が exit 1 で data.js を書き換えないこと → `FORCE_WRITE=1` で書き込めること →
退避を戻さず正常生成された data.js を最終状態にする。

# フェーズ8: フロントの安定性修正（app.js）

1. **localStorage の JSON.parse を保護**: `ft_toggles` 読み込みの
   `JSON.parse(localStorage.getItem(TOGGLE_KEY)||'{}')` を try/catch で包み、
   失敗時は `{}` として続行する（現状は値が壊れているとスクリプト全体が死んで白画面になる）。
2. **系列2点未満のガード**: `renderWidget()` 冒頭で `f.index` が無いか長さ2未満なら
   ウィジェット領域(`#cfd`)を `display:none` にして return。
   `render()` 冒頭で `f.nav` の長さが2未満なら `#updMsg` に
   「データが不足しています。🔄 で更新してください」を出して return。
3. **手動更新ポーリングの精度向上**: 🔄 ボタンの run ポーリング
   （`runs?per_page=1`）を `per_page=5` に変え、取得した runs から
   `event === 'workflow_dispatch'` かつ `created_at >= triggerTime - 30秒` の
   最初の run を対象にする（定時実行 run を誤って監視しないため）。

**受入条件**: ローカルサーバーで
- DevTools 相当の操作で `localStorage.setItem('ft_toggles','{{broken')` を仕込んでリロード
  → 白画面にならず表示される（Codex はブラウザが使えなければ Playwright
  （`C:\work\Claude\temp\node_modules` に導入済みの場合あり、無ければ node で追加）で確認するか、
  最低限 app.js の該当ロジックを目視＋ユーザー確認依頼として報告）
- 通常表示・タブ切替が壊れていない

# フェーズ9: リファクタリング（機能変更なし）

1. **update.ps1 の重複ロジック削除**: データ生成ロジックを update.py に一本化する。
   `update.ps1` は「PATH 再読込 → `python update.py` を呼ぶだけ」の薄いラッパーに書き換える
   （ヘッダーコメントに「実体は update.py」と明記）。`update.bat` の中身を確認し、
   ps1 を呼んでいるならそのまま、独自にデータ生成しているなら同様にラッパー化する。
2. **innerHTML の削減**: データ値を埋め込んでいる箇所を textContent / createElement 組み立てに置換:
   - `cfdUpd`（更新日時表示）
   - `navChg`（前日比）
   - `#tech`（テクニカル指標4セル）
   - `#hint`（指数ヒント）
   - `cfdFx`（ドル円表示）
   見た目（改行・色・太字）は現状と同一に保つこと。
3. **マジックナンバーの定数化**: app.js 冒頭に定数を集める:
   `WIDGET_DAYS=60`, `WIDGET_ZOOM_DAYS=14`, `PERIOD_ALL=99999`,
   `POLL_TRIES=22`, `POLL_INTERVAL_MS=5000`, `POLL_INITIAL_WAIT_MS=6000` など。
4. **（任意）render() の分割**: 余力があれば `renderCards(f)` / `renderTech(f)` /
   `renderChart(f)` に分割。リスクを感じたらスキップしてよい（報告に明記）。

**受入条件**: 全機能が修正前と同一に動く。`python update.py` も正常動作。

---

# ローカル動作確認の方法（必須ルール）

`Start-Process` でサーバーを裏起動して次のコマンドで確認する方式は**禁止**
（プロセスが片付けられて誤判定する）。起動→待受確認→HTTP確認→検証→停止を
**1つの PowerShell コマンド内**で完結させること。テンプレート:

```powershell
$port = 8080
$python = (Get-Command python).Source
$p = Start-Process -FilePath $python -ArgumentList @('-m','http.server',[string]$port,'--bind','127.0.0.1','--directory',(Get-Location).Path) -PassThru -WindowStyle Hidden
try {
  $ready = $false
  for ($i=0; $i -lt 20; $i++) {
    Start-Sleep -Milliseconds 500
    if ($p.HasExited) { break }
    $c=[Net.Sockets.TcpClient]::new(); $t=$c.ConnectAsync('127.0.0.1',$port); $ready=$t.Wait(300); $c.Dispose()
    if ($ready) { break }
  }
  if (-not $ready) { throw "server not ready; exited=$($p.HasExited)" }
  Invoke-WebRequest -UseBasicParsing "http://127.0.0.1:$port/" -TimeoutSec 5
  # ここに追加の検証（Playwright 等）も同一コマンド内で書く
}
finally { if ($p -and -not $p.HasExited) { Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue } }
```

Service Worker がローカル確認を妨げる場合（古いキャッシュが返る等）は、
ポート番号を変えるか Playwright のコンテキストを毎回新規にすれば回避できる。

# コミット計画（例）

フェーズごとに ASCII メッセージでコミットしてよい。例:

1. `Extract inline JS/CSS to app.js and style.css`
2. `Add SRI to Chart.js CDN script`
3. `Add _headers with CSP and tighten service worker scope`
4. `Harden PAT guidance, share warning, and hash validation`
5. `Scope Cloudflare secrets to deploy step and pin requests`
6. `Deploy from allowlisted _site dir to hide dev files`
7. `Add shrink guard to update.py`
8. `Frontend robustness: toggle parse guard, short series guard, poll filter`
9. `Refactor: dedupe update scripts, reduce innerHTML, extract constants`（＋この指示書を含めるならここで）

# 対象外（やらないこと・理由）

- **data.js の古いデータ間引き**: 移動平均（25/75/200日）が日次データ前提のため、
  間引くと全期間表示の MA が崩れる。250KB は現状許容。将来別途検討。
- `android/`、`notion-commit.yml`、`build-android.yml` の変更。
- GAS 版（`gas/Code.gs` がある場合）の変更。

# 最終報告に含めること

- 各フェーズの実施結果とコミットハッシュ
- 受入条件のうち自分で確認できたもの／ユーザー確認に委ねるもの（本番ブラウザでの CSP 違反ゼロ確認、
  スマホでの 🔄 手動更新、PWA 再インストール不要かどうか）の区別
- スキップした任意項目
