package ru.avtodnevnik;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.widget.Toast;

import java.io.File;
import java.util.Locale;

/**
 * Driver guidance:
 * 1) Play the owner's recorded ElevenLabs voice from bundled, split clips.
 * 2) Or play an individual time window of the owner's imported 29.6s MP3.
 * 3) Device TTS is only a fallback for extra phrases not in the recording.
 *
 * Audio remains on the driver's phone; no network requests are performed.
 */
public final class VoiceGuide {
    private static final String PREFS = "avtodnevnik_voice";
    private static final String ENABLED = "enabled";
    private static final String MASTER = "voice_master.mp3";
    private final Context app;
    private final SharedPreferences preferences;
    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaPlayer player;
    private Runnable stopTask;
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
    public File masterFile(){return new File(app.getFilesDir(),MASTER);}
    public boolean hasImportedVoice(){File file=masterFile();return file.isFile()&&file.length()>20000;}

    public void setEnabled(boolean enabled){
        preferences.edit().putBoolean(ENABLED,enabled).apply();
        if(enabled) say("Голосовые подсказки включены");
        else{
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
        if(p.startsWith("внимание") && (p.contains("gps")||p.contains("джипиэс")))return "gps_lost";
        if(p.startsWith("голосовые подсказки включены"))return "voice_enabled";
        if(p.startsWith("проверка звука"))return "voice_test";
        return null;
    }
    private static int[] times(String name){
        switch(name){
            case "day_start": return new int[]{0,3280};
            case "standby_start": return new int[]{3280,6780};
            case "day_resume": return new int[]{6780,8800};
            case "day_end": return new int[]{8800,12110};
            case "map_open": return new int[]{12110,13970};
            case "journal_open": return new int[]{13970,15700};
            case "address_saved": return new int[]{15700,17060};
            case "journal_saved": return new int[]{17060,18880};
            case "gps_lost": return new int[]{18880,23360};
            case "voice_enabled": return new int[]{23360,25450};
            case "voice_test": return new int[]{25450,29600};
            default: return null;
        }
    }
    private AudioAttributes audioAttributes(){
        return new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build();
    }
    private void stopPlayer(){
        if(stopTask!=null){main.removeCallbacks(stopTask);stopTask=null;}
        if(player==null)return;
        try{player.stop();}catch(Exception ignored){}
        try{player.release();}catch(Exception ignored){}
        player=null;
    }
    private void releaseIfCurrent(MediaPlayer mp){
        if(player==mp){
            player=null;
            if(stopTask!=null){main.removeCallbacks(stopTask);stopTask=null;}
        }
        try{mp.release();}catch(Exception ignored){}
    }
    private boolean playBundled(String name){
        int res=app.getResources().getIdentifier(name,"raw",app.getPackageName());
        if(res==0)return false;
        try{
            stopPlayer();
            if(synthesizer!=null)synthesizer.stop();
            MediaPlayer mp=MediaPlayer.create(app,res,audioAttributes(),0);
            if(mp==null)return false;
            player=mp;
            mp.setOnCompletionListener(this::releaseIfCurrent);
            mp.setOnErrorListener((failed,what,extra)->{releaseIfCurrent(failed);return true;});
            mp.start();
            return true;
        }catch(Exception e){stopPlayer();return false;}
    }
    private boolean playImported(String name){
        if(!hasImportedVoice())return false;
        int[] interval=times(name);
        if(interval==null)return false;
        try{
            stopPlayer();
            if(synthesizer!=null)synthesizer.stop();
            MediaPlayer mp=new MediaPlayer();
            mp.setAudioAttributes(audioAttributes());
            mp.setDataSource(masterFile().getAbsolutePath());
            player=mp;
            mp.setOnPreparedListener(ready->{
                if(released||player!=ready)return;
                try{ready.seekTo(interval[0],MediaPlayer.SEEK_CLOSEST);}
                catch(Exception e){releaseIfCurrent(ready);}
            });
            mp.setOnSeekCompleteListener(ready->{
                if(released||player!=ready)return;
                try{
                    ready.start();
                    final int duration=interval[1]-interval[0];
                    stopTask=()->{if(player==ready)releaseIfCurrent(ready);};
                    main.postDelayed(stopTask,duration);
                }catch(Exception e){releaseIfCurrent(ready);}
            });
            mp.setOnErrorListener((failed,what,extra)->{releaseIfCurrent(failed);return true;});
            mp.setOnCompletionListener(this::releaseIfCurrent);
            mp.prepareAsync();
            return true;
        }catch(Exception e){stopPlayer();return false;}
    }
    private boolean playRecorded(String message){
        String clip=audioFile(message);
        if(clip==null)return false;
        if(playBundled(clip))return true;
        return playImported(clip);
    }
    private void speakInternal(String message,boolean preview){
        if(released||message==null||message.isEmpty()||(!preview&&!enabled()))return;
        main.post(()->{
            if(released)return;
            if(playRecorded(message))return;
            // The recording contains eleven commands. Extra messages use Android TTS.
            if(unavailable){
                if(preview)Toast.makeText(app,
                    "Ни запись, ни русский голос Android не найдены.",
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
                    unavailable=true;pending=null;return;
                }
                int language=synthesizer.setLanguage(new Locale("ru","RU"));
                if(language==TextToSpeech.LANG_MISSING_DATA || language==TextToSpeech.LANG_NOT_SUPPORTED){
                    unavailable=true;pending=null;return;
                }
                synthesizer.setSpeechRate(0.96f);
                synthesizer.setPitch(1.02f);
                initialized=true;
                if(pending!=null){
                    String phrase=pending;pending=null;
                    synthesizer.speak(phrase,TextToSpeech.QUEUE_FLUSH,null,"avtodnevnik-initial");
                }
            }));
        }catch(Exception ignored){initializing=false;unavailable=true;pending=null;}
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
