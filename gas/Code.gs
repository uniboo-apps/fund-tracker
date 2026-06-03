/**********************************************************************
 * ファンド・ウォッチャー (Google Apps Script 版)
 *
 *  - スマホからURLを開くだけ。PC不要。
 *  - Googleのサーバーが毎晩自動でデータ取得＆下落をメール通知。
 *
 *  【初回セットアップ】
 *   1. script.google.com で新規プロジェクト → このコードを貼り付け
 *   2. メニュー「実行」で setup を1回実行（権限を許可）
 *      → 毎朝7時の自動チェック＆メール通知が有効になる
 *   3. 「デプロイ」→「新しいデプロイ」→ 種類「ウェブアプリ」
 *      実行ユーザー=自分 / アクセス=自分のみ → デプロイ
 *      → 出てきたURLをスマホのホーム画面に追加
 **********************************************************************/

// 通知先メール（必要なら変更）
const ALERT_EMAIL = 'e091228@gmail.com';

// 対象ファンドと対応指数
const FUNDS = [
  { key:'orukan', name:'eMAXIS Slim 全世界株式（オール・カントリー）', short:'オルカン',
    isin:'JP90C000H1T1', code:'0331418A', indexName:'MSCI ACWI (ACWI ETF)', indexSym:'ACWI' },
  { key:'sp500',  name:'eMAXIS Slim 米国株式（S&P500）', short:'S&P500',
    isin:'JP90C000GKC6', code:'03311187', indexName:'S&P 500 指数', indexSym:'%5EGSPC' }
];

// バーゲン度の閾値（過去最高値からの下落率）。HTML側のbadgeForと一致させること。
// band: 0高値圏 1小休止 2押し目 3バーゲン 4大バーゲン
function bandOf_(dd){
  if(dd > -2)  return 0;
  if(dd > -4)  return 1;
  if(dd > -7)  return 2;
  if(dd > -12) return 3;
  return 4;
}
const BAND_LABEL = ['高値圏','小休止🟢','押し目🟡（買い検討）','バーゲン🟠（買い増し有力）','大バーゲン🔥'];

// ===================== Web ページ =====================
function doGet(){
  const data = getData_();
  const html = HTML_TEMPLATE.replace('__DATA__', function(){ return JSON.stringify(data); });
  return HtmlService.createHtmlOutput(html)
    .setTitle('ファンド・ウォッチャー')
    .addMetaTag('viewport','width=device-width, initial-scale=1.0');
}

// ===================== データ取得 =====================
function getData_(){
  const cached = cacheGet_('funddata');
  if(cached){ try{ return JSON.parse(cached); }catch(e){} }
  const data = fetchAll_();
  cachePut_('funddata', JSON.stringify(data));
  return data;
}

function fetchAll_(){
  const funds = {};
  FUNDS.forEach(function(f){
    funds[f.key] = {
      name:f.name, short:f.short, code:f.code, indexName:f.indexName,
      nav:   fetchNav_(f.isin, f.code),
      index: fetchIndex_(f.indexSym)
    };
  });
  return { generatedAt: Utilities.formatDate(new Date(),'Asia/Tokyo','yyyy-MM-dd HH:mm'), funds:funds };
}

// 基準価額CSV（Shift-JIS）を取得・パース
function fetchNav_(isin, code){
  const url = 'https://toushin-lib.fwg.ne.jp/FdsWeb/FDST030000/csv-file-download?isinCd='+isin+'&associFundCd='+code;
  try{
    const resp = UrlFetchApp.fetch(url, { muteHttpExceptions:true });
    const txt  = resp.getBlob().getDataAsString('Shift_JIS');
    const out  = [];
    txt.split(/\r?\n/).forEach(function(line){
      const m = line.match(/^(\d{4})年(\d{2})月(\d{2})日,(\d+),/);
      if(m) out.push([m[1]+'-'+m[2]+'-'+m[3], Number(m[4])]);
    });
    return out;
  }catch(e){ return []; }
}

