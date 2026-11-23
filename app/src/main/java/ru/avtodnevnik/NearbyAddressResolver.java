package ru.avtodnevnik;

import android.content.Context;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Verifiable address lookup for a GPS stop.
 * Only accepts an actual street+house matching an OSM address feature within 40m
 * of the GPS location (a polygon can be intersected, not just its center).
 * Public Overpass infrastructure is for light personal use, not production loads.
 */
final class NearbyAddressResolver {
    static final double MAX_METRES = 40.0;
    private static final String SERVICE = "https://overpass-api.de/api/interpreter";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static long lastNetworkAttempt = 0L;

    static final class Match {
        final String city, street, house, source;
        final double distanceMetres;
        Match(String city,String street,String house,String source,double metres){
            this.city=city==null?"":city.trim();
            this.street=street==null?"":street.trim();
            this.house=house==null?"":house.trim();
            this.source=source;
            this.distanceMetres=metres;
        }
    }
    interface Callback { void finish(Match address); }

    static void lookup(Context context,double lat,double lon,Callback callback){
        if(!Double.isFinite(lat)||!Double.isFinite(lon)
                ||Math.abs(lat)>90||Math.abs(lon)>180||(lat==0 && lon==0)){
            callback.finish(null);return;
        }
        Context app=context.getApplicationContext();
        EXECUTOR.execute(()->{
            Match result=null;
            try{result=overpass(lat,lon);}catch(Exception ignored){}
            // The device's geocoder may know an address that has not been mapped in OSM.
            // However it is accepted only when it supplies coordinates of the matched
            // address, an explicit street and house and <=40m distance.
            if(result==null){
                try{result=deviceGeocoder(app,lat,lon);}catch(Exception ignored){}
            }
            if(result!=null && result.city.isEmpty()){
                try{
                    String city=locality(app,lat,lon);
                    if(!city.isEmpty())result=new Match(city,result.street,result.house,result.source,result.distanceMetres);
                }catch(Exception ignored){}
            }
            callback.finish(result);
        });
    }

    private static Match overpass(double lat,double lon) throws Exception{
        // Overpass around() performs a spatial intersection test on ways and nodes.
        String latStr=String.format(Locale.US,"%.7f",lat),lonStr=String.format(Locale.US,"%.7f",lon);
        String q="[out:json][timeout:12];nwr(around:40,"+latStr+","+lonStr+
                ")[\"addr:housenumber\"];out geom;";
        // Space public API queries 3 seconds apart; no request is made per GPS fix.
        long elapsed=System.currentTimeMillis()-lastNetworkAttempt;
        if(elapsed<3000L)Thread.sleep(3000L-elapsed);
        lastNetworkAttempt=System.currentTimeMillis();
        byte[] body=("data="+URLEncoder.encode(q,"UTF-8")).getBytes(StandardCharsets.UTF_8);
        HttpURLConnection conn=(HttpURLConnection)new URL(SERVICE).openConnection();
        try{
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type","application/x-www-form-urlencoded; charset=UTF-8");
            conn.setRequestProperty("User-Agent","Avtodnevnik-Android/0.5 (https://github.com/falkon2015-ux/Avtodnevnik)");
            conn.setRequestProperty("Accept","application/json");
            conn.setFixedLengthStreamingMode(body.length);
            try(OutputStream output=conn.getOutputStream()){output.write(body);}
            if(conn.getResponseCode()!=200)throw new java.io.IOException("Overpass HTTP "+conn.getResponseCode());
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try(InputStream input=conn.getInputStream()){
                byte[] chunk=new byte[4096];int n;
                while((n=input.read(chunk))!=-1){
                    bytes.write(chunk,0,n);
                    if(bytes.size()>1000000)throw new java.io.IOException("Response too large");
                }
            }
            JSONObject response=new JSONObject(bytes.toString("UTF-8"));
            JSONArray entries=response.optJSONArray("elements");
            if(entries==null)return null;
            Match nearest=null;
            for(int i=0;i<entries.length();i++){
                JSONObject element=entries.optJSONObject(i);
                if(element==null)continue;
                JSONObject tags=element.optJSONObject("tags");
                if(tags==null)continue;
                String house=tags.optString("addr:housenumber","").trim();
                String street=tags.optString("addr:street","").trim();
                if(street.isEmpty())street=tags.optString("addr:place","").trim();
                if(house.isEmpty()||street.isEmpty())continue;
                double metres=featureDistance(lat,lon,element);
                if(!Double.isFinite(metres)||metres>MAX_METRES)continue;
                String city=tags.optString("addr:city","").trim();
                if(city.isEmpty())city=tags.optString("addr:town","").trim();
                if(city.isEmpty())city=tags.optString("addr:village","").trim();
                if(city.isEmpty())city=tags.optString("addr:hamlet","").trim();
                if(nearest==null || metres<nearest.distanceMetres){
                    nearest=new Match(city,street,house,"OSM",metres);
                }
            }
            return nearest;
        }finally{conn.disconnect();}
    }

