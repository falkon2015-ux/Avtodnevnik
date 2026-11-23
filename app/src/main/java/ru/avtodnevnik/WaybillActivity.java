package ru.avtodnevnik;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class WaybillActivity extends Activity {
    private static final int SAVE_CSV=801;
    private static final int SAVE_WEEK_XLSX=802;
    private Store store;
    private VoiceGuide voice;
    private WebView web;
    private String csvDate=WaybillEngine.today();
    private String excelWeek=WaybillEngine.today();

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        store=new Store(this);
        voice=new VoiceGuide(this);
        web=new WebView(this);
        web.setBackgroundColor(0xffedf5f2);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.addJavascriptInterface(new Bridge(),"Sheet");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient());
        setContentView(web);
        web.loadDataWithBaseURL("https://avtodnevnik.local/",PAGE,"text/html","UTF-8",null);
    }

    private void saveCsv(String date){
        csvDate=date!=null && date.matches("\\d{4}-\\d{2}-\\d{2}")?date:WaybillEngine.today();
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/csv");
        intent.putExtra(Intent.EXTRA_TITLE,"Avtodnevnik_"+csvDate+".csv");
        try{voice.say("Выберите место для сохранения путевого журнала.");startActivityForResult(intent,SAVE_CSV);}
        catch(Exception e){Toast.makeText(this,"Нет приложения для сохранения CSV",Toast.LENGTH_LONG).show();}
    }
    private void saveWeekExcel(String date){
        excelWeek=WeekJournal.monday(date);
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        intent.putExtra(Intent.EXTRA_TITLE,"Avtodnevnik_week_"+excelWeek+".xlsx");
        try{
            voice.say("Сохраняю журнал поездок за неделю в Excel.");
            startActivityForResult(intent,SAVE_WEEK_XLSX);
        }catch(Exception ex){Toast.makeText(this,"Нет приложения для сохранения Excel",Toast.LENGTH_LONG).show();}
    }
    @Override protected void onActivityResult(int code,int result,Intent data){
        super.onActivityResult(code,result,data);
        if((code!=SAVE_CSV && code!=SAVE_WEEK_XLSX)||result!=RESULT_OK||data==null)return;
        Uri uri=data.getData();
        if(uri==null)return;
        try(OutputStream os=getContentResolver().openOutputStream(uri,"w")){
            if(os==null)throw new IllegalStateException("Не удалось открыть файл");
            if(code==SAVE_WEEK_XLSX){
                WeekXlsxWriter.write(os,store,excelWeek);
            }else{
                os.write(new byte[]{(byte)0xEF,(byte)0xBB,(byte)0xBF}); // UTF-8 BOM for Excel Cyrillic.
                os.write(WaybillEngine.csv(store,csvDate).getBytes(StandardCharsets.UTF_8));
            }
            os.flush();
            Toast.makeText(this,code==SAVE_WEEK_XLSX?"Недельная таблица Excel сохранена":"Суточная таблица CSV сохранена",Toast.LENGTH_LONG).show();
            voice.say("Путевой журнал сохранён.");
        }catch(Exception e){
            Toast.makeText(this,"Ошибка сохранения: "+e.getMessage(),Toast.LENGTH_LONG).show();
        }
    }
    @Override protected void onDestroy(){if(voice!=null)voice.release();super.onDestroy();}
    public class Bridge {
        @JavascriptInterface public String days(){return store.reportDays().toString();}
        @JavascriptInterface public String week(String date){
            try{
                org.json.JSONArray dates=WeekJournal.dates(date);
                for(int i=0;i<dates.length();i++)store.backfillAddresses(dates.optString(i));
            }catch(Exception ignored){}
            return WeekJournal.get(store,date).toString();
        }
        @JavascriptInterface public String shiftWeek(String date,int diff){
            return WeekJournal.shift(date,diff);
        }
        @JavascriptInterface public void exportWeek(String date){runOnUiThread(()->saveWeekExcel(date));}
        @JavascriptInterface public String data(String date){
            try{store.backfillAddresses(date);}catch(Exception ignored){}
            return store.dayReport(date).toString();
        }
        @JavascriptInterface public void edit(String kind,String id,String city,String street,String house){
            try{store.editAddress(kind,Long.parseLong(id),city,street,house);voice.say("Адрес сохранён.");}
            catch(Exception ignored){}
        }
        @JavascriptInterface public void retry(String kind,String id){
            try{store.retryAddress(kind,Long.parseLong(id));voice.say("Повторяю определение адреса.");}
            catch(Exception ignored){}
        }
        @JavascriptInterface public void export(String date){runOnUiThread(()->saveCsv(date));}
        @JavascriptInterface public void back(){runOnUiThread(()->finish());}
    }

    private static final String PAGE = """
<!doctype html><html lang="ru"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, user-scalable=no">
<style>
:root{--dark:#123a48;--mint:#a9ead1;--muted:#71898d;--line:#e0eae8}
*{box-sizing:border-box}body{background:#edf5f2;font:14px system-ui,-apple-system,Arial,sans-serif;color:#294853;margin:0}
main{margin:auto;max-width:780px;padding:0 15px 34px}
.header{background:linear-gradient(125deg,#1b5360,#103344);color:white;margin:0 -15px;padding:16px 20px 24px;border-radius:0 0 27px 27px}
.back{color:#e3f9f0;background:#ffffff23;border:0;border-radius:12px;padding:9px 15px;font-weight:800;font-size:13px}
.eyebrow{margin:23px 0 6px;letter-spacing:1.9px;font-size:11px;color:#b4e5d3;font-weight:800}.header h1{margin:0;font-size:29px;line-height:1.17}
.header p{color:#c8e5e3;font-size:13px;line-height:1.5;max-width:430px}
.card{background:white;border-radius:20px;padding:17px;margin-top:16px;box-shadow:0 5px 18px #1641430c}
h2{font-size:19px;color:var(--dark);margin:0 0 10px}small{color:#789195;font-size:11px}
.date-line{display:flex;justify-content:space-between;align-items:center;gap:11px;flex-wrap:wrap}
.weekControls{display:flex;align-items:center;justify-content:space-between;gap:8px;margin:8px 0 12px}
.weekControls button{background:#e9f5ef;color:#194e42;border:0;border-radius:12px;padding:10px 13px;font-weight:800}
.weekControls b{color:#174650;font-size:13px}
.weekdayButton{background:#def3e9;border:0;border-radius:9px;padding:8px 11px;font-weight:750;color:#1a5a4a}
.weekEmpty{font-size:12px;color:#84999a}
select{border:1px solid #d2e5e0;border-radius:12px;font-weight:700;color:#204657;padding:11px;background:#f3faf7;font-size:15px}
.stat-grid{display:grid;grid-template-columns:1fr 1fr;gap:11px;margin-top:14px}
.stat{padding:14px 13px;background:#e7f6ee;border-radius:15px}
.stat:nth-child(2){background:#e6f0f9}.stat:nth-child(3){background:#fff1df}.stat:nth-child(4){background:#eeeafb}
.stat label{font-size:10px;letter-spacing:.7px;font-weight:800;color:#678286;display:block}
.stat b{font-size:21px;display:block;margin-top:8px;color:#153f4b}
.info{background:#f3f8f7;border-radius:13px;color:#71898c;font-size:12px;line-height:1.48;padding:11px;margin:9px 0 4px}
.actions{display:flex;gap:9px;flex-wrap:wrap;margin-top:13px}
.btn{border:0;border-radius:13px;font-weight:750;background:#e7f3ef;color:#1a5a51;padding:11px 14px;font-size:13px}
.btn.primary{background:var(--mint);color:#174c42}
.table-scroll{max-width:100%;overflow-x:auto;border:1px solid var(--line);border-radius:14px;margin-top:14px}
table{border-collapse:collapse;min-width:740px;width:100%;table-layout:fixed}
th{padding:13px 8px;background:#eff7f4;text-align:left;font-size:10px;letter-spacing:.5px;color:#657d80}
td{padding:13px 8px;border-top:1px solid #e8eeeb;vertical-align:top}
th:nth-child(1){width:35px}th:nth-child(2),th:nth-child(4){width:66px}
th:nth-child(3),th:nth-child(5){width:212px}th:nth-child(6){width:58px}th:nth-child(7){width:78px}
.place{font-size:12px;line-height:1.35}.place b{color:#194551}.place .lines{font-size:11px;color:#57727a;margin-top:3px}
.place .missing{color:#bc7040}.cell-action{display:inline-block;margin-top:7px;background:#eaf3f1;border:0;border-radius:9px;color:#28645b;padding:6px 8px;font-size:11px}
.muted{color:var(--muted);font-size:12px}.empty{text-align:center;padding:28px 10px;color:var(--muted)}
.help{font-size:12px;color:#758c90;line-height:1.5;margin:14px 3px}
.modal{display:none;position:fixed;inset:0;background:#0a2539ad;z-index:20;align-items:flex-end;justify-content:center}
.modal.open{display:flex}.sheet{width:100%;max-width:520px;background:white;border-radius:26px 26px 0 0;padding:23px 20px 28px;box-shadow:0 -10px 40px #04203026}
.sheet h2{margin:0 0 8px}.field{display:block;margin:12px 0;color:#5d7d82;font-size:12px;font-weight:700}
.field input{display:block;width:100%;padding:13px;border-radius:12px;border:1px solid #cddedc;color:#214958;font-size:16px;margin-top:5px}
.modalBtns{display:flex;gap:10px;margin-top:17px}.modalBtns button{flex:1}
#addrGps{overflow-wrap:anywhere}#status{color:#548175;font-size:12px;margin-top:9px}
</style></head><body>
<div class="header"><button class="back" onclick="Sheet.back()">← Назад</button>
<div class="eyebrow">АВТОДНЕВНИК · GPS</div><h1>Путевой журнал<br>за неделю и сутки</h1>
<p>Каждый рабочий день хранится отдельно. Ближайшие адреса подбираются в радиусе 40 метров, результаты можно проверить и выгрузить в Excel.</p>
</div>
<main>
<section class="card">
<h2>Недельный журнал</h2>
<div class="weekControls">
<button onclick="changeWeek(-1)">‹ Раньше</button>
<b id="weekRange">Текущая неделя</b>
<button onclick="changeWeek(1)">Позже ›</button>
</div>
<div class="info">Все семь дней сохраняются отдельно. Нажмите на дату, чтобы открыть маршруты и исправить адреса.</div>
<div class="table-scroll"><table style="min-width:720px"><thead><tr>
<th style="width:94px">День</th><th>Дата</th><th>Рейсы</th><th>Км</th><th>Движение</th><th>Стоянки</th><th>Ожидание</th>
</tr></thead><tbody id="weekRows"><tr><td colspan="7" class="empty">Загрузка недели</td></tr></tbody></table></div>
<div class="actions"><button class="btn primary" onclick="Sheet.exportWeek(weekDate)">↓ Сохранить неделю в Excel (.xlsx)</button></div>
<div class="muted" id="weekTotal" style="margin-top:9px"></div>
</section>
<section class="card">
<div class="date-line"><h2>Выберите день для подробного отчёта</h2><select id="day" onchange="pickDay()"></select></div>
<div class="stat-grid">
<div class="stat"><label>ПРОБЕГ</label><b id="distance">0,0 км</b></div>
<div class="stat"><label>В ДВИЖЕНИИ</label><b id="moving">0 мин</b></div>
<div class="stat"><label>СТОЯНКИ</label><b id="stopped">0 мин</b></div>
<div class="stat"><label>ПЕРЕРЫВЫ / ОЖИДАНИЕ</label><b id="breaks">0 мин</b></div>
</div>
<div id="status">Проверяем ближайшие адресные объекты в пределах 40 м. Если адрес не найден, укажите его вручную.</div>
</section>
<section class="card"><h2>Маршрут по точкам</h2>
<div class="info">Листайте таблицу по горизонтали. Программа показывает адрес, а не GPS-координаты. Адреса без найденного дома отмечены для проверки.</div>
<div class="table-scroll"><table><thead><tr>
<th>№</th><th>Выезд</th><th>Откуда</th><th>Прибытие</th><th>Куда</th><th>Км</th><th>Стоянка</th>
</tr></thead><tbody id="rows"><tr><td colspan="7" class="empty">Нет записей за этот день</td></tr></tbody></table></div>
<div class="actions"><button class="btn primary" onclick="Sheet.export(el('day').value)">↓ Сохранить CSV</button>
<button class="btn" onclick="refresh()">↻ Обновить адреса</button></div>
</section>
<p class="help">Это журнал GPS-поездок. Он не заменяет обязательный официальный путевой лист, если такой документ требуется для вашей деятельности. Для автоматического поиска нужен интернет; адреса берутся из OpenStreetMap или геокодера устройства и могут не соответствовать фактическому объекту посещения. © OpenStreetMap contributors.</p>
</main>
<div class="modal" id="modal"><div class="sheet">
<h2>Уточнить адрес</h2>
<div class="muted" id="addrGps">Адрес можно исправить вручную. Исходная GPS-точка остаётся сохранённой в журнале.</div>
<label class="field">Город / населённый пункт<input id="city" type="text" placeholder="Благовещенск" maxlength="180"></label>
<label class="field">Улица<input id="street" type="text" placeholder="Октябрьская" maxlength="180"></label>
<label class="field">Номер дома<input id="house" type="text" placeholder="108" maxlength="180"></label>
<div class="modalBtns"><button class="btn" onclick="closeEditor()">Отмена</button>
<button class="btn primary" onclick="saveEditor()">Сохранить</button></div>
<div class="actions"><button class="btn" onclick="retryEditor()">↻ Попробовать определить автоматически</button></div>
</div></div>
<script>
const el=id=>document.getElementById(id);
let state={rows:[],total:{}},editing=null,weekDate=null;
const esc=x=>String(x==null?'':x).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const time=m=>m>=60?Math.floor(m/60)+' ч '+m%60+' мин':m+' мин';
const dateLabel=k=>k.split('-').reverse().join('.');
function makeDates(){
try{
  let saved=localStorage.getItem('reportday');
  let dates=JSON.parse(Sheet.days());
  const select=el('day');
  select.innerHTML=dates.map(d=>'<option value="'+esc(d)+'">'+dateLabel(d)+'</option>').join('');
  if(saved&&dates.includes(saved))select.value=saved;
}catch(e){console.log(e)}
}
function pickDay(){localStorage.setItem('reportday',el('day').value);refresh()}
function selectDay(date){
 let d=el('day');
 if(!Array.from(d.options).some(o=>o.value===date))d.add(new Option(dateLabel(date),date));
 d.value=date;pickDay();
 document.querySelector('#day').scrollIntoView({behavior:'smooth',block:'center'});
}
function changeWeek(delta){weekDate=Sheet.shiftWeek(weekDate||el('day').value,delta);refreshWeek()}
function refreshWeek(){
 try{
   let w=JSON.parse(Sheet.week(weekDate||el('day').value));
   weekDate=w.from;
   el('weekRange').textContent=dateLabel(w.from)+' — '+dateLabel(w.to);
   let list=w.days||[];
   el('weekRows').innerHTML=list.map(d=>'<tr><td><button class="weekdayButton" onclick="selectDay(\''+d.date+'\')">'+esc(d.weekday)+'</button></td>'+
     '<td>'+dateLabel(d.date)+'</td>'+
     '<td>'+d.legs+'</td><td><b>'+Number(d.km).toFixed(1)+'</b></td>'+
     '<td>'+time(d.moving)+'</td><td>'+time(d.stopped)+'</td><td>'+time(d.breaks)+'</td></tr>').join('');
   let t=w.total||{};
   el('weekTotal').textContent='Итого за неделю: '+Number(t.km||0).toFixed(1)+' км · '+(t.legs||0)+' участков маршрута';
 }catch(e){el('weekTotal').textContent='Не удалось загрузить недельный журнал: '+e.message}
}
function placeCell(p){
 const kind=p.kind,id=Number(p.id);
 const city=esc(p.city||'—'),street=esc(p.street||'—'),house=esc(p.house||'—');
 const found=p.status===2 || p.status===4;
 const matched=p.source && p.distanceMetres>=0 ? ' · '+esc(p.source)+' · '+Math.round(p.distanceMetres)+' м' : '';
 const text='<div class="place">'+
   '<b>'+city+'</b><div class="lines">ул.: '+street+'<br>дом: '+house+'</div>'+
   (!found?'<div class="missing">Адрес не найден в 40 м</div>':(!p.complete?'<div class="missing">Не указан город</div>':''))+'<small>'+matched+'</small></div>';
 if(kind==='current')return text;
 return text+'<button class="cell-action" onclick="editAddress(\''+kind+'\','+id+')">✎ Адрес</button>';
}
function refresh(){
try {
 const d=el('day').value;
 state=JSON.parse(Sheet.data(d));
 const summary=state.total||{};
 el('distance').textContent=(summary.km||0).toFixed(1).replace('.',',')+' км';
 el('moving').textContent=time(summary.movingMinutes||0);
 el('stopped').textContent=time(summary.stoppedMinutes||0);
 el('breaks').textContent=time(summary.breakMinutes||0);
 const rows=state.rows||[];
 el('status').textContent=(summary.sessions||0)+' рабочих сессий · '+rows.length+' участков маршрута · адреса могут требовать уточнения';
 el('rows').innerHTML=rows.length?rows.map(r=>'<tr><td><b>'+r.number+'</b></td>'+
   '<td>'+esc(r.departure)+'</td>'+
   '<td>'+placeCell(r.from)+'</td>'+
   '<td>'+esc(r.arrival)+'</td>'+
   '<td>'+placeCell(r.to)+'</td>'+
   '<td><b>'+Number(r.km||0).toFixed(2)+'</b></td>'+
   '<td>'+time(r.stopMinutes||0)+(r.ongoing?'<br><small>сейчас</small>':'')+'</td></tr>').join(''):
   '<tr><td colspan="7" class="empty">Пока нет GPS-поездок за этот день</td></tr>';
}catch(e){el('status').textContent='Ошибка загрузки данных: '+e.message}
}
function editAddress(kind,id){
  editing=null;
  const rows=state.rows||[];
  for(const row of rows){for(const p of [row.from,row.to]){
    if(p.kind===kind && Number(p.id)===Number(id)){editing={...p};break}
  }if(editing)break}
  if(!editing)return;
  el('city').value=editing.city||'';el('street').value=editing.street||'';
  el('house').value=editing.house||'';
  el('addrGps').textContent=editing.source ? 'Источник: '+editing.source+(editing.distanceMetres>=0?' · до объекта '+Math.round(editing.distanceMetres)+' м':'') : 'Введите подтверждённый адрес объекта. Координаты сохраняются только внутри приложения.';
  el('modal').classList.add('open');
}
function closeEditor(){el('modal').classList.remove('open');editing=null}
function saveEditor(){
 if(!editing)return;
 Sheet.edit(editing.kind,String(editing.id),el('city').value,el('street').value,el('house').value);
 closeEditor();setTimeout(refresh,350);
}
function retryEditor(){
 if(!editing)return;
 Sheet.retry(editing.kind,String(editing.id));
 closeEditor();setTimeout(refresh,900);
}
makeDates();weekDate=el("day").value;refresh();refreshWeek();setInterval(refresh,14000);setInterval(refreshWeek,60000);
</script></body></html>
""";
}
