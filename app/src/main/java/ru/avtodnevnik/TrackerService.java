package ru.avtodnevnik;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;

public class TrackerService extends Service implements LocationListener {
    private LocationManager locationManager;
    private Store store;
    private static final String CHANNEL = "tracking";
    @Override public void onCreate(){
        super.onCreate();store=new Store(this);
        NotificationManager nm=getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL,"Запись маршрута",NotificationManager.IMPORTANCE_LOW));
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null && "END".equals(intent.getAction())){stopTracking();return START_NOT_STICKY;}
        if(store.active()==0){stopSelf();return START_NOT_STICKY;}
        Intent ui=new Intent(this,MainActivity.class);
        PendingIntent pi=PendingIntent.getActivity(this,0,ui,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification n=new Notification.Builder(this,CHANNEL).setContentTitle("Автодневник: идёт запись").setContentText("GPS фиксирует поездки и остановки").setSmallIcon(android.R.drawable.ic_menu_mylocation).setOngoing(true).setContentIntent(pi).build();
        try {
            if(Build.VERSION.SDK_INT>=29) startForeground(101,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            else startForeground(101,n);
            locationManager=(LocationManager)getSystemService(LOCATION_SERVICE);
            if(checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){stopSelf();return START_NOT_STICKY;}
            if(locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,4000,3,this);
            if(locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER))locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,9000,10,this);
        } catch(Exception ex){stopSelf();return START_NOT_STICKY;}
        return START_STICKY;
    }
    @Override public void onLocationChanged(Location p){try{store.onFix(p);}catch(Exception ignored){}}
    @Override public void onProviderDisabled(String provider){}
    @Override public void onProviderEnabled(String provider){}
    @Override public void onStatusChanged(String provider,int status,Bundle extras){}
    private void stopTracking(){if(locationManager!=null){try{locationManager.removeUpdates(this);}catch(Exception ignored){}}store.finish();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}
    @Override public void onDestroy(){if(locationManager!=null){try{locationManager.removeUpdates(this);}catch(Exception ignored){}}super.onDestroy();}
    @Override public IBinder onBind(Intent i){return null;}
}