// Yahoo Finance から指数の日次終値（Google IPが弾かれる場合は空配列で続行）
function fetchIndex_(sym){
  const url = 'https://query1.finance.yahoo.com/v8/finance/chart/'+sym+'?range=10y&interval=1d';
  try{
    const resp = UrlFetchApp.fetch(url, { muteHttpExceptions:true,
      headers:{ 'User-Agent':'Mozilla/5.0 (Windows NT 10.0; Win64; x64)' } });
    if(resp.getResponseCode() !== 200) return [];
    const j = JSON.parse(resp.getContentText());
    const res = j.chart.result[0];
    const ts = res.timestamp, cl = res.indicators.quote[0].close;
    const out = [];
    for(let i=0;i<ts.length;i++){
      if(cl[i] != null){
        const d = Utilities.formatDate(new Date(ts[i]*1000),'America/New_York','yyyy-MM-dd');
        out.push([d, Math.round(cl[i]*100)/100]);
      }
    }
    return out;
  }catch(e){ return []; }
}

// ===================== 下落アラート（毎朝の自動実行） =====================
function checkAndAlert(){
  const data = fetchAll_();
  cachePut_('funddata', JSON.stringify(data));   // ページ用キャッシュも更新

  const props = PropertiesService.getScriptProperties();
  const msgs = [];
  Object.keys(data.funds).forEach(function(k){
    const f = data.funds[k];
    const nav = f.nav.map(function(p){ return p[1]; });
    if(nav.length < 2) return;
    let peak = 0; nav.forEach(function(v){ if(v>peak) peak=v; });
    const now = nav[nav.length-1];
    const dd  = (now/peak - 1) * 100;
    const band = bandOf_(dd);
    const prev = Number(props.getProperty('band_'+k) || '0');

    // 「押し目」以上に、かつ前回より深い段階へ入ったときだけ通知（鳴りっぱなし防止）
    if(band >= 2 && band > prev){
      msgs.push('【'+f.short+'】 '+BAND_LABEL[band]+'\n'+
                '  現在 '+now.toLocaleString()+'円 / 過去最高値 '+peak.toLocaleString()+'円\n'+
                '  最高値からの下落率 '+dd.toFixed(1)+'%');
    }
    props.setProperty('band_'+k, String(band));
  });

  if(msgs.length){
    let url = '';
    try{ url = ScriptApp.getService().getUrl() || ''; }catch(e){}
    MailApp.sendEmail(ALERT_EMAIL, '📉 ファンド下落アラート',
      msgs.join('\n\n') + (url ? '\n\n▼グラフを確認\n'+url : '') +
      '\n\n（基準価額は前営業日終値ベース。下落＝買い場の候補です）');
  }
}

// ===================== セットアップ =====================
function setup(){
  // 既存の同名トリガーを掃除してから作成
  ScriptApp.getProjectTriggers().forEach(function(t){
    if(t.getHandlerFunction() === 'checkAndAlert') ScriptApp.deleteTrigger(t);
  });
  ScriptApp.newTrigger('checkAndAlert').timeBased().everyDays(1).atHour(7).create();
  // 初回のband状態を現在値で初期化（いきなり大量通知を防ぐ）
  const data = fetchAll_();
  const props = PropertiesService.getScriptProperties();
  Object.keys(data.funds).forEach(function(k){
    const nav = data.funds[k].nav.map(function(p){ return p[1]; });
    if(nav.length < 2) return;
    let peak=0; nav.forEach(function(v){ if(v>peak) peak=v; });
    props.setProperty('band_'+k, String(bandOf_((nav[nav.length-1]/peak-1)*100)));
  });
  cachePut_('funddata', JSON.stringify(data));
  Logger.log('セットアップ完了：毎朝7時に自動チェックします。');
}

