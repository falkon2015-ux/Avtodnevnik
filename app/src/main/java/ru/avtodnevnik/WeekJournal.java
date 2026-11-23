package ru.avtodnevnik;

import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

final class WeekJournal {
    private WeekJournal(){}
    static String monday(String anyDay){
        Calendar c=Calendar.getInstance();
        c.setTimeInMillis(WaybillEngine.dayRange(anyDay)[0]);
        int back=(c.get(Calendar.DAY_OF_WEEK)+5)%7;
        c.add(Calendar.DAY_OF_YEAR,-back);
        return iso(c);
    }
    private static String iso(Calendar c){
        return new SimpleDateFormat("yyyy-MM-dd",Locale.ROOT).format(c.getTime());
    }
    static String shift(String day,int weeks){
        Calendar c=Calendar.getInstance();
        c.setTimeInMillis(WaybillEngine.dayRange(monday(day))[0]);
        c.add(Calendar.DAY_OF_YEAR,Math.max(-520,Math.min(520,weeks))*7);
        return iso(c);
    }
    static JSONArray dates(String anyDay){
        Calendar c=Calendar.getInstance();
        c.setTimeInMillis(WaybillEngine.dayRange(monday(anyDay))[0]);
        JSONArray result=new JSONArray();
        for(int i=0;i<7;i++){result.put(iso(c));c.add(Calendar.DAY_OF_YEAR,1);}
        return result;
    }
    static JSONObject get(Store store,String anyDay){
        JSONObject report=new JSONObject();
        try{
            String first=monday(anyDay);
            report.put("from",first);
            JSONArray keys=dates(first);
            report.put("to",keys.getString(6));
            JSONArray days=new JSONArray();
            report.put("days",days);
            double km=0;
            long moving=0,standing=0,breaks=0,legs=0;
            for(int i=0;i<keys.length();i++){
                String key=keys.getString(i);
                JSONObject day=WaybillEngine.report(store,key);
                JSONObject sum=day.optJSONObject("total");
                if(sum==null)sum=new JSONObject();
                int pending=0;
                JSONArray rows=day.optJSONArray("rows");
                if(rows!=null)for(int r=0;r<rows.length();r++){
                    JSONObject row=rows.optJSONObject(r);
                    if(row==null)continue;
                    JSONObject target=row.optJSONObject("to");
                    if(target!=null&&!target.optBoolean("complete"))pending++;
                }
                JSONObject brief=new JSONObject();
                brief.put("date",key);
                brief.put("weekday",new String[]{"Пн","Вт","Ср","Чт","Пт","Сб","Вс"}[i]);
                brief.put("km",sum.optDouble("km"));
                brief.put("moving",sum.optLong("movingMinutes"));
                brief.put("stopped",sum.optLong("stoppedMinutes"));
                brief.put("breaks",sum.optLong("breakMinutes"));
                brief.put("legs",sum.optInt("rows"));
                brief.put("sessions",sum.optInt("sessions"));
                brief.put("missing",pending);
                days.put(brief);
                km+=sum.optDouble("km");moving+=sum.optLong("movingMinutes");
                standing+=sum.optLong("stoppedMinutes");breaks+=sum.optLong("breakMinutes");
                legs+=sum.optInt("rows");
            }
            JSONObject summary=new JSONObject();
            summary.put("km",Math.round(km*100)/100.0);
            summary.put("moving",moving);
            summary.put("stopped",standing);
            summary.put("breaks",breaks);
            summary.put("legs",legs);
            report.put("total",summary);
        }catch(Exception e){try{report.put("error",e.getClass().getSimpleName());}catch(Exception ignored){}}
        return report;
    }
}
