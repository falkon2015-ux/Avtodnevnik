package ru.avtodnevnik;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.location.Location;
import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Locale;

/** A route journal grouped by the local date when each driver's workday started. */
final class WaybillEngine {
    private static final Locale RUSSIAN=new Locale("ru","RU");
    private static final String DATE_FORMAT="yyyy-MM-dd";

    private WaybillEngine(){}

    static String today(){return new SimpleDateFormat(DATE_FORMAT,Locale.ROOT).format(new Date());}
    static long[] dayRange(String date){
        Calendar c=Calendar.getInstance();
        SimpleDateFormat parser=new SimpleDateFormat(DATE_FORMAT,Locale.ROOT);
        parser.setLenient(false);
        try{
            Date parsed=parser.parse(date);
            if(parsed!=null)c.setTime(parsed);
        }catch(Exception ignored){}
        c.set(Calendar.HOUR_OF_DAY,0);c.set(Calendar.MINUTE,0);
        c.set(Calendar.SECOND,0);c.set(Calendar.MILLISECOND,0);
        long from=c.getTimeInMillis();
        c.add(Calendar.DAY_OF_YEAR,1);
        return new long[]{from,c.getTimeInMillis()};
    }
    private static String dayKey(long ms){return new SimpleDateFormat(DATE_FORMAT,Locale.ROOT).format(new Date(ms));}
    private static String clock(long ms){return new SimpleDateFormat("HH:mm",RUSSIAN).format(new Date(ms));}
    static JSONArray days(Store store){
        LinkedHashSet<String> keys=new LinkedHashSet<>();
        keys.add(today());
        try(Cursor cur=store.getReadableDatabase().rawQuery("SELECT started FROM trips ORDER BY started DESC LIMIT 365",null)){
            while(cur.moveToNext()) keys.add(dayKey(cur.getLong(0)));
        }catch(Exception ignored){}
        JSONArray array=new JSONArray();
        for(String key:keys)array.put(key);
        return array;
    }
    private static String val(Cursor c,int column){
        String s=c.isNull(column)?"":c.getString(column);
        return s==null?"":s;
    }
    private static JSONObject place(String kind,long id,String city,String street,String house,
                                    int status,double lat,double lon){
        JSONObject p=new JSONObject();
        try {
            p.put("kind",kind);p.put("id",id);
            p.put("city",city);p.put("street",street);p.put("house",house);
            p.put("status",status);
            p.put("lat",lat);p.put("lon",lon);
            p.put("complete",!city.isEmpty()&&!street.isEmpty()&&!house.isEmpty());
            StringBuilder sb=new StringBuilder();
            if(!city.isEmpty())sb.append("г. ").append(city);
            if(!street.isEmpty()){if(sb.length()>0)sb.append(", ");sb.append(street);}
            if(!house.isEmpty()){if(sb.length()>0)sb.append(", ");sb.append("д. ").append(house);}
            if(sb.length()==0){
                if(lat==0&&lon==0)sb.append("Нет GPS-координат");
                else sb.append(String.format(Locale.US,"Координаты: %.5f, %.5f",lat,lon));
            }
            p.put("label",sb.toString());
        }catch(Exception ignored){}
        return p;
    }
    private static JSONObject addRow(JSONArray rows,int number,long trip,
                                     long departed,long arrived,JSONObject origin,JSONObject destination,
                                     double km,long standingMs,String note,boolean ongoing){
        JSONObject row=new JSONObject();
        try{
            row.put("number",number);row.put("tripId",trip);
            row.put("departure",clock(departed));
            row.put("arrival",clock(arrived));
            row.put("departureMs",departed);row.put("arrivalMs",arrived);
            row.put("from",origin);row.put("to",destination);
            row.put("km",Math.round(km*100.0)/100.0);
            row.put("stopMinutes",Math.max(0,standingMs/60000));
            row.put("note",note==null?"":note);
            row.put("ongoing",ongoing);
            rows.put(row);
        }catch(Exception ignored){}
        return row;
    }
    private static double intervalDistance(SQLiteDatabase db,long trip,long start,long end){
        if(end<=start)return 0;
        double metres=0;
        try(Cursor cur=db.rawQuery(
                "SELECT t,lat,lon,accuracy FROM points WHERE trip_id=? AND t>=? AND t<=? ORDER BY t",
                new String[]{""+trip,""+start,""+end})){
            long previousTime=0;double previousLat=0,previousLon=0;float previousAccuracy=0;
            while(cur.moveToNext()){
                long now=cur.getLong(0);
                double lat=cur.getDouble(1),lon=cur.getDouble(2);
                float accuracy=cur.getFloat(3);
                if(previousTime>0){
                    long dt=now-previousTime;
                    if(dt>0 && dt<=180000){
                        float[] d=new float[1];
                        Location.distanceBetween(previousLat,previousLon,lat,lon,d);
                        if(d[0]>Math.max(6,Math.max(accuracy,previousAccuracy)*0.35)
                                && d[0]<120 && d[0]/dt*1000<65)metres+=d[0];
                    }
                }
                previousTime=now;previousLat=lat;previousLon=lon;previousAccuracy=accuracy;
            }
        }catch(Exception ignored){}
        return metres/1000.0;
    }