    private static Match deviceGeocoder(Context c,double lat,double lon) throws Exception{
        if(!Geocoder.isPresent())return null;
        List<Address> addresses=new Geocoder(c,new Locale("ru","RU")).getFromLocation(lat,lon,8);
        if(addresses==null)return null;
        Match closest=null;
        for(Address a:addresses){
            if(a==null || !a.hasLatitude() || !a.hasLongitude())continue;
            String street=value(a.getThoroughfare()),house=value(a.getSubThoroughfare());
            if(street.isEmpty()||house.isEmpty())continue;
            double d=distance(lat,lon,a.getLatitude(),a.getLongitude());
            if(!Double.isFinite(d) || d>MAX_METRES)continue;
            Match match=new Match(value(a.getLocality()),street,house,"Android",d);
            if(closest==null || d<closest.distanceMetres)closest=match;
        }
        return closest;
    }
    private static String locality(Context c,double lat,double lon) throws Exception{
        if(!Geocoder.isPresent())return "";
        List<Address> list=new Geocoder(c,new Locale("ru","RU")).getFromLocation(lat,lon,1);
        if(list==null||list.isEmpty()||list.get(0)==null)return "";
        return value(list.get(0).getLocality());
    }
    private static String value(String s){return s==null?"":s.trim();}
    private static double distance(double aLat,double aLon,double bLat,double bLon){
        float[] d=new float[1];Location.distanceBetween(aLat,aLon,bLat,bLon,d);return d[0];
    }

    private static double featureDistance(double lat,double lon,JSONObject element){
        double best=Double.POSITIVE_INFINITY;
        if(element.has("lat") && element.has("lon")){
            best=Math.min(best,distance(lat,lon,element.optDouble("lat"),element.optDouble("lon")));
        }
        JSONArray geometry=element.optJSONArray("geometry");
        if(geometry!=null)best=Math.min(best,shapeDistance(lat,lon,geometry));
        JSONArray members=element.optJSONArray("members");
        if(members!=null)for(int i=0;i<members.length();i++){
            JSONObject member=members.optJSONObject(i);
            if(member==null)continue;
            JSONArray g=member.optJSONArray("geometry");
            if(g!=null)best=Math.min(best,shapeDistance(lat,lon,g));
            if(member.has("lat")&&member.has("lon"))
                best=Math.min(best,distance(lat,lon,member.optDouble("lat"),member.optDouble("lon")));
        }
        // Deliberately do not use element.center: a polygon's centroid can be far
        // from the building's nearest edge and over 40m away.
        return best;
    }

    /** Local planar approximation accurate to <<1m over a 40m neighbourhood. */
    private static double shapeDistance(double lat,double lon,JSONArray g){
        int n=g.length();
        if(n==0)return Double.POSITIVE_INFINITY;
        double[][] points=new double[n][2];
        double latScale=111132.92;
        double lonScale=111320.0*Math.cos(Math.toRadians(lat));
        double best=Double.POSITIVE_INFINITY;
        for(int i=0;i<n;i++){
            JSONObject p=g.optJSONObject(i);
            if(p==null || !p.has("lat")||!p.has("lon"))return Double.POSITIVE_INFINITY;
            points[i][0]=(p.optDouble("lon")-lon)*lonScale;
            points[i][1]=(p.optDouble("lat")-lat)*latScale;
            best=Math.min(best,Math.hypot(points[i][0],points[i][1]));
            if(i>0)best=Math.min(best,segmentDistance(0,0,points[i-1],points[i]));
        }
        if(n>=4 && Math.hypot(points[0][0]-points[n-1][0],points[0][1]-points[n-1][1])<1.0){
            // Test whether parked GPS is inside a closed address-bearing building.
            boolean inside=false;
            for(int i=0,j=n-1;i<n;j=i++){
                double xi=points[i][0],yi=points[i][1],xj=points[j][0],yj=points[j][1];
                if(((yi>0)!=(yj>0)) && 0<(xj-xi)*(-yi)/(yj-yi)+xi) inside=!inside;
            }
            if(inside)return 0;
            best=Math.min(best,segmentDistance(0,0,points[n-1],points[0]));
        }
        return best;
    }
    private static double segmentDistance(double x,double y,double[] a,double[] b){
        double vx=b[0]-a[0], vy=b[1]-a[1];
        double length=vx*vx+vy*vy;
        if(length<1e-10)return Math.hypot(a[0]-x,a[1]-y);
        double t=((x-a[0])*vx+(y-a[1])*vy)/length;
        t=Math.max(0,Math.min(1,t));
        return Math.hypot(a[0]+t*vx-x,a[1]+t*vy-y);
    }
}