// 動作確認用：今すぐ閾値チェック＆（条件を満たせば）メール送信
function testRun(){ checkAndAlert(); }

// ===================== キャッシュ（100KB制限を分割で回避） =====================
function cachePut_(key, str){
  const c = CacheService.getScriptCache();
  const size = 90000, n = Math.ceil(str.length/size), parts = {};
  for(let i=0;i<n;i++) parts[key+'_'+i] = str.substr(i*size, size);
  parts[key+'_n'] = String(n);
  c.putAll(parts, 21600); // 6時間
}
function cacheGet_(key){
  const c = CacheService.getScriptCache();
  const nv = c.get(key+'_n'); if(!nv) return null;
  const n = Number(nv), keys = [];
  for(let i=0;i<n;i++) keys.push(key+'_'+i);
  const got = c.getAll(keys);
  let s = '';
  for(let i=0;i<n;i++){ if(got[key+'_'+i] == null) return null; s += got[key+'_'+i]; }
  return s;
}

// ===================== 表示HTML =====================
const HTML_TEMPLATE = `<!DOCTYPE html>
<html lang="ja"><head><meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>ファンド・ウォッチャー</title>
<script src="https://cdn.jsdelivr.net/npm/chart.js@4.4.1/dist/chart.umd.min.js"><\/script>
<style>
  :root{--bg:#0f1419;--panel:#1a2029;--panel2:#222a35;--line:#2c3540;--txt:#e6edf3;--sub:#8b98a5;--accent:#3b82f6;--idx:#22d3ee}
  *{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
  body{margin:0;background:var(--bg);color:var(--txt);font-family:"Hiragino Kaku Gothic ProN","Meiryo",system-ui,sans-serif;line-height:1.5}
  .wrap{max-width:880px;margin:0 auto;padding:14px}
  h1{font-size:17px;margin:4px 0 2px}
  .updated{color:var(--sub);font-size:12px;margin-bottom:14px}
  .tabs{display:flex;gap:8px;margin-bottom:14px}
  .tab{flex:1;padding:11px;border:1px solid var(--line);background:var(--panel);color:var(--sub);border-radius:10px;font-size:14px;font-weight:600;text-align:center}
  .tab.active{background:var(--accent);color:#fff;border-color:var(--accent)}
  .fundname{font-size:12px;color:var(--sub);margin-bottom:10px}
  .cards{display:grid;grid-template-columns:1fr 1fr;gap:10px;margin-bottom:14px}
  .card{background:var(--panel);border:1px solid var(--line);border-radius:12px;padding:14px}
  .card .lbl{font-size:11px;color:var(--sub);margin-bottom:4px}
  .card .big{font-size:25px;font-weight:700}
  .card .sub{font-size:13px;margin-top:2px}
  .up{color:#22c55e}.down{color:#ef4444}
  .gauge{grid-column:1 / -1;text-align:center;padding:16px}
  .gauge .badge{display:inline-block;padding:6px 16px;border-radius:999px;font-size:19px;font-weight:700;margin-bottom:8px}
  .gauge .det{font-size:13px;color:var(--sub)}
  .tech{display:grid;grid-template-columns:repeat(4,1fr);gap:8px;margin-bottom:16px}
  .tech .t{background:var(--panel);border:1px solid var(--line);border-radius:10px;padding:10px 6px;text-align:center}
  .tech .t .tl{font-size:10px;color:var(--sub)}
  .tech .t .tv{font-size:18px;font-weight:700;margin-top:3px}
  .tech .t .ts{font-size:10px;margin-top:3px;color:var(--sub)}
  @media(max-width:520px){.tech{grid-template-columns:repeat(2,1fr)}}
  .periods{display:flex;gap:6px;margin-bottom:10px;flex-wrap:wrap}
  .pbtn{padding:8px 13px;border:1px solid var(--line);background:var(--panel);color:var(--sub);border-radius:8px;font-size:13px}
  .pbtn.active{background:var(--panel2);color:var(--txt);border-color:var(--accent)}
  .chartbox{background:var(--panel);border:1px solid var(--line);border-radius:12px;padding:10px 8px 4px;position:relative;height:340px}
  .toggles{display:flex;gap:14px;flex-wrap:wrap;margin:12px 2px;font-size:13px;color:var(--sub)}
  .hint{background:var(--panel);border:1px solid var(--line);border-left:3px solid var(--idx);border-radius:10px;padding:12px 14px;margin-top:14px;font-size:13px}
  .hint b{color:var(--idx)}
  .foot{color:var(--sub);font-size:11px;margin-top:18px;line-height:1.7}
</style></head>
<body><div class="wrap">
  <h1>📉 ファンド・ウォッチャー</h1>
  <div class="updated" id="updated"></div>
  <div class="tabs" id="tabs"></div>
  <div class="fundname" id="fundname"></div>
  <div class="cards">
    <div class="card"><div class="lbl">最新 基準価額</div><div class="big" id="navNow"></div><div class="sub" id="navChg"></div></div>
    <div class="card"><div class="lbl">過去最高値</div><div class="big" id="ath"></div><div class="sub" id="athDate"></div></div>
    <div class="card gauge"><div class="lbl">バーゲン度（過去最高値からの下落率）</div><div class="badge" id="badge"></div><div class="det" id="gaugeDet"></div></div>
  </div>
  <div class="lbl" style="font-size:11px;color:var(--sub);margin:2px 2px 6px">テクニカル指標（買い場サイン）</div>
  <div class="tech" id="tech"></div>
  <div class="periods" id="periods"></div>
  <div class="chartbox"><canvas id="chart"></canvas></div>
  <div class="toggles">
    <label><input type="checkbox" id="tMa" checked> 移動平均線(25/75/200日)</label>
    <label><input type="checkbox" id="tIdx" checked> 指数を重ねる(形を比較)</label>
  </div>
  <div class="hint" id="hint"></div>
  <div class="foot">
    ・基準価額は前営業日終値。指数(米国市場)の動きはおおむね<b>翌営業日の基準価額</b>に反映。<br>
    ・指数は通貨が異なる(USD)ため、グラフでは形の比較用に正規化(為替差は含まず)。<br>
    ・データは毎朝Googleが自動取得。下落が「押し目」以上になると登録メールに通知が届きます。
  </div>
</div>
<script>
const DATA = __DATA__;
const COLORS={nav:'#3b82f6',ma1:'#f59e0b',ma2:'#a855f7',ma3:'#64748b',idx:'#22d3ee'};
let curKey=null, curDays=365, chart=null;
const fmt=n=>n.toLocaleString('ja-JP');
const pct=n=>(n>=0?'+':'')+n.toFixed(2)+'%';
function sma(v,n){const o=new Array(v.length).fill(null);let s=0;for(let i=0;i<v.length;i++){s+=v[i];if(i>=n)s-=v[i-n];if(i>=n-1)o[i]=s/n;}return o;}
function rsi(v,p=14){if(v.length<p+1)return null;let g=0,l=0;for(let i=1;i<=p;i++){const d=v[i]-v[i-1];if(d>=0)g+=d;else l-=d;}let aG=g/p,aL=l/p;for(let i=p+1;i<v.length;i++){const d=v[i]-v[i-1];aG=(aG*(p-1)+(d>0?d:0))/p;aL=(aL*(p-1)+(d<0?-d:0))/p;}if(aL===0)return 100;return 100-100/(1+aG/aL);}
function idxAt(s,ds){let lo=0,hi=s.length-1,a=null;while(lo<=hi){const m=(lo+hi)>>1;if(s[m][0]<=ds){a=s[m][1];lo=m+1;}else hi=m-1;}return a;}
function badgeFor(dd){
  if(dd> -2)  return ['高値圏','#334155','#cbd5e1','今は高め。慌てず待ち。'];
  if(dd> -4)  return ['小休止🟢','#14532d','#86efac','軽い調整。様子見。'];
  if(dd> -7)  return ['押し目🟡','#713f12','#fde047','押し目。買い検討ゾーン。'];
  if(dd> -12) return ['バーゲン🟠','#7c2d12','#fdba74','しっかり下落。買い増し有力。'];
  return ['大バーゲン🔥','#7f1d1d','#fca5a5','歴史的な下落水準。仕込み時。'];
}
function setupTabs(){const t=document.getElementById('tabs');Object.keys(DATA.funds).forEach((k,i)=>{const b=document.createElement('div');b.className='tab'+(i===0?' active':'');b.textContent=DATA.funds[k].short;b.onclick=()=>{document.querySelectorAll('.tab').forEach(x=>x.classList.remove('active'));b.classList.add('active');curKey=k;render();};t.appendChild(b);if(i===0)curKey=k;});}
function setupPeriods(){const o=[['3ヶ月',90],['6ヶ月',180],['1年',365],['3年',1095],['全期間',99999]];const box=document.getElementById('periods');o.forEach(([lbl,d])=>{const b=document.createElement('div');b.className='pbtn'+(d===curDays?' active':'');b.textContent=lbl;b.onclick=()=>{document.querySelectorAll('.pbtn').forEach(x=>x.classList.remove('active'));b.classList.add('active');curDays=d;render();};box.appendChild(b);});}
function render(){
  const f=DATA.funds[curKey];
  const hasIdx=f.index && f.index.length>1;
  document.getElementById('fundname').textContent=f.name+'（協会コード '+f.code+'）';
  const dates=f.nav.map(p=>p[0]), vals=f.nav.map(p=>p[1]);
  const li=vals.length-1, now=vals[li], prev=vals[li-1];
  const dchg=now-prev, dpct=dchg/prev*100;
  let ath=-Infinity,athi=0; for(let i=0;i<vals.length;i++) if(vals[i]>ath){ath=vals[i];athi=i;}
  const ddAth=(now/ath-1)*100;
  document.getElementById('navNow').textContent=fmt(now)+'円';
  const ce=document.getElementById('navChg'); ce.innerHTML='前日比 '+(dchg>=0?'+':'')+fmt(dchg)+'円 ('+pct(dpct)+')'; ce.className='sub '+(dchg>=0?'up':'down');
  document.getElementById('ath').textContent=fmt(ath)+'円';
  document.getElementById('athDate').textContent=dates[athi]+' 時点';
  const [lb,bg,fg,desc]=badgeFor(ddAth);
  const bd=document.getElementById('badge'); bd.textContent=lb+'  '+ddAth.toFixed(1)+'%'; bd.style.background=bg; bd.style.color=fg;
  document.getElementById('gaugeDet').textContent=desc+'（最高値 '+fmt(ath)+'円 → 現在 '+fmt(now)+'円）';
  const ma25=sma(vals,25),ma75=sma(vals,75),ma200=sma(vals,200);
  const rv=rsi(vals); let rS,rC;
  if(rv==null){rS='—';rC='#cbd5e1';}else if(rv<30){rS='売られすぎ(買い場)';rC='#22c55e';}else if(rv<45){rS='やや弱い';rC='#86efac';}else if(rv<=70){rS='中立';rC='#94a3b8';}else{rS='買われすぎ';rC='#ef4444';}
  function dev(label,mav){if(mav==null)return{l:label,v:'—',c:'#cbd5e1',s:'データ不足'};const d=(now/mav-1)*100;let c,s;if(d<=-5){c='#22c55e';s='大きく下回る';}else if(d<0){c='#86efac';s='線の下';}else if(d<5){c='#94a3b8';s='線の上';}else{c='#f59e0b';s='大きく上回る';}return{l:label,v:(d>=0?'+':'')+d.toFixed(1)+'%',c,s};}
  const cells=[{l:'RSI(14)',v:rv==null?'—':rv.toFixed(0),c:rC,s:rS},dev('25日線かい離',ma25[li]),dev('75日線かい離',ma75[li]),dev('200日線かい離',ma200[li])];
  document.getElementById('tech').innerHTML=cells.map(c=>'<div class="t"><div class="tl">'+c.l+'</div><div class="tv" style="color:'+c.c+'">'+c.v+'</div><div class="ts">'+c.s+'</div></div>').join('');
  let start=0;
  if(curDays<99999){const co=new Date(dates[li]);co.setDate(co.getDate()-curDays);const cs=co.toISOString().slice(0,10);start=dates.findIndex(d=>d>=cs);if(start<0)start=0;}
  const L=dates.slice(start);
  const showMa=document.getElementById('tMa').checked, showIdx=document.getElementById('tIdx').checked && hasIdx;
  let idxLine=[];
  if(showIdx){const idxF=idxAt(f.index,L[0]);const sc=idxF?vals[start]/idxF:1;idxLine=L.map(d=>{const v=idxAt(f.index,d);return v?+(v*sc).toFixed(0):null;});}
  const ds=[{label:'基準価額',data:vals.slice(start),borderColor:COLORS.nav,borderWidth:2,pointRadius:0,tension:.1,fill:false,order:1}];
  if(showIdx)ds.push({label:'指数(正規化)',data:idxLine,borderColor:COLORS.idx,borderWidth:1.5,borderDash:[5,3],pointRadius:0,tension:.1,fill:false,order:0});
  if(showMa){ds.push({label:'25日',data:ma25.slice(start),borderColor:COLORS.ma1,borderWidth:1,pointRadius:0,fill:false});ds.push({label:'75日',data:ma75.slice(start),borderColor:COLORS.ma2,borderWidth:1,pointRadius:0,fill:false});ds.push({label:'200日',data:ma200.slice(start),borderColor:COLORS.ma3,borderWidth:1,pointRadius:0,fill:false});}
  if(chart)chart.destroy();
  chart=new Chart(document.getElementById('chart'),{type:'line',data:{labels:L,datasets:ds},options:{responsive:true,maintainAspectRatio:false,animation:false,interaction:{mode:'index',intersect:false},plugins:{legend:{labels:{color:'#8b98a5',boxWidth:14,font:{size:11}}},tooltip:{callbacks:{label:c=>c.dataset.label+': '+(c.parsed.y==null?'-':fmt(c.parsed.y)+(c.dataset.label==='基準価額'?'円':''))}}},scales:{x:{ticks:{color:'#5b6671',maxTicksLimit:6,font:{size:10}},grid:{display:false}},y:{ticks:{color:'#5b6671',font:{size:10},callback:v=>fmt(v)},grid:{color:'#222a35'}}}}});
  const h=document.getElementById('hint');
  if(!hasIdx){h.innerHTML='（指数データは取得できませんでした。基準価額は正常に表示中）';}
  else{const ix=f.index,a=ix[ix.length-1],b=ix[ix.length-2];const c=(a[1]/b[1]-1)*100;
    h.innerHTML='<b>'+f.indexName+'</b> 最新 '+fmt(a[1])+'（'+a[0]+'） 前日比 <span class="'+(c>=0?'up':'down')+'">'+pct(c)+'</span><br>指数のこの動きは、おおむね<b>翌営業日の基準価額</b>に反映されます（為替次第）。'+(dates[li]<a[0]?'　※基準価額('+dates[li]+')は指数より日付が古いので次の更新で追いつきます。':'');}
}
document.getElementById('updated').textContent='データ取得: '+DATA.generatedAt+' 時点';
document.getElementById('tMa').onchange=render;
document.getElementById('tIdx').onchange=render;
setupTabs(); setupPeriods(); render();
<\/script>
</body></html>`;