    static JSONObject report(Store store,String date){
        String key=date!=null && date.matches("\\d{4}-\\d{2}-\\d{2}")?date:today();
        long[] bounds=dayRange(key);
        JSONArray rows=new JSONArray();
        JSONObject result=new JSONObject();
        double kmTotal=0, distanceInRows=0;
        long movingMs=0, stoppedMs=0, breakMs=0;
        int sessionCount=0;
        try{
            result.put("date",key);
            result.put("days",days(store));
            result.put("rows",rows);
            SQLiteDatabase db=store.getReadableDatabase();
            String sessionsQuery="SELECT id,started,COALESCE(finished,0),km,moving_ms,"+
                "start_lat,start_lon,start_city,start_street,start_house,start_status,"+
                "end_lat,end_lon,end_city,end_street,end_house,end_status "+
                "FROM trips WHERE started>=? AND started<? ORDER BY started ASC";
            try(Cursor sessions=db.rawQuery(sessionsQuery,new String[]{""+bounds[0],""+bounds[1]})){
                while(sessions.moveToNext()){
                    sessionCount++;
                    long id=sessions.getLong(0), started=sessions.getLong(1), ended=sessions.getLong(2);
                    kmTotal+=sessions.getDouble(3);movingMs+=sessions.getLong(4);
                    long now=System.currentTimeMillis();

                    long firstTime=0,lastTime=0;
                    double firstLat=0,firstLon=0,lastLat=0,lastLon=0;
                    try(Cursor first=db.rawQuery(
                          "SELECT t,lat,lon FROM points WHERE trip_id=? ORDER BY t ASC LIMIT 1",
                          new String[]{""+id})){
                        if(first.moveToFirst()){
                            firstTime=first.getLong(0);firstLat=first.getDouble(1);firstLon=first.getDouble(2);
                        }
                    }
                    try(Cursor last=db.rawQuery(
                          "SELECT t,lat,lon FROM points WHERE trip_id=? ORDER BY t DESC LIMIT 1",
                          new String[]{""+id})){
                        if(last.moveToFirst()){
                            lastTime=last.getLong(0);lastLat=last.getDouble(1);lastLon=last.getDouble(2);
                        }
                    }
                    JSONObject origin=place("start",id,val(sessions,7),val(sessions,8),val(sessions,9),
                            sessions.getInt(10),firstLat,firstLon);
                    JSONObject destination=place(ended==0?"current":"end",id,
                            val(sessions,13),val(sessions,14),val(sessions,15),
                            sessions.getInt(16),lastLat,lastLon);
                    long departed=started;
                    boolean stoppedCurrently=false;
                    try(Cursor stops=db.rawQuery(
                        "SELECT id,arrival,COALESCE(departure,0),lat,lon,excluded,note,city,street,house,status "+
                        "FROM stops WHERE trip_id=? ORDER BY arrival",
                        new String[]{""+id})){
                        while(stops.moveToNext()){
                            if(stops.getInt(5)!=0)continue;
                            long stopId=stops.getLong(0),arrival=stops.getLong(1),departure=stops.getLong(2);
                            long dwell=Math.max(0,(departure==0?now:departure)-arrival);
                            stoppedMs+=dwell;
                            JSONObject stop=place("stop",stopId,val(stops,7),val(stops,8),val(stops,9),
                                    stops.getInt(10),stops.getDouble(3),stops.getDouble(4));
                            if(arrival>=departed){
                                double distance=intervalDistance(db,id,departed,arrival);
                                distanceInRows+=distance;
                                addRow(rows,rows.length()+1,id,departed,arrival,origin,stop,
                                    distance,dwell,val(stops,6),departure==0);
                            }
                            origin=stop;
                            if(departure==0){stoppedCurrently=true;departed=now;}
                            else departed=departure;
                        }
                    }
                    // A final leg remains visible even when there was no 5-minute stop.
                    if(!stoppedCurrently && lastTime>0 && lastTime>=departed){
                        double distance=intervalDistance(db,id,departed,lastTime);
                        boolean hasStops=!"start".equals(origin.optString("kind"));
                        boolean isNewPlace=true;
                        if(hasStops){
                            float[] d=new float[1];
                            Location.distanceBetween(origin.optDouble("lat"),origin.optDouble("lon"),
                                    lastLat,lastLon,d);
                            isNewPlace=d[0]>=50 || distance>0.08;
                        }
                        if(!hasStops || isNewPlace){
                            distanceInRows+=distance;
                            addRow(rows,rows.length()+1,id,departed,ended!=0?ended:lastTime,
                                   origin,destination,distance,0,ended!=0?"Завершение дня":"В пути",false);
                        }
                    }
                    try(Cursor rest=db.rawQuery(
                        "SELECT started,COALESCE(ended,?) FROM breaks WHERE trip_id=?",
                        new String[]{""+now,""+id})){
                        while(rest.moveToNext())
                            breakMs+=Math.max(0,rest.getLong(1)-rest.getLong(0));
                    }
                }
            }
            JSONObject totals=new JSONObject();
            totals.put("km",Math.round(kmTotal*100.0)/100.0);
            totals.put("legsKm",Math.round(distanceInRows*100.0)/100.0);
            totals.put("movingMinutes",movingMs/60000);
            totals.put("stoppedMinutes",stoppedMs/60000);
            totals.put("breakMinutes",breakMs/60000);
            totals.put("sessions",sessionCount);
            totals.put("rows",rows.length());
            result.put("total",totals);
        }catch(Exception e){
            try{result.put("error",e.getClass().getSimpleName());}catch(Exception ignored){}
        }
        return result;
    }

