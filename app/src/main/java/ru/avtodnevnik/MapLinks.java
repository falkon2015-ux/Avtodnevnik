package ru.avtodnevnik;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;
import java.util.Locale;

/** Links to a specific GPS waypoint, never to the user's current position. */
final class MapLinks {
    private MapLinks(){}

    static String url(String provider, double lat, double lon){
        if(!Double.isFinite(lat)||!Double.isFinite(lon)
                ||Math.abs(lat)>90 || Math.abs(lon)>180 || (lat==0 && lon==0))
            throw new IllegalArgumentException("Неверные координаты остановки");
        String point=String.format(Locale.US,"%.7f,%.7f",lon,lat);
        if("2gis".equals(provider)){
            // 2GIS official public deep-link for arbitrary point: /geo/{longitude},{latitude}
            return "https://2gis.ru/geo/"+point;
        }
        if("yandex".equals(provider)){
            // Both explicit center AND visible red placemark; never uses geolocation.
            return "https://yandex.ru/maps/?ll="+point+"&pt="+point+",pm2rdm&z=18&l=map";
        }
        throw new IllegalArgumentException("Неизвестная карта");
    }

    static void open(Activity activity,String provider,double lat,double lon){
        try{
            String link=url(provider,lat,lon);
            Intent intent=new Intent(Intent.ACTION_VIEW,Uri.parse(link));
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            activity.startActivity(intent);
        }catch(ActivityNotFoundException e){
            Toast.makeText(activity,"Не найдено приложение для открытия карты",Toast.LENGTH_LONG).show();
        }catch(Exception e){
            Toast.makeText(activity,"Не удалось открыть точку на карте",Toast.LENGTH_LONG).show();
        }
    }
}
