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
    private final Context appContext;
    public Store(Context c) {
        super(c,"avtodnevnik.db",null,4);
        appContext=c.getApplicationContext();
        prefs=c.getSharedPreferences("record",0);
    }
    private static String[] addressColumns(String prefix){
        return new String[]{prefix+"city TEXT DEFAULT ''",prefix+"street TEXT DEFAULT ''",
             prefix+"house TEXT DEFAULT ''",prefix+"status INTEGER DEFAULT 0"};
    }
    private static void upgradeAddress(SQLiteDatabase db,String table,String prefix){
        for(String field:addressColumns(prefix))db.execSQL("ALTER TABLE "+table+" ADD COLUMN "+field);
    }

    @Override public void onCreate(SQLiteDatabase db){
        db.execSQL("CREATE TABLE trips(id INTEGER PRIMARY KEY AUTOINCREMENT, started INTEGER NOT NULL, finished INTEGER, km REAL DEFAULT 0, moving_ms INTEGER DEFAULT 0)");
        db.execSQL("CREATE TABLE points(id INTEGER PRIMARY KEY AUTOINCREMENT,trip_id INTEGER, t INTEGER,lat REAL,lon REAL,accuracy REAL,speed REAL)");
        db.execSQL("CREATE TABLE stops(id INTEGER PRIMARY KEY AUTOINCREMENT,trip_id INTEGER,arrival INTEGER,departure INTEGER,lat REAL,lon REAL,excluded INTEGER DEFAULT 0,note TEXT DEFAULT '')");
        db.execSQL("CREATE TABLE breaks(id INTEGER PRIMARY KEY AUTOINCREMENT,trip_id INTEGER NOT NULL,started INTEGER NOT NULL,ended INTEGER)");
        for(String field:new String[]{"start_lat REAL", "start_lon REAL", "end_lat REAL", "end_lon REAL"}){
            db.execSQL("ALTER TABLE trips ADD COLUMN "+field);
        }
        upgradeAddress(db,"trips","start_");
        upgradeAddress(db,"trips","end_");
        upgradeAddress(db,"stops","");
        upgradeMatchMeta(db,"trips","start_");
        upgradeMatchMeta(db,"trips","end_");
        upgradeMatchMeta(db,"stops","");
    }
    private static void upgradeMatchMeta(SQLiteDatabase db,String table,String prefix){
        db.execSQL("ALTER TABLE "+table+" ADD COLUMN "+prefix+"source TEXT DEFAULT ''");
        db.execSQL("ALTER TABLE "+table+" ADD COLUMN "+prefix+"distance REAL DEFAULT -1");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldV,int newV){
        if(oldV<2)db.execSQL("CREATE TABLE IF NOT EXISTS breaks(id INTEGER PRIMARY KEY AUTOINCREMENT,trip_id INTEGER NOT NULL,started INTEGER NOT NULL,ended INTEGER)");
        if(oldV<3){
            for(String field:new String[]{"start_lat REAL","start_lon REAL","end_lat REAL","end_lon REAL"})
                db.execSQL("ALTER TABLE trips ADD COLUMN "+field);
            upgradeAddress(db,"trips","start_");
            upgradeAddress(db,"trips","end_");
            upgradeAddress(db,"stops","");
        }
        if(oldV<4){
            upgradeMatchMeta(db,"trips","start_");
            upgradeMatchMeta(db,"trips","end_");
            upgradeMatchMeta(db,"stops","");
            // Old Android reverse geocodes were not checked against a 40m radius.
            // Recheck automatic suggestions, but never erase a user-edited address (4).
            db.execSQL("UPDATE stops SET status=0,city='',street='',house='' WHERE status IN (1,2,3)");
            db.execSQL("UPDATE trips SET start_status=0,start_city='',start_street='',start_house='' WHERE start_status IN (1,2,3)");
            db.execSQL("UPDATE trips SET end_status=0,end_city='',end_street='',end_house='' WHERE end_status IN (1,2,3)");
        }
    }
    public long active(){return prefs.getLong("active",0);}
    public boolean paused(){return active()!=0 && prefs.getLong("pause_id",0)!=0;}
    private void resetPosition(SharedPreferences.Editor e){
        e.remove("anchor_lat").remove("anchor_lon").remove("anchor_t").remove("last_t").remove("last_lat").remove("last_lon");
    }
    public synchronized void pause(){
        long trip=active();if(trip==0||paused())return;
        long now=System.currentTimeMillis();
        long stop=prefs.getLong("active_stop",0);
        if(stop!=0)getWritableDatabase().execSQL("UPDATE stops SET departure=? WHERE id=?",new Object[]{now,stop});
        android.content.ContentValues v=new android.content.ContentValues();
        v.put("trip_id",trip);v.put("started",now);
        long breakId=getWritableDatabase().insertOrThrow("breaks",null,v);
        SharedPreferences.Editor e=prefs.edit();e.putLong("pause_id",breakId).remove("active_stop");resetPosition(e);e.apply();
    }
    public synchronized void resume(){
        if(!paused())return;
        getWritableDatabase().execSQL("UPDATE breaks SET ended=? WHERE id=?",new Object[]{System.currentTimeMillis(),prefs.getLong("pause_id",0)});
        SharedPreferences.Editor e=prefs.edit().remove("pause_id");resetPosition(e);e.apply();
    }
    public long begin(){if(active()!=0)return active(); SQLiteDatabase db=getWritableDatabase(); android.content.ContentValues v=new android.content.ContentValues();v.put("started",System.currentTimeMillis());long id=db.insertOrThrow("trips",null,v);prefs.edit().putLong("active",id).remove("anchor_lat").remove("anchor_lon").remove("anchor_t").remove("last_t").remove("last_lat").remove("last_lon").remove("active_stop").apply();return id;}
    public synchronized void finish(){
        long id=active();if(id==0)return;
        long now=System.currentTimeMillis();
        if(paused())resume();
        long stop=prefs.getLong("active_stop",0);
        SQLiteDatabase db=getWritableDatabase();
        if(stop!=0)db.execSQL("UPDATE stops SET departure=? WHERE id=?",new Object[]{now,stop});
        db.execSQL("UPDATE trips SET finished=? WHERE id=?",new Object[]{now,id});
        // Resolve the final address once, from the most recent valid GPS position.
        try(Cursor c=db.rawQuery("SELECT lat,lon FROM points WHERE trip_id=? ORDER BY t DESC LIMIT 1",new String[]{""+id})){
            if(c.moveToFirst()){
                double lat=c.getDouble(0),lon=c.getDouble(1);
                db.execSQL("UPDATE trips SET end_lat=?,end_lon=? WHERE id=?",new Object[]{lat,lon,id});
                resolveAddress("end",id,lat,lon);
            }
        }
        prefs.edit().clear().apply();
    }
    public synchronized void onFix(Location p){
        long trip=active();if(trip==0 || paused() || p.getAccuracy()>55 || p.getAccuracy()<=0)return;
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
        // A new session gets an address at its first accurate GPS fix.
        try(Cursor c=db.rawQuery("SELECT start_lat FROM trips WHERE id=?",new String[]{""+trip})){
            if(c.moveToFirst() && c.isNull(0)){
                db.execSQL("UPDATE trips SET start_lat=?,start_lon=? WHERE id=?",new Object[]{p.getLatitude(),p.getLongitude(),trip});
                resolveAddress("start",trip,p.getLatitude(),p.getLongitude());
            }
        }
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
            long sid=db.insert("stops",null,st);
            e.putLong("active_stop",sid);
            if(sid>0)resolveAddress("stop",sid,aLat,aLon);
        }
        e.putLong("last_t",t).putLong("last_lat",Double.doubleToRawLongBits(p.getLatitude())).putLong("last_lon",Double.doubleToRawLongBits(p.getLongitude())).apply();
    }
    private static String fmt(long t){return new SimpleDateFormat("dd.MM HH:mm",Locale.getDefault()).format(new Date(t));}
    public JSONObject summary(){
        JSONObject j=new JSONObject();long active=active();try{
            j.put("active",active!=0);j.put("paused",paused());j.put("break_minutes",0);j.put("distance",0);j.put("moving",0);j.put("stopped",0);j.put("gpsAge",-1);j.put("lastLat",0);j.put("lastLon",0);
            SQLiteDatabase db=getReadableDatabase();long id=active;
            long[] today=WaybillEngine.dayRange(WaybillEngine.today());
            j.put("todaySessions",0);
            try(Cursor count=db.rawQuery("SELECT COUNT(*) FROM trips WHERE started>=? AND started<?",
                    new String[]{""+today[0],""+today[1]})){
                if(count.moveToFirst())j.put("todaySessions",count.getInt(0));
            }
            if(id==0){try(Cursor c=db.rawQuery("SELECT id FROM trips ORDER BY id DESC LIMIT 1",null)){if(c.moveToFirst())id=c.getLong(0);}}
            if(id==0)return j;
            j.put("id",id);
            try(Cursor c=db.rawQuery("SELECT started,COALESCE(finished,0),km,moving_ms FROM trips WHERE id=?",new String[]{""+id})){if(c.moveToFirst()){j.put("started",fmt(c.getLong(0)));j.put("distance",c.getDouble(2));j.put("moving",c.getLong(3)/60000);}}
            try(Cursor c=db.rawQuery("SELECT COALESCE(SUM((COALESCE(departure,?)-arrival)),0) FROM stops WHERE trip_id=? AND excluded=0",new String[]{""+System.currentTimeMillis(),""+id})){if(c.moveToFirst())j.put("stopped",c.getLong(0)/60000);}
            try(Cursor c=db.rawQuery("SELECT COALESCE(SUM(COALESCE(ended,?)-started),0) FROM breaks WHERE trip_id=?",new String[]{""+System.currentTimeMillis(),""+id})){if(c.moveToFirst())j.put("break_minutes",c.getLong(0)/60000);}
            try(Cursor c=db.rawQuery("SELECT t,lat,lon FROM points WHERE trip_id=? ORDER BY id DESC LIMIT 1",new String[]{""+id})){if(c.moveToFirst()){j.put("gpsAge",Math.max(0,(System.currentTimeMillis()-c.getLong(0))/1000));j.put("lastLat",c.getDouble(1));j.put("lastLon",c.getDouble(2));}}
            JSONArray points=new JSONArray();
            try(Cursor c=db.rawQuery("SELECT lat,lon FROM points WHERE trip_id=? ORDER BY id DESC LIMIT 450",new String[]{""+id})){while(c.moveToNext()){JSONArray p=new JSONArray();p.put(c.getDouble(0));p.put(c.getDouble(1));points.put(p);}}
            j.put("points",points);
            JSONArray stops=new JSONArray();
            try(Cursor c=db.rawQuery("SELECT id,arrival,COALESCE(departure,0),lat,lon,excluded,note,city,street,house,status,source,distance FROM stops WHERE trip_id=? ORDER BY arrival DESC",new String[]{""+id})){while(c.moveToNext()){JSONObject s=new JSONObject();s.put("id",c.getLong(0));s.put("arrival",fmt(c.getLong(1)));s.put("duration",( (c.getLong(2)==0?System.currentTimeMillis():c.getLong(2))-c.getLong(1))/60000);s.put("lat",c.getDouble(3));s.put("lon",c.getDouble(4));s.put("excluded",c.getInt(5)!=0);s.put("note",c.getString(6));
                    String city=c.getString(7),street=c.getString(8),house=c.getString(9);
                    s.put("address",(
                        (city==null||city.isEmpty()?"":city+", ")+
                        (street==null||street.isEmpty()?"":street+", ")+
                        (house==null||house.isEmpty()?"":"д. "+house)
                    ).replaceAll(", $",""));
                    s.put("addressStatus",c.getInt(10));
                    s.put("addressSource",c.getString(11));
                    s.put("addressDistance",c.getDouble(12));stops.put(s);}}
            j.put("stops",stops);
        }catch(Exception ignored){}return j;
    }


    /**
     * Find the nearest mapped address within 40m of the actual GPS point.
     * A city-only or street-only reverse geocode is not treated as a building address.
     */
    public synchronized void resolveAddress(String kind,long id,double lat,double lon){
        String table="stop".equals(kind)?"stops":"trips";
        String prefix="stop".equals(kind)?"":("end".equals(kind)?"end_":"start_");
        if(!"stop".equals(kind)&&!"start".equals(kind)&&!"end".equals(kind))return;
        android.content.ContentValues pending=new android.content.ContentValues();
        pending.put(prefix+"status",1);
        int updated=getWritableDatabase().update(table,pending,
            "id=? AND "+prefix+"status NOT IN (1,4)",new String[]{""+id});
        if(updated!=1)return; // Already resolving or manually corrected.
        NearbyAddressResolver.lookup(appContext,lat,lon,address->{
            try{
                android.content.ContentValues result=new android.content.ContentValues();
                if(address!=null && !address.street.isEmpty() && !address.house.isEmpty()){
                    result.put(prefix+"city",address.city);
                    result.put(prefix+"street",address.street);
                    result.put(prefix+"house",address.house);
                    result.put(prefix+"source",address.source);
                    result.put(prefix+"distance",address.distanceMetres);
                    result.put(prefix+"status",2);
                }else{
                    result.put(prefix+"city","");
                    result.put(prefix+"street","");
                    result.put(prefix+"house","");
                    result.put(prefix+"source","");
                    result.put(prefix+"distance",-1);
                    result.put(prefix+"status",3); // No verified nearby house.
                }
                // Ignore a late response if the driver has since edited that address.
                getWritableDatabase().update(table,result,
                    "id=? AND "+prefix+"status=1",new String[]{""+id});
            }catch(Exception ignored){}
        });
    }
    public synchronized void editAddress(String kind,long id,String city,String street,String house){
        if(!"stop".equals(kind)&&!"start".equals(kind)&&!"end".equals(kind))return;
        String table="stop".equals(kind)?"stops":"trips";
        String prefix="stop".equals(kind)?"":("end".equals(kind)?"end_":"start_");
        android.content.ContentValues v=new android.content.ContentValues();
        v.put(prefix+"city",clean(city));v.put(prefix+"street",clean(street));
        v.put(prefix+"house",clean(house));v.put(prefix+"status",4);v.put(prefix+"source","Вручную");v.put(prefix+"distance",-1);
        getWritableDatabase().update(table,v,"id=?",new String[]{""+id});
    }
    private static String clean(String value){return value==null?"":value.trim().substring(0,Math.min(180,value.trim().length()));}
    public void retryAddress(String kind,long id){
        if(!"stop".equals(kind)&&!"start".equals(kind)&&!"end".equals(kind))return;
        String table="stop".equals(kind)?"stops":"trips";
        String column="stop".equals(kind)?"":kind+"_";
        String q="stop".equals(kind)?"SELECT lat,lon FROM stops WHERE id=?":
            "SELECT "+column+"lat,"+column+"lon FROM trips WHERE id=?";
        try(Cursor c=getReadableDatabase().rawQuery(q,new String[]{""+id})){
            if(c.moveToFirst() && !c.isNull(0) && !c.isNull(1)){
                // Explicit retry is allowed to replace an old manually entered address.
                getWritableDatabase().execSQL("UPDATE "+table+" SET "+column+"status=0 WHERE id=?",new Object[]{id});
                resolveAddress(kind,id,c.getDouble(0),c.getDouble(1));
            }
        }
    }
    /** Populate geocodes for older saved trips after the v2 to v3 database migration. */
    public void backfillAddresses(String day){
        long[] range=WaybillEngine.dayRange(day);
        SQLiteDatabase db=getReadableDatabase();
        try(Cursor trips=db.rawQuery(
            "SELECT id,start_lat,end_lat FROM trips WHERE started>=? AND started<? ORDER BY id",
            new String[]{""+range[0],""+range[1]})){
            while(trips.moveToNext()){
                long id=trips.getLong(0);
                if(trips.isNull(1)){
                    try(Cursor first=db.rawQuery("SELECT lat,lon FROM points WHERE trip_id=? ORDER BY t LIMIT 1",
                        new String[]{""+id})){
                        if(first.moveToFirst()){
                            double lat=first.getDouble(0),lon=first.getDouble(1);
                            db.execSQL("UPDATE trips SET start_lat=?,start_lon=? WHERE id=?",new Object[]{lat,lon,id});
                        }
                    }
                }
                if(trips.isNull(2)){
                    try(Cursor last=db.rawQuery("SELECT lat,lon FROM points WHERE trip_id=? ORDER BY t DESC LIMIT 1",
                        new String[]{""+id})){
                        if(last.moveToFirst()){
                            db.execSQL("UPDATE trips SET end_lat=?,end_lon=? WHERE id=?",new Object[]{
                                last.getDouble(0),last.getDouble(1),id});
                        }
                    }
                }
                try(Cursor candidates=db.rawQuery("SELECT start_lat,start_lon,start_status,end_lat,end_lon,end_status FROM trips WHERE id=?",
                        new String[]{""+id})){
                    if(candidates.moveToFirst()){
                        if(!candidates.isNull(0)&&candidates.getInt(2)==0)
                            resolveAddress("start",id,candidates.getDouble(0),candidates.getDouble(1));
                        if(!candidates.isNull(3)&&candidates.getInt(5)==0)
                            resolveAddress("end",id,candidates.getDouble(3),candidates.getDouble(4));
                    }
                }
                try(Cursor stops=db.rawQuery("SELECT id,lat,lon FROM stops WHERE trip_id=? AND status=0",
                    new String[]{""+id})){
                    while(stops.moveToNext()){
                        resolveAddress("stop",stops.getLong(0),stops.getDouble(1),stops.getDouble(2));
                    }
                }
            }
        }
    }
    public org.json.JSONArray reportDays(){return WaybillEngine.days(this);}
    public org.json.JSONObject dayReport(String day){return WaybillEngine.report(this,day);}

    /**
     * Route data for the native in-app map. GPS positions are stored locally and do not
     * require internet. Long gaps and explicitly paused intervals split the polyline,
     * so the map does not draw a fictitious road between separate journeys.
     */
    public synchronized JSONObject mapSnapshot() {
        JSONObject all=new JSONObject();
        try{
            JSONArray segments=new JSONArray(),stops=new JSONArray();
            all.put("segments",segments);all.put("stops",stops);
            SQLiteDatabase db=getReadableDatabase();
            long[] window=WaybillEngine.dayRange(WaybillEngine.today());
            try(Cursor cnt=db.rawQuery("SELECT COUNT(*) FROM trips WHERE started>=? AND started<?",
                new String[]{""+window[0],""+window[1]})){
                if(cnt.moveToFirst() && cnt.getInt(0)==0){
                    try(Cursor latest=db.rawQuery("SELECT MAX(started) FROM trips",null)){
                        if(latest.moveToFirst()&&!latest.isNull(0)){
                            String day=new SimpleDateFormat("yyyy-MM-dd",Locale.ROOT)
                                .format(new Date(latest.getLong(0)));
                            window=WaybillEngine.dayRange(day);
                        }
                    }
                }
            }
            try(Cursor periods=db.rawQuery("SELECT id FROM trips WHERE started>=? AND started<? ORDER BY started",
                new String[]{""+window[0],""+window[1]})){
                while(periods.moveToNext()){
                    JSONObject single=singleTripMapSnapshot(periods.getLong(0));
                    JSONArray pieces=single.optJSONArray("segments"),marks=single.optJSONArray("stops");
                    if(pieces!=null)for(int i=0;i<pieces.length();i++)segments.put(pieces.optJSONArray(i));
                    if(marks!=null)for(int i=0;i<marks.length();i++)stops.put(marks.optJSONObject(i));
                }
            }
        }catch(Exception ignored){}
        return all;
    }
    private JSONObject singleTripMapSnapshot(long id) {
        JSONObject out=new JSONObject();
        try {
            JSONArray segments=new JSONArray();
            JSONArray stops=new JSONArray();
            out.put("segments",segments);
            out.put("stops",stops);
            SQLiteDatabase db=getReadableDatabase();
            String idArg=Long.toString(id);
            java.util.ArrayList<long[]> pauses=new java.util.ArrayList<>();
            try(Cursor cur=db.rawQuery("SELECT started, COALESCE(ended,?) FROM breaks WHERE trip_id=? ORDER BY started",
                    new String[]{Long.toString(System.currentTimeMillis()),idArg})){
                while(cur.moveToNext()) pauses.add(new long[]{cur.getLong(0),cur.getLong(1)});
            }
            try(Cursor cur=db.rawQuery("SELECT t,lat,lon FROM points WHERE trip_id=? ORDER BY id ASC",
                    new String[]{idArg})){
                int total=cur.getCount();
                int stride=Math.max(1,(total+2999)/3000);
                JSONArray line=new JSONArray();
                long previous=0;
                int index=0;
                int pauseIndex=0;
                while(cur.moveToNext()){
                    int pointIndex=index++;
                    if(pointIndex%stride!=0 && pointIndex!=total-1) continue;
                    long timestamp=cur.getLong(0);
                    double lat=cur.getDouble(1), lon=cur.getDouble(2);
                    if(lat < -90 || lat > 90 || lon < -180 || lon > 180)continue;
                    while(pauseIndex<pauses.size() && pauses.get(pauseIndex)[1]<previous)pauseIndex++;
                    boolean crossedPause=false;
                    for(int k=pauseIndex;k<pauses.size();k++){
                        long[] pause=pauses.get(k);
                        if(pause[0]>timestamp)break;
                        if(previous>0 && previous<=pause[1] && timestamp>=pause[0]){
                            crossedPause=true;break;
                        }
                    }
                    if(previous>0 && (timestamp-previous>180000 || crossedPause)){
                        if(line.length()>0)segments.put(line);
                        line=new JSONArray();
                    }
                    JSONArray point=new JSONArray();
                    point.put(lat);point.put(lon);
                    line.put(point);
                    previous=timestamp;
                }
                if(line.length()>0)segments.put(line);
            }
            try(Cursor cur=db.rawQuery("SELECT id,arrival,COALESCE(departure,0),lat,lon,excluded,note FROM stops WHERE trip_id=? ORDER BY arrival",
                    new String[]{idArg})){
                while(cur.moveToNext()){
                    JSONObject stop=new JSONObject();
                    stop.put("id",cur.getLong(0));
                    stop.put("arrival",fmt(cur.getLong(1)));
                    long ended=cur.getLong(2);
                    stop.put("duration",Math.max(0,((ended==0?System.currentTimeMillis():ended)-cur.getLong(1))/60000));
                    stop.put("lat",cur.getDouble(3));
                    stop.put("lon",cur.getDouble(4));
                    stop.put("excluded",cur.getInt(5)!=0);
                    stop.put("note",cur.getString(6));
                    stops.put(stop);
                }
            }
        } catch(Exception ignored) {}
        return out;
    }

    public void exclude(long id,boolean value){getWritableDatabase().execSQL("UPDATE stops SET excluded=? WHERE id=?",new Object[]{value?1:0,id});}
    public void rename(long id,String text){android.content.ContentValues v=new android.content.ContentValues();v.put("note",text);getWritableDatabase().update("stops",v,"id=?",new String[]{""+id});}
}