    static String csv(Store store,String date){
        JSONObject obj=report(store,date);
        StringBuilder s=new StringBuilder();
        s.append("АВТОДНЕВНИК — ЖУРНАЛ МАРШРУТА ЗА ").append(obj.optString("date")).append("\r\n");
        s.append("№;Выезд;Город отправления;Улица отправления;Дом отправления;Прибытие;Город назначения;Улица назначения;Дом назначения;Пробег км;Стоянка мин;Примечание\r\n");
        JSONArray lines=obj.optJSONArray("rows");
        if(lines!=null)for(int i=0;i<lines.length();i++){
            JSONObject line=lines.optJSONObject(i);if(line==null)continue;
            JSONObject from=line.optJSONObject("from"),to=line.optJSONObject("to");
            String[] fields=new String[]{
                line.optString("number"),line.optString("departure"),
                from==null?"":from.optString("city"),from==null?"":from.optString("street"),from==null?"":from.optString("house"),
                line.optString("arrival"),to==null?"":to.optString("city"),to==null?"":to.optString("street"),to==null?"":to.optString("house"),
                String.format(Locale.US,"%.2f",line.optDouble("km")),
                line.optString("stopMinutes"),line.optString("note")
            };
            for(int n=0;n<fields.length;n++){
                if(n>0)s.append(';');
                s.append(csvCell(fields[n]));
            }
            s.append("\r\n");
        }
        JSONObject total=obj.optJSONObject("total");
        if(total!=null){
            s.append("\r\n");
            s.append("Итого пробег (GPS), км;").append(String.format(Locale.US,"%.2f",total.optDouble("km"))).append("\r\n");
            s.append("В движении, мин;").append(total.optLong("movingMinutes")).append("\r\n");
            s.append("На стоянках, мин;").append(total.optLong("stoppedMinutes")).append("\r\n");
            s.append("Перерыв и ожидание, мин;").append(total.optLong("breakMinutes")).append("\r\n");
        }
        s.append("Примечание;Это журнал поездок по GPS, а не официальный юридически оформленный путевой лист.\r\n");
        return s.toString();
    }
    private static String csvCell(String value){
        if(value==null)value="";
        String safe=value.replace("\"","\"\"");
        return (safe.contains(";")||safe.contains("\n")||safe.contains("\r")||safe.contains("\""))?"\""+safe+"\"":safe;
    }
}
