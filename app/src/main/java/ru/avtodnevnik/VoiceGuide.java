package ru.avtodnevnik;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.widget.Toast;
import java.util.Locale;

/**
 * First plays the user's recorded ElevenLabs messages (bundled offline in res/raw).
 * Falls back to Android TTS only for phrases not present in the supplied recording.
 * All 11 spoken commands are enabled/disabled with one preference.
 */
public final class VoiceGuide {
    private static final String PREFS = "avtodnevnik_voice";
    private static final String ENABLED = "enabled";
    private final Context app;
    private final SharedPreferences preferences;
    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaPlayer player;
    private TextToSpeech synthesizer;
    private boolean initialized = false;
    private boolean initializing = false;
    private boolean unavailable = false;
    private String pending;
    private boolean released = false;

    public VoiceGuide(Context context){
        app=context.getApplicationContext();
        preferences=app.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
    }
    public boolean enabled(){return preferences.getBoolean(ENABLED,false);}
    public void setEnabled(boolean enabled){
        preferences.edit().putBoolean(ENABLED,enabled).apply();
        if(enabled){
            say("Голосовые подсказки включены");
        }else{
            pending=null;
            stopPlayer();
            if(synthesizer!=null)synthesizer.stop();
        }
    }
    public void preview(){
        speakInternal("Проверка звука. Автодневник готов сопровождать рабочий день.",true);
    }
    public void say(String message){speakInternal(message,false);}

    private static String audioFile(String phrase){
        String p=phrase.toLowerCase(new Locale("ru","RU"));
        if(p.startsWith("рабочий день начат"))return "day_start";
        if(p.startsWith("ожидание задания"))return "standby_start";
        if(p.startsWith("запись маршрута продолжена"))return "day_resume";
        if(p.startsWith("рабочий день заверш"))return "day_end";
        if(p.startsWith("открываю карту маршрута"))return "map_open";
        if(p.startsWith("открываю путевой журнал"))return "journal_open";
        if(p.startsWith("адрес сохран"))return "address_saved";
        if(p.startsWith("путевой журнал сохран"))return "journal_saved";
        if(p.startsWith("внимание") && p.contains("gps"))return "gps_lost";
        if(p.startsWith("голосовые подсказки включены"))return "voice_enabled";
        if(p.startsWith("проверка звука"))return "voice_test";
        return null;
    }
    private void stopPlayer(){
        if(player==null)return;
        try{player.stop();}catch(Exception ignored){}
        try{player.release();}catch(Exception ignored){}
        player=null;
    }
    private boolean playClip(String phrase){
        String name=audioFile(phrase);
        if(name==null)return false;
        int id=app.getResources().getIdentifier(name,"raw",app.getPackageName());
        if(id==0)return false; // Allows building without master recording.
        try{
            stopPlayer();
            if(synthesizer!=null)synthesizer.stop();
            AudioAttributes attrs=new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
            MediaPlayer mp=MediaPlayer.create(app,id,attrs,0);
            if(mp==null)return false;
            player=mp;
            mp.setOnCompletionListener(done->{
                if(player==done){player=null;}
                done.release();
            });
            mp.setOnErrorListener((failed,what,extra)->{
                if(player==failed)player=null;
                failed.release();
                return true;
            });
            mp.start();
            return true;
        }catch(Exception ignored){
            stopPlayer();
            return false;
        }
    }
    private void speakInternal(String message,boolean preview){
        if(released||message==null||message.isEmpty()||(!preview&&!enabled()))return;
        main.post(()->{
            if(released)return;
            if(playClip(message))return;
            // Unrecorded auxiliary prompts still use the device's built-in TTS.
            if(unavailable){
                if(preview)Toast.makeText(app,
                    "Голос не найден. Проверьте настройку синтеза речи Android.",
                    Toast.LENGTH_LONG).show();
                return;
            }
            if(initialized&&synthesizer!=null){
                synthesizer.speak(message,TextToSpeech.QUEUE_FLUSH,null,"avtodnevnik-"+System.nanoTime());
            }else{
                pending=message;
                initialize();
            }
        });
    }
    private void initialize(){
        if(initializing||initialized||unavailable||released)return;
        initializing=true;
        try{
            synthesizer=new TextToSpeech(app,status->main.post(()->{
                if(released)return;
                initializing=false;
                if(status!=TextToSpeech.SUCCESS||synthesizer==null){
                    unavailable=true;pending=null;
                    return;
                }
                int language=synthesizer.setLanguage(new Locale("ru","RU"));
                if(language==TextToSpeech.LANG_MISSING_DATA||language==TextToSpeech.LANG_NOT_SUPPORTED){
                    unavailable=true;pending=null;
                    return;
                }
                synthesizer.setSpeechRate(0.96f);
                synthesizer.setPitch(1.02f);
                initialized=true;
                if(pending!=null){
                    String phrase=pending;pending=null;
                    synthesizer.speak(phrase,TextToSpeech.QUEUE_FLUSH,null,"avtodnevnik-initial");
                }
            }));
        }catch(Exception ignored){
            initializing=false;unavailable=true;pending=null;
        }
    }
    public void release(){
        released=true;pending=null;
        main.removeCallbacksAndMessages(null);
        stopPlayer();
        if(synthesizer!=null){
            synthesizer.stop();synthesizer.shutdown();synthesizer=null;
        }
    }
}
