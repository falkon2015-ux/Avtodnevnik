package ru.avtodnevnik;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.location.Location;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class Store extends SQLiteOpenHelper {
    private final SharedPreferences prefs;
    public Store(Context c) { super(c,"avtodnevnik.db",null,1); prefs=c.getSharedPreferences("record",0); }
    @Override public void onCreate(SQLiteDatabase db){
        db.execSQL("CREATE TABLE trips(id INTEGER PRIMARY KEY AUTOINCREMENT, started INTEGER NOT NULL, finished INTEGER, km REAL DEFAULT 0, moving_ms INTEGER DEFAULT 0)");
        db.execSQL("CREATE TABLE points(id INTEGER PRIMARY KEY AUTOINCREMENT,trip_id INTEGER, t INTEGER,lat REAL,lon REAL,accuracy REAL,speed REAL)");
        db.execSQL("CREATE TABLE stops(id INTEGER PRIMARY KEY AUTOINCREMENT,trip_id INTEGER,arrival INTEGER,departure INTEGER,lat REAL,lon REAL,excluded INTEGER DEFAULT 0,note TEXT DEFAULT '')");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldV,int newV){}
    public long active(){return prefs.getLong("active",0);}
    public long begin(){if(active()!=0)return active(); SQLiteDatabase db=getWritableDatabase(); android.content.ContentValues v=new android.content.ContentValues();v.put("started",System.currentTimeMillis());long id=db.insertOrThrow("trips",null,v);prefs.edit().putLong("active",id).remove("anchor_lat").remove("anchor_lon").remove("anchor_t").remove("last_t").remove("last_lat").remove("last_lon").remove("active_stop").apply();return id;}
    public void finish(){long id=active();if(id==0)return;long now=System.currentTimeMillis();long stop=prefs.getLong("active_stop",0);if(stop!=0){getWritableDatabase().execSQL("UPDATE stops SET departure=? WHERE id=?",new Object[]{now,stop});}getWritableDatabase().execSQL("UPDATE trips SET finished=? WHERE id=?",new Object[]{now,id});prefs.edit().clear().apply();}
    public synchronized void onFix(Location p){
        long trip=active();if(trip==0 || p.getAccuracy()>55 || p.getAccuracy()<=0)return;
        long t=p.getTime()>0?p.getTime():System.currentTimeMillis();
        if(t>System.currentTimeMillis()+30000)return;
        long lt=prefs.getLong("last_t",0);
        if(lt>0 && t<=lt)return;
        float lat=p.getLatitude()==0?0:(float)p.getLatitude();
        float lon=p.getLongitude()==0?0:(float)p.getLongitude();
        if(lat==0 && lon==0)return;
        float dist=0;long delta=0;
        if(lt>0){float[] d=new float[1]; Location.distanceBetween(Double.longBitsToDouble(prefs.getLong("last_lat",0)), Double.longBitsToDouble(prefs.getLong("last_lon",0)),p.getLatitude(),p.getLongitude(),d);dist=d[0];delta=t-lt;}
        if(lt>0 && (delta>300000 || (dist>120 && dist>delta*0.065))) {dist=0;delta=0;}
        android.content.ContentValues point=new android.content.ContentValues();
        point.put("trip_id",trip);point.put("t",t);point.put("lat",p.getLatitude());point.put("lon",p.getLongitude());point.put("accuracy",p.getAccuracy());point.put("speed",p.hasSpeed()?p.getSpeed():0);
        SQLiteDatabase db=getWritableDatabase();db.insert("points",null,point);
        float speed=p.hasSpeed()?p.getSpeed():0;
        if(delta>0){if(dist>Math.max(6,p.getAccuracy()*0.35) && dist<120 && dist/delta*1000<65)db.execSQL("UPDATE trips SET km=km+? WHERE id=?",new Object[]{dist/1000.0,trip});if(speed>1.5 && delta<120000)db.execSQL("UPDATE trips SET moving_ms=moving_ms+? WHERE id=?",new Object[]{delta,trip});}
        long anchorT=prefs.getLong("anchor_t",0);
        double aLat=Double.longBitsToDouble(prefs.getLong("anchor_lat",0));
        double aLon=Double.longBitsToDouble(prefs.getLong("anchor_lon",0));
        float anchorDist=999999;
        if(anchorT!=0){float[] d=new float[1];Location.distanceBetween(aLat,aLon,p.getLatitude(),p.getLongitude(),d);anchorDist=d[0];}
        long activeStop=prefs.getLong("active_stop",0);
        // A 15m shift inside a 40m zone does not start another journey.
        boolean moved=anchorT!=0 && (anchorDist>60 || (anchorDist>40 && speed>2.5));
        SharedPreferences.Editor e=prefs.edit();
        if(anchorT==0 || moved){
            if(activeStop!=0){db.execSQL("UPDATE stops SET departure=? WHERE id=?",new Object[]{t,activeStop});e.remove("active_stop");}
            e.putLong("anchor_t",t).putLong("anchor_lat",Double.doubleToRawLongBits(p.getLatitude())).putLong("anchor_lon",Double.doubleToRawLongBits(p.getLongitude()));
        } else if(activeStop==0 && t-anchorT>=300000 && speed<1.5){
            android.content.ContentValues st=new android.content.ContentValues();st.put("trip_id",trip);st.put("arrival",anchorT);st.put("lat",aLat);st.put("lon",aLon);
            long sid=db.insert("stops",null,st);e.putLong("active_stop",sid);
        }
        e.putLong("last_t",t).putLong("last_lat",Double.doubleToRawLongBits(p.getLatitude())).putLong("last_lon",Double.doubleToRawLongBits(p.getLongitude())).apply();
    }
    private static String fmt(long t){return new SimpleDateFormat("dd.MM HH:mm",Locale.getDefault()).format(new Date(t));}
    public JSONObject summary(){
        JSONObject j=new JSONObject();long active=active();try{
            j.put("active",active!=0);j.put("distance",0);j.put("moving",0);j.put("stopped",0);j.put("gpsAge",-1);j.put("lastLat",0);j.put("lastLon",0);
            SQLiteDatabase db=getReadableDatabase();long id=active;
            if(id==0){try(Cursor c=db.rawQuery("SELECT id FROM trips ORDER BY id DESC LIMIT 1",null)){if(c.moveToFirst())id=c.getLong(0);}}
            if(id==0)return j;
            j.put("id",id);
            try(Cursor c=db.rawQuery("SELECT started,COALESCE(finished,0),km,moving_ms FROM trips WHERE id=?",new String[]{""+id})){if(c.moveToFirst()){j.put("started",fmt(c.getLong(0)));j.put("distance",c.getDouble(2));j.put("moving",c.getLong(3)/60000);}}
            try(Cursor c=db.rawQuery("SELECT COALESCE(SUM((COALESCE(departure,?)-arrival)),0) FROM stops WHERE trip_id=? AND excluded=0",new String[]{""+System.currentTimeMillis(),""+id})){if(c.moveToFirst())j.put("stopped",c.getLong(0)/60000);}
            try(Cursor c=db.rawQuery("SELECT t,lat,lon FROM points WHERE trip_id=? ORDER BY id DESC LIMIT 1",new String[]{""+id})){if(c.moveToFirst()){j.put("gpsAge",Math.max(0,(System.currentTimeMillis()-c.getLong(0))/1000));j.put("lastLat",c.getDouble(1));j.put("lastLon",c.getDouble(2));}}
            JSONArray points=new JSONArray();
            try(Cursor c=db.rawQuery("SELECT lat,lon FROM points WHERE trip_id=? ORDER BY id DESC LIMIT 450",new String[]{""+id})){while(c.moveToNext()){JSONArray p=new JSONArray();p.put(c.getDouble(0));p.put(c.getDouble(1));points.put(p);}}
            j.put("points",points);
            JSONArray stops=new JSONArray();
            try(Cursor c=db.rawQuery("SELECT id,arrival,COALESCE(departure,0),lat,lon,excluded,note FROM stops WHERE trip_id=? ORDER BY arrival DESC",new String[]{""+id})){while(c.moveToNext()){JSONObject s=new JSONObject();s.put("id",c.getLong(0));s.put("arrival",fmt(c.getLong(1)));s.put("duration",( (c.getLong(2)==0?System.currentTimeMillis():c.getLong(2))-c.getLong(1))/60000);s.put("lat",c.getDouble(3));s.put("lon",c.getDouble(4));s.put("excluded",c.getInt(5)!=0);s.put("note",c.getString(6));stops.put(s);}}
            j.put("stops",stops);
        }catch(Exception ignored){}return j;
    }
    public void exclude(long id,boolean value){getWritableDatabase().execSQL("UPDATE stops SET excluded=? WHERE id=?",new Object[]{value?1:0,id});}
    public void rename(long id,String text){android.content.ContentValues v=new android.content.ContentValues();v.put("note",text);getWritableDatabase().update("stops",v,"id=?",new String[]{""+id});}
}
