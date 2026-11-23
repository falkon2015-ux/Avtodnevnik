package ru.avtodnevnik;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.widget.Toast;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import java.io.File;
import java.io.InputStream;
import java.io.FileOutputStream;
import org.json.JSONObject;
import java.util.Locale;

public class MainActivity extends Activity {
    private Store store;
    private VoiceGuide voice;
    private WebView web;
    private static final int GPS_REQ = 312;
    private static final int VOICE_IMPORT_REQ = 923;
    @Override public void onCreate(Bundle b){
        super.onCreate(b);store=new Store(this);voice=new VoiceGuide(this);
        web=new WebView(this);
        web.setBackgroundColor(0xffeef5f5);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.addJavascriptInterface(new Bridge(),"Driver");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient());
        setContentView(web);
        web.loadDataWithBaseURL("https://avtodnevnik.local/",PAGE,"text/html","UTF-8",null);
    }
    private void refresh(){web.evaluateJavascript("refresh()",null);}
    private boolean permissionGranted(){return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;}
    private void start(){
        if(!permissionGranted()){
            if(Build.VERSION.SDK_INT>=33)requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.POST_NOTIFICATIONS},GPS_REQ);
            else requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},GPS_REQ);
            return;
        }
        LocationManager lm=(LocationManager)getSystemService(LOCATION_SERVICE);
        if(!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)){
            Toast.makeText(this,"Включите GPS в настройках телефона",Toast.LENGTH_LONG).show();
            startActivity(new Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS));return;
        }
        store.begin();
        Intent i=new Intent(this,TrackerService.class);
        try{startForegroundService(i);voice.say("Рабочий день начат. Запись маршрута включена.");}catch(Exception ex){Toast.makeText(this,"Не удалось включить запись: "+ex.getMessage(),Toast.LENGTH_LONG).show();}
        refresh();
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
        super.onRequestPermissionsResult(request,permissions,results);
        if(request==GPS_REQ && permissionGranted())start();
        else Toast.makeText(this,"Для записи необходим доступ к точной геолокации",Toast.LENGTH_LONG).show();
    }
    private void importElevenLabsVoice(){
        Intent picker=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        picker.setType("audio/*");
        try{startActivityForResult(picker,VOICE_IMPORT_REQ);}
        catch(Exception ex){Toast.makeText(this,"Не найден файловый менеджер для выбора MP3",Toast.LENGTH_LONG).show();}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request!=VOICE_IMPORT_REQ||result!=RESULT_OK||data==null)return;
        Uri uri=data.getData();
        if(uri==null)return;
        File temporary=new File(getFilesDir(),"voice_master_temp.mp3");
        try{
            int copied=0;
            try(InputStream in=getContentResolver().openInputStream(uri);
                FileOutputStream out=new FileOutputStream(temporary)){
                if(in==null)throw new IllegalStateException("Не удалось открыть аудиозапись");
                byte[] dataBuffer=new byte[8192];
                int read;
                while((read=in.read(dataBuffer))!=-1){
                    copied+=read;
                    if(copied>15*1024*1024)throw new IllegalStateException("Слишком большой файл");
                    out.write(dataBuffer,0,read);
                }
            }
            if(copied<20000)throw new IllegalStateException("Аудиозапись слишком короткая");
            MediaMetadataRetriever meta=new MediaMetadataRetriever();
            long duration;
            try{
                meta.setDataSource(temporary.getAbsolutePath());
                String length=meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                duration=length==null?0:Long.parseLong(length);
            }finally{meta.release();}
            // Cuts were timed against the exact 29.6s ElevenLabs master recording.
            if(duration<29000||duration>31000){
                throw new IllegalStateException("Нужна исходная запись ElevenLabs длительностью около 29,6 секунды");
            }
            File target=voice.masterFile();
            if(target.exists()&&!target.delete())throw new IllegalStateException("Нельзя заменить старую запись");
            if(!temporary.renameTo(target))throw new IllegalStateException("Нельзя сохранить аудиозапись");
            Toast.makeText(this,"11 фраз ElevenLabs подключены. Запись хранится в телефоне.",Toast.LENGTH_LONG).show();
            refresh();
            voice.preview();
        }catch(Exception ex){
            Toast.makeText(this,"Ошибка импорта: "+ex.getMessage(),Toast.LENGTH_LONG).show();
        }finally{if(temporary.exists())temporary.delete();}
    }
    @Override protected void onResume(){super.onResume();if(web!=null)web.postDelayed(this::refresh,600);}
    @Override protected void onDestroy(){if(voice!=null)voice.release();super.onDestroy();}
    public class Bridge {
        @JavascriptInterface public boolean voiceEnabled(){return voice.enabled();}
        @JavascriptInterface public boolean voiceImported(){return voice.hasImportedVoice();}
        @JavascriptInterface public void importVoice(){runOnUiThread(()->importElevenLabsVoice());}
        @JavascriptInterface public void setVoiceEnabled(boolean enabled){runOnUiThread(()->{voice.setEnabled(enabled);refresh();});}
        @JavascriptInterface public void previewVoice(){runOnUiThread(()->voice.preview());}
        @JavascriptInterface public void gpsWarning(){runOnUiThread(()->voice.say("Внимание. Давно нет сигнала GPS. Проверьте геолокацию."));}
        @JavascriptInterface public String state(){return store.summary().toString();}
        @JavascriptInterface public void start(){runOnUiThread(()->MainActivity.this.start());}
        @JavascriptInterface public void pause(){runOnUiThread(()->{if(store.active()!=0 && !store.paused()){store.pause();voice.say("Ожидание задания. Учёт пробега приостановлен.");}refresh();});}
        @JavascriptInterface public void resume(){runOnUiThread(()->{if(store.paused()){store.resume();voice.say("Запись маршрута продолжена.");}refresh();});}
        @JavascriptInterface public void end(){runOnUiThread(()->{try{startService(new Intent(MainActivity.this,TrackerService.class).setAction("END"));voice.say("Рабочий день завершён. Маршрут сохранён.");}catch(Exception ex){Toast.makeText(MainActivity.this,"Не удалось завершить запись",Toast.LENGTH_LONG).show();}web.postDelayed(()->refresh(),750);});}
        @JavascriptInterface public void exclude(String id,boolean exclude){try{store.exclude(Long.parseLong(id),exclude);}catch(Exception ignored){}}
        @JavascriptInterface public void rename(String id,String name){try{store.rename(Long.parseLong(id),name);}catch(Exception ignored){}}
        @JavascriptInterface public void openMap(){runOnUiThread(()->{voice.say("Открываю карту маршрута");startActivity(new Intent(MainActivity.this,RouteMapActivity.class));});}
        @JavascriptInterface public void openWaybill(){runOnUiThread(()->{voice.say("Открываю путевой журнал");startActivity(new Intent(MainActivity.this,WaybillActivity.class));});}
        @JavascriptInterface public void exportCsv(){runOnUiThread(()->{
            JSONObject j=store.summary();
            StringBuilder csv=new StringBuilder("Дата;Километраж_км;Время_движения_мин;Стоянки_мин\n");
            csv.append(j.optString("started")).append(";").append(j.optDouble("distance")).append(";").append(j.optLong("moving")).append(";").append(j.optLong("stopped")).append("\n");
            voice.say("Открываю отправку отчёта");
            Intent i=new Intent(Intent.ACTION_SEND);i.setType("text/csv");i.putExtra(Intent.EXTRA_TEXT,csv.toString());startActivity(Intent.createChooser(i,"Отправить отчёт"));
        });}
    }
    private static final String PAGE = """
<!doctype html><html lang="ru"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,user-scalable=no">
<style>
:root{--navy:#12384b;--deep:#0d2d3d;--mint:#b8f0db;--body:#263e49;--muted:#778c94}
*{box-sizing:border-box}body{margin:0;background:#eff5f5;color:var(--body);font:15px system-ui,-apple-system,Arial,sans-serif}
main{max-width:620px;margin:auto;padding:16px 16px 60px}.hero{position:relative;overflow:hidden;color:white;border-radius:28px;padding:24px 23px 26px;background:linear-gradient(120deg,#245a66,#0e3041);box-shadow:0 10px 22px #143f4b20;min-height:222px}
.hero:after{content:' ';width:190px;height:190px;border:2px dashed #77bfb655;border-radius:50%;position:absolute;right:-86px;top:20px;transform:rotate(22deg)}.brand{font-size:12px;font-weight:800;letter-spacing:2px;color:#aef1db}.hero h1{font-size:30px;line-height:1.12;margin:25px 0 13px;color:white}.hero p{font-size:13px;color:#d1e6eb;line-height:1.5;margin:0}.pill{display:inline-flex;align-items:center;gap:9px;padding:9px 13px;border-radius:22px;background:#ffffff19;margin-top:22px;font-size:13px;font-weight:650}.dot{width:9px;height:9px;border-radius:50%;background:#9be7d0}.on .dot{animation:pulse 1.6s infinite;background:#8fffcb}@keyframes pulse{50%{box-shadow:0 0 0 8px #a8ffe41c}}
h2{font-size:21px;color:#16384a;margin:25px 3px 14px}.stats{display:grid;grid-template-columns:1fr 1fr;gap:12px}.stat{border-radius:20px;padding:18px 14px;min-height:125px;background:white;box-shadow:0 3px 12px #1a40410b}.stat:nth-child(1){background:#e0f5ec}.stat:nth-child(2){background:#e5effa}.stat:nth-child(3){background:#ffefdf}.stat:nth-child(4){background:#efecfa}.label{font-size:10px;font-weight:800;letter-spacing:1px;color:#607c81}.value{font-size:24px;font-weight:800;color:#153c4a;margin-top:18px}.card{background:white;padding:20px;border-radius:23px;box-shadow:0 4px 17px #173f4c10;margin-top:16px}.card h3{margin:0 0 8px;font-size:18px;color:#183947}.muted{color:var(--muted);font-size:13px;line-height:1.45}.primary{background:#a9ead1;border:none;color:#123d37;font-weight:800;font-size:17px;padding:18px;border-radius:17px;width:100%;margin-top:18px;box-shadow:0 4px 0 #83c8ae}.voicebtn{border:none;border-radius:14px;background:#ddf5ea;color:#145343;font-weight:800;padding:13px 15px;font-size:14px;cursor:pointer}.voicebtn.off{background:#edf1f3;color:#657d82}.voice-settings{display:flex;gap:10px;flex-wrap:wrap;margin-top:16px}.breakbutton{border:none;background:#fff0ce;color:#735018;font-weight:800;font-size:16px;padding:16px;border-radius:17px;width:100%;margin-top:13px}.breakbutton.running{background:#bbefda;color:#164f40}.breakbutton:disabled{opacity:.57;filter:grayscale(.35);box-shadow:none;cursor:not-allowed}.primary.stop{background:#ffdad2;color:#753a37;box-shadow:0 4px 0 #edb8ab}.button{border:none;background:#edf5f2;border-radius:14px;color:#24596b;font-weight:700;padding:11px 14px;font-size:13px;cursor:pointer}.stoprow{padding:13px 0;border-top:1px solid #e9f1ef}.stophead{display:flex;justify-content:space-between;gap:8px;align-items:center}.stoprow b{color:#1c4651}.actions{display:flex;flex-wrap:wrap;gap:8px;margin-top:9px}.warn{color:#a35335;background:#fff2e7;padding:12px;border-radius:12px;font-size:13px;margin-top:12px;display:none}.mapbox{background:#e7f1f0;border-radius:16px;overflow:hidden;margin:14px 0 8px;height:175px;position:relative}.mapbox svg{width:100%;height:100%}.mapbox span{position:absolute;bottom:9px;left:12px;font-size:11px;color:#5b7e83}.settings{display:flex;gap:14px;align-items:center;margin:12px 0}.settings input{border:1px solid #d6e5e3;background:#f6fbf9;border-radius:11px;padding:10px;width:90px;font-size:16px;color:#164453;font-weight:700}small{font-size:11px;color:#84989b}.footer{text-align:center;margin:24px 0;color:#829598;font-size:11px}
</style></head><body><main>
<div id="hero" class="hero"><div class="brand">◇ АВТОДНЕВНИК</div><h1>Ваш день —<br>под контролем</h1><p>Маршрут, остановки и пробег<br>без лишних действий</p><div id="status" class="pill"><i class="dot"></i> Запись выключена</div></div>
<h2>Обзор дня</h2><div class="stats">
<div class="stat"><div class="label">ПРОБЕГ</div><div id="km" class="value">0,0 км</div></div>
<div class="stat"><div class="label">В ДВИЖЕНИИ</div><div id="moving" class="value">0 мин</div></div>
<div class="stat"><div class="label">НА СТОЯНКАХ</div><div id="stopped" class="value">0 мин</div></div>
<div class="stat"><div class="label">ТОПЛИВО · ОЦЕНКА</div><div id="fuel" class="value">0 ₽</div></div></div>
<div class="card"><h3>Управление записью</h3><div id="prompt" class="muted">Утром нажмите кнопку и оставьте телефон на зарядке в автомобиле.</div><button id="go" class="primary" onclick="toggle()">▶ Начать рабочий день</button><button id="pause" class="breakbutton" disabled onclick="togglePause()">☕ Перекур / Ожидание задания</button><div id="pauseHint" class="muted" style="margin-top:8px">Кнопка станет активной после начала рабочего дня.</div><div id="breaktime" class="muted" style="margin-top:10px">Перерывы: 0 мин</div><div class="muted" style="margin-top:14px">GPS работает при выключенном экране. Данные остаются на устройстве.</div><div id="warning" class="warn"></div></div>
<div class="card"><h3>Маршрут дня</h3><div class="muted">Улицы, полный GPS-маршрут и точки стоянок на интерактивной карте</div><div class="mapbox"><svg viewBox="0 0 330 175" id="route"><path d="M20 140Q90 70 135 110T300 22" fill="none" stroke="#b3cecc" stroke-width="3" stroke-dasharray="6 6"/></svg><span>Мини-схема GPS-трека</span></div><button class="button" onclick="openMap()">🗺 Открыть карту маршрута</button></div>
<div class="card"><h3>Остановки</h3><div class="muted">Фиксируются после пяти минут в радиусе 40 м. Ложные можно исключить.</div><div id="stops" style="margin-top:14px" class="muted">Остановок пока нет</div></div>
<div class="card"><h3>Путевой журнал: день и неделя</h3><div class="muted">Ближайший адрес в радиусе 40 м, семь отдельных дней и выгрузка недельного журнала в Excel (.xlsx).</div><button class="primary" onclick="Driver.openWaybill()">▦ Открыть путевой журнал</button><div class="muted" style="margin-top:12px">Можно экспортировать неделю в Excel и выбранный день в CSV.</div></div>
<div class="card"><h3>Расход топлива</h3><div class="settings"><label>Л/100 км</label><input id="litres" type="number" min="1" max="40" step=".1" value="8"></div><div class="settings"><label>Цена за литр, ₽</label><input id="price" type="number" min="1" max="300" step=".1" value="65"></div><div class="muted">Оценка по пробегу. Простой с работающим мотором не учитывается.</div></div>
<div class="card"><h3>🔊 Голосовые подсказки</h3>
<div class="muted">Подтверждение начала поездки, паузы, продолжения работы и завершения дня. Небольшое предупреждение при потере GPS. Можно отключить в любой момент.</div>
<div class="voice-settings"><button class="voicebtn off" id="voiceToggle" onclick="toggleVoice()">Включить голос</button><button class="button" onclick="Driver.previewVoice()">▶ Проверить голос</button></div>
<div class="voice-settings"><button class="button" onclick="Driver.importVoice()">📁 Выбрать запись ElevenLabs (MP3)</button></div>
<div class="muted" id="voiceFileStatus" style="margin-top:10px">Без записи используется системный голос Android.</div>
<div class="muted" style="margin-top:10px">По умолчанию выключено. Используется русский голос, установленный на телефоне.</div></div>
<div class="card"><h3>Отчёт</h3><p class="muted">Сводка последнего рабочего дня в формате CSV.</p><button class="button" onclick="Driver.exportCsv()">Экспортировать CSV ↗</button></div>
<div class="footer">АВТОДНЕВНИК · Тестовая версия 0.6.0</div></main>
<script>
let state={active:false,distance:0,moving:0,stopped:0,points:[],stops:[]};
const el=id=>document.getElementById(id);
const fmt=m=>m>=60?Math.floor(m/60)+' ч '+(m%60)+' мин':m+' мин';
function toggle(){if(state.active){if(confirm('Завершить запись рабочего дня?'))Driver.end()}else Driver.start();setTimeout(refresh,900)}
function toggleVoice(){Driver.setVoiceEnabled(!Driver.voiceEnabled());setTimeout(refresh,300)}
let gpsAlerted=false;
function togglePause(){if(!state.active)return;if(state.paused)Driver.resume();else Driver.pause();setTimeout(refresh,350)}
function openMap(){Driver.openMap()}
function esc(s){return String(s||'').replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]))}
function nameStop(id){const text=prompt('Название остановки:');if(text!==null){Driver.rename(String(id),text);setTimeout(refresh,250)}}
function exclude(id,yes){Driver.exclude(String(id),yes);setTimeout(refresh,250)}
function drawRoute(points){
const svg=el('route');if(!points||points.length<2)return;
const ps=points.map(p=>[p[1],p[0]]);const xs=ps.map(p=>p[0]),ys=ps.map(p=>p[1]);
const minX=Math.min(...xs),maxX=Math.max(...xs),minY=Math.min(...ys),maxY=Math.max(...ys);
const scale=Math.max(maxX-minX,maxY-minY,0.000001);const path=ps.reverse().map((p,i)=>(i?'L':'M')+(20+(p[0]-minX)/scale*290).toFixed(1)+' '+(155-(p[1]-minY)/scale*135).toFixed(1)).join(' ');
svg.innerHTML='<path d="'+path+'" fill="none" stroke="#24a892" stroke-width="4" stroke-linecap="round" stroke-linejoin="round"/>';
}
function refresh(){
try{state=JSON.parse(Driver.state());el('km').textContent=Number(state.distance||0).toFixed(1).replace('.',',')+' км';el('moving').textContent=fmt(state.moving||0);el('stopped').textContent=fmt(state.stopped||0);
const fuel=Number(state.distance||0)*Number(el('litres').value||8)/100;el('fuel').textContent=Math.round(fuel*Number(el('price').value||65))+' ₽';
el('breaktime').textContent='Перерывы и ожидание: '+fmt(state.break_minutes||0);
el('pause').disabled=!state.active;el('pause').textContent=state.paused?'▶ Продолжить работу':'☕ Перекур / Ожидание задания';el('pause').classList.toggle('running',!!state.paused);el('pauseHint').textContent=state.active?(state.paused?'Пауза включена. Возобновите запись перед поездкой.':'Здесь можно включить ожидание задания без завершения рабочего дня.'):'Кнопка станет активной после начала рабочего дня.';
const voiceOn=Driver.voiceEnabled();
el('voiceToggle').textContent=voiceOn?'🔊 Голос включён — выключить':'🔇 Включить голос';
el('voiceToggle').classList.toggle('off',!voiceOn);
el('voiceFileStatus').textContent=Driver.voiceImported()?'✓ Запись ElevenLabs подключена. 11 фраз воспроизводятся офлайн.':'Выберите вашу исходную запись ElevenLabs длительностью 29,6 с — один раз. Без неё используется голос Android.';
el('hero').classList.toggle('on',state.active&&!state.paused);el('status').innerHTML='<i class="dot"></i> '+(state.paused?'Пауза · ожидание задания':state.active?'Идёт GPS-запись':'Запись выключена');
el('go').textContent=state.active?'■ Завершить рабочий день':'▶ Начать рабочий день';el('go').classList.toggle('stop',state.active);
el('prompt').textContent=state.paused?'Пауза активна: пробег и движение не учитываются. Нажмите «Продолжить работу» перед выездом.':state.active?'Рабочий день записывается. Экран можно выключить.':'Нажмите «Начать рабочий день» для новой записи.';
const warning=el('warning');warning.style.display=state.active&&!state.paused&&state.gpsAge>120?'block':'none';warning.textContent='GPS не обновлялся '+Math.floor(state.gpsAge/60)+' мин. Проверьте точную геолокацию и энергосбережение.';
const gpsLost=state.active&&!state.paused&&state.gpsAge>120;
if(gpsLost&&!gpsAlerted){gpsAlerted=true;Driver.gpsWarning();}
if(!gpsLost)gpsAlerted=false;
const stops=state.stops||[];el('stops').innerHTML=stops.length?stops.map(s=>'<div class="stoprow" style="opacity:'+(s.excluded?'.5':'1')+'"><div class="stophead"><b>'+esc(s.note||'Остановка')+'</b><small>'+esc(s.arrival)+'</small></div><div class="muted">'+s.duration+' мин · '+esc(s.address|| (s.addressStatus===1?'Поиск адреса...':'Адрес рядом не найден'))+(s.addressSource&&s.addressDistance>=0?' · '+esc(s.addressSource)+' · '+Math.round(s.addressDistance)+' м':'')+(s.excluded?' · исключена':'')+'</div><div class="actions"><button class="button" onclick="nameStop('+s.id+')">✎ Назвать</button><button class="button" onclick="exclude('+s.id+','+(!s.excluded)+')">'+(s.excluded?'Вернуть':'Исключить')+'</button></div></div>').join(''):'Остановок пока нет';
drawRoute(state.points);
}catch(e){console.log(e)}
}
el('litres').value=localStorage.getItem('litres')||'8';el('price').value=localStorage.getItem('price')||'65';
['litres','price'].forEach(id=>el(id).addEventListener('input',()=>{localStorage.setItem(id,el(id).value);refresh()}));
setInterval(refresh,4000);refresh();
</script></body></html>
""";
}
