# =====================================================================
#  fund-tracker / update.ps1
#  基準価額（投信ライブラリー）と指数（Yahoo Finance）を取得し
#  data.js を生成する。ブラウザは CORS でこれらを直接取れないため、
#  このスクリプトで取得してローカル JS ファイルに書き出す。
# =====================================================================
$ErrorActionPreference = 'Stop'
$ProgressPreference     = 'SilentlyContinue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$sjis = [System.Text.Encoding]::GetEncoding(932)
$ua   = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"

# --- 対象ファンドと対応指数 ---------------------------------------
$funds = @(
  [ordered]@{
    key       = "orukan"
    name      = "eMAXIS Slim 全世界株式（オール・カントリー）"
    short     = "オルカン"
    isin      = "JP90C000H1T1"
    code      = "0331418A"
    indexName = "MSCI ACWI (ACWI ETF)"
    indexSym  = "ACWI"
  },
  [ordered]@{
    key       = "sp500"
    name      = "eMAXIS Slim 米国株式（S&P500）"
    short     = "S&P500"
    isin      = "JP90C000GKC6"
    code      = "03311187"
    indexName = "S&P 500 指数"
    indexSym  = "%5EGSPC"
  }
)

# --- 基準価額 CSV を取得・パース ----------------------------------
function Get-Nav($isin, $code) {
  $u = "https://toushin-lib.fwg.ne.jp/FdsWeb/FDST030000/csv-file-download?isinCd=$isin&associFundCd=$code"
  $r = Invoke-WebRequest -Uri $u -UseBasicParsing -TimeoutSec 60
  $txt = $sjis.GetString($r.RawContentStream.ToArray())
  $out = [System.Collections.Generic.List[object]]::new()
  foreach ($line in ($txt -split "`r?`n")) {
    if ($line -match '^(\d{4})年(\d{2})月(\d{2})日,([0-9]+),') {
      $d = "{0}-{1}-{2}" -f $matches[1], $matches[2], $matches[3]
      $out.Add(@($d, [int]$matches[4]))
    }
  }
  return $out
}

# --- Yahoo Finance から指数の日次終値を取得 -----------------------
function Get-Index($sym) {
  $u = "https://query1.finance.yahoo.com/v8/finance/chart/$sym`?range=10y&interval=1d"
  $r = Invoke-WebRequest -Uri $u -UseBasicParsing -TimeoutSec 60 -Headers @{ "User-Agent" = $ua }
  $j = $r.Content | ConvertFrom-Json
  $res = $j.chart.result[0]
  $ts  = $res.timestamp
  $cl  = $res.indicators.quote[0].close
  $out = [System.Collections.Generic.List[object]]::new()
  for ($i = 0; $i -lt $ts.Count; $i++) {
    if ($null -ne $cl[$i]) {
      $d = [DateTimeOffset]::FromUnixTimeSeconds([long]$ts[$i]).ToString("yyyy-MM-dd")
      $out.Add(@($d, [math]::Round([double]$cl[$i], 2)))
    }
  }
  return $out
}

# --- 系列を JS 配列文字列へ（[["2020-01-01",123],...]） -----------
function To-JsSeries($list) {
  $sb = [System.Text.StringBuilder]::new()
  [void]$sb.Append('[')
  for ($i = 0; $i -lt $list.Count; $i++) {
    if ($i) { [void]$sb.Append(',') }
    [void]$sb.Append('["' + $list[$i][0] + '",' + $list[$i][1] + ']')
  }
  [void]$sb.Append(']')
  return $sb.ToString()
}

# --- 取得＆組み立て ----------------------------------------------
$parts = [System.Collections.Generic.List[string]]::new()
foreach ($f in $funds) {
  Write-Host ("取得中: " + $f.short + " ...") -NoNewline
  $nav = Get-Nav $f.isin $f.code
  $idx = Get-Index $f.indexSym
  Write-Host (" 基準価額 {0}日 / 指数 {1}日" -f $nav.Count, $idx.Count)

  $block = @"
  "$($f.key)": {
    "name": "$($f.name)",
    "short": "$($f.short)",
    "code": "$($f.code)",
    "indexName": "$($f.indexName)",
    "nav": $(To-JsSeries $nav),
    "index": $(To-JsSeries $idx)
  }
"@
  $parts.Add($block)
}

$now = (Get-Date).ToString("yyyy-MM-dd HH:mm")
$js = @"
// 自動生成ファイル — 直接編集しないでください（update.ps1 が上書きします）
window.DATA = {
  "generatedAt": "$now",
  "funds": {
$([string]::Join(",`n", $parts))
  }
};
"@

$outPath = Join-Path $root "data.js"
[System.IO.File]::WriteAllText($outPath, $js, (New-Object System.Text.UTF8Encoding($false)))
Write-Host ("`n完了: " + $outPath + "  (" + $now + " 時点)")
