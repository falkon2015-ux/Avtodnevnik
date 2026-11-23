package ru.avtodnevnik;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.widget.Toast;

import java.util.Locale;

/**
 * Optional, privacy-preserving native speech feedback.
 * Speaks only short user-initiated confirmations and a single GPS-loss alert.
 * Does not send trip coordinates or addresses to a speech service explicitly.
 * The installed device speech engine determines whether speech needs internet.
 */
public final class VoiceGuide {
    private static final String PREFS = "avtodnevnik_voice";
    private static final String ENABLED = "enabled";
    private final Context app;
    private final SharedPreferences preferences;
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextToSpeech synthesizer;
    private boolean initialized = false;
    private boolean initializing = false;
    private boolean unavailable = false;
    private String pending;
    private boolean released = false;

    public VoiceGuide(Context context){
        this.app = context.getApplicationContext();
        this.preferences = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (enabled()) initialize();
    }

    public boolean enabled(){
        return preferences.getBoolean(ENABLED,false);
    }

    public void setEnabled(boolean enabled){
        preferences.edit().putBoolean(ENABLED,enabled).apply();
        if(enabled){
            say("Голосовые подсказки включены");
        }else{
            pending=null;
            if(synthesizer!=null)synthesizer.stop();
        }
    }

    public void preview(){
        speakInternal("Проверка звука. Автодневник готов сопровождать рабочий день.",true);
    }

    public void say(String message){
        speakInternal(message,false);
    }

    private void speakInternal(String message,boolean preview){
        if(released || message==null || message.isEmpty() || (!preview && !enabled()))return;
        main.post(()->{
            if(released)return;
            if(unavailable){
                if(preview)Toast.makeText(app,"Русский голос недоступен. Проверьте настройки синтеза речи Android.",Toast.LENGTH_LONG).show();
                return;
            }
            if(initialized && synthesizer!=null){
                synthesizer.speak(message,TextToSpeech.QUEUE_FLUSH,null,"avtodnevnik-"+System.nanoTime());
            }else{
                pending=message;
                initialize();
            }
        });
    }

    private void initialize(){
        if(initializing || initialized || unavailable || released)return;
        initializing=true;
        try{
            synthesizer=new TextToSpeech(app,status->main.post(()->{
                if(released)return;
                initializing=false;
                if(status!=TextToSpeech.SUCCESS || synthesizer==null){
                    unavailable=true;
                    pending=null;
                    Toast.makeText(app,"Не найден движок синтеза речи Android",Toast.LENGTH_LONG).show();
                    return;
                }
                int language=synthesizer.setLanguage(new Locale("ru","RU"));
                if(language==TextToSpeech.LANG_MISSING_DATA || language==TextToSpeech.LANG_NOT_SUPPORTED){
                    unavailable=true;
                    pending=null;
                    Toast.makeText(app,"Русский голос не установлен. Проверьте настройки синтеза речи Android.",Toast.LENGTH_LONG).show();
                    return;
                }
                synthesizer.setSpeechRate(0.96f);
                synthesizer.setPitch(1.02f);
                initialized=true;
                if(pending!=null){
                    String speak=pending;
                    pending=null;
                    synthesizer.speak(speak,TextToSpeech.QUEUE_FLUSH,null,"avtodnevnik-initial");
                }
            }));
        }catch(Exception e){
            initializing=false;
            unavailable=true;
            pending=null;
        }
    }

    public void release(){
        released=true;
        pending=null;
        main.removeCallbacksAndMessages(null);
        if(synthesizer!=null){
            synthesizer.stop();
            synthesizer.shutdown();
            synthesizer=null;
        }
    }
}
