package ru.avtodnevnik;

import android.content.Context;
import android.location.Address;
import android.location.Geocoder;
import android.os.Build;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Uses the reverse-geocoder supplied by the Android device. No paid API key.
 * Addresses are advisory; Android may return incomplete or no address.
 */
final class AddressResolver {
    interface Result { void onResult(Address address); }

    private static final ExecutorService LEGACY_WORKER=Executors.newSingleThreadExecutor();

    static void resolve(Context context, double latitude, double longitude, Result callback){
        if(!Double.isFinite(latitude)||!Double.isFinite(longitude)
            || Math.abs(latitude)>90 || Math.abs(longitude)>180
            || (latitude==0 && longitude==0)){
            callback.onResult(null);
            return;
        }
        if(!Geocoder.isPresent()){
            callback.onResult(null);
            return;
        }
        Geocoder geocoder = new Geocoder(context.getApplicationContext(), new Locale("ru","RU"));
        try{
            if(Build.VERSION.SDK_INT>=33){
                geocoder.getFromLocation(latitude,longitude,1,new Geocoder.GeocodeListener(){
                    @Override public void onGeocode(List<Address> addresses){
                        callback.onResult(addresses==null || addresses.isEmpty() ? null:addresses.get(0));
                    }
                    @Override public void onError(String message){callback.onResult(null);}
                });
            }else{
                LEGACY_WORKER.execute(()->{
                    Address result=null;
                    try{
                        List<Address> addresses=geocoder.getFromLocation(latitude,longitude,1);
                        if(addresses!=null && !addresses.isEmpty()) result=addresses.get(0);
                    }catch(Exception ignored){}
                    callback.onResult(result);
                });
            }
        }catch(Exception ignored){
            callback.onResult(null);
        }
    }

    static String city(Address address){
        if(address==null)return "";
        return safe(address.getLocality());
    }
    static String street(Address address){
        if(address==null)return "";
        return safe(address.getThoroughfare());
    }
    static String house(Address address){
        if(address==null)return "";
        return safe(address.getSubThoroughfare());
    }
    private static String safe(String input){return input==null?"":input.trim();}
}
