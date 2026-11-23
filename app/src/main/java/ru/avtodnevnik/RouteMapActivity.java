package ru.avtodnevnik;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.layers.CircleLayer;
import org.maplibre.android.style.layers.LineLayer;
import org.maplibre.android.style.layers.Layer;
import org.maplibre.android.style.layers.SymbolLayer;
import org.maplibre.android.style.expressions.Expression;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.FeatureCollection;
import org.maplibre.geojson.LineString;
import org.maplibre.geojson.Point;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.maplibre.android.style.layers.PropertyFactory.circleColor;
import static org.maplibre.android.style.layers.PropertyFactory.circleRadius;
import static org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor;
import static org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth;
import static org.maplibre.android.style.layers.PropertyFactory.lineColor;
import static org.maplibre.android.style.layers.PropertyFactory.lineWidth;
import static org.maplibre.android.style.layers.PropertyFactory.textField;
import static org.maplibre.android.style.layers.PropertyFactory.textSize;
import static org.maplibre.android.style.layers.PropertyFactory.textColor;
import static org.maplibre.android.style.layers.PropertyFactory.textHaloColor;
import static org.maplibre.android.style.layers.PropertyFactory.textHaloWidth;

public class RouteMapActivity extends Activity {
    private static final String STYLE = "https://tiles.openfreemap.org/styles/liberty";
    private static final int NAVY = Color.rgb(20, 57, 75);
    private static final int MINT = Color.rgb(31, 166, 143);
    private Store store;
    private VoiceGuide voice;
    private MapView mapView;
    private MapLibreMap map;
    private boolean styleReady = false;
    private boolean cameraSet = false;
    private double selectedLat=Double.NaN,selectedLon=Double.NaN;
    private TextView title;
    private TextView subtitle;
    private LinearLayout stopItems;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (styleReady) showRoute(false);
            handler.postDelayed(this, 15000L);
        }
    };

    private int dp(float n){return (int)(n*getResources().getDisplayMetrics().density+0.5f);}
    private GradientDrawable surface(int color,int corners){
        GradientDrawable d=new GradientDrawable();
        d.setColor(color);d.setCornerRadius(dp(corners));return d;
    }
    private TextView txt(String s,int size,int color,boolean bold){
        TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);
        if(bold)t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        store=new Store(this);
        voice=new VoiceGuide(this);
        MapLibre.getInstance(this);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(243,248,247));

        LinearLayout bar=new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(13),dp(14),dp(18),dp(14));
        bar.setBackgroundColor(NAVY);
        Button back=new Button(this);
        back.setText("‹");
        back.setTextSize(26);
        back.setTextColor(Color.WHITE);
        back.setAllCaps(false);
        back.setBackground(surface(Color.rgb(41,83,96),14));
        back.setOnClickListener(v->finish());
        bar.addView(back,new LinearLayout.LayoutParams(dp(47),dp(46)));

        LinearLayout headings=new LinearLayout(this);
        headings.setOrientation(LinearLayout.VERTICAL);
        headings.setPadding(dp(12),0,0,0);
        title=txt("Карта маршрута",20,Color.WHITE,true);
        subtitle=txt("Загрузка карты...",12,Color.rgb(192,227,220),false);
        headings.addView(title);
        headings.addView(subtitle);
        bar.addView(headings,new LinearLayout.LayoutParams(0,-2,1f));
        root.addView(bar);

        FrameLayout mapFrame=new FrameLayout(this);
        mapView=new MapView(this);
        mapFrame.addView(mapView,new FrameLayout.LayoutParams(-1,-1));
        root.addView(mapFrame,new LinearLayout.LayoutParams(-1,0,1f));

        LinearLayout bottom=new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(16),dp(12),dp(16),dp(18));
        bottom.setBackground(surface(Color.WHITE,20));
        TextView legend=txt("● Маршрут   ● Стоянки   ● GPS   ·   Дома при приближении",12,NAVY,true);
        bottom.addView(legend);
        TextView info=txt("Нажмите на стоянку и выберите Яндекс Карты или 2ГИС для проверки адреса и номера дома. Детальная карта откроется отдельно.",12,Color.rgb(107,128,132),false);
        info.setPadding(0,dp(9),0,dp(8));
        bottom.addView(info);
        LinearLayout mapOptions=new LinearLayout(this);
        mapOptions.setOrientation(LinearLayout.HORIZONTAL);
        mapOptions.setPadding(0,dp(5),0,dp(8));
        Button yandex=new Button(this);
        yandex.setText("Яндекс Карты ↗");
        yandex.setAllCaps(false);
        yandex.setTextSize(12);
        yandex.setTextColor(NAVY);
        yandex.setBackground(surface(Color.rgb(219,237,244),12));
        yandex.setOnClickListener(v->openDetailedMap("yandex"));
        LinearLayout.LayoutParams yp=new LinearLayout.LayoutParams(0,dp(43),1);
        yp.setMargins(0,0,dp(10),0);
        mapOptions.addView(yandex,yp);
        Button twogis=new Button(this);
        twogis.setText("2ГИС ↗");
        twogis.setAllCaps(false);
        twogis.setTextSize(12);
        twogis.setTextColor(NAVY);
        twogis.setBackground(surface(Color.rgb(217,244,230),12));
        twogis.setOnClickListener(v->openDetailedMap("2gis"));
        mapOptions.addView(twogis,new LinearLayout.LayoutParams(0,dp(43),1));
        bottom.addView(mapOptions);
        HorizontalScrollView scroller=new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        stopItems=new LinearLayout(this);
        stopItems.setOrientation(LinearLayout.HORIZONTAL);
        scroller.addView(stopItems);
        bottom.addView(scroller,new LinearLayout.LayoutParams(-1,dp(49)));
        TextView credit=txt("© OpenStreetMap contributors · OpenFreeMap. Карта требует интернета.",10,Color.rgb(115,137,137),false);
        credit.setPadding(0,dp(9),0,0);
        bottom.addView(credit);
        root.addView(bottom);

        setContentView(root);
        mapView.onCreate(state);
        mapView.getMapAsync(readyMap -> {
            map=readyMap;
            map.setStyle(STYLE, style -> {
                styleReady=true;
                preferRussianLabels(style);
                enableBuildingNumbers(style);
                style.addSource(new GeoJsonSource("trip-route",FeatureCollection.fromFeatures(new Feature[]{})));
                style.addLayer(new LineLayer("trip-route-layer","trip-route").withProperties(
                    lineColor(MINT),lineWidth(5.5f)));
                style.addSource(new GeoJsonSource("trip-stops",FeatureCollection.fromFeatures(new Feature[]{})));
                style.addLayer(new CircleLayer("trip-stops-layer","trip-stops").withProperties(
                    circleColor(Color.rgb(245,149,83)),circleRadius(8f),
                    circleStrokeColor(Color.WHITE),circleStrokeWidth(2.5f)));
                style.addSource(new GeoJsonSource("trip-last",FeatureCollection.fromFeatures(new Feature[]{})));
                style.addLayer(new CircleLayer("trip-last-layer","trip-last").withProperties(
                    circleColor(Color.rgb(28,111,225)),circleRadius(9f),
                    circleStrokeColor(Color.WHITE),circleStrokeWidth(3f)));
                showRoute(true);
            });
        });
        handler.postDelayed(tick,15000L);
    }

    /** Use original OSM local-language names rather than English/Latin labels. */
    private void preferRussianLabels(Style style){
        try{
            for(Layer layer:style.getLayers()){
                if(!(layer instanceof SymbolLayer))continue;
                String id=layer.getId().toLowerCase(Locale.ROOT);
                if(id.contains("housenumber")||id.contains("shield")||id.contains("ref")||id.startsWith("trip-"))continue;
                if(id.contains("road")||id.contains("street")||id.contains("place")||
                   id.contains("town")||id.contains("city")||id.contains("village")||
                   id.contains("country")||id.contains("poi")||id.contains("label")||
                   id.contains("transportation")){
                    ((SymbolLayer)layer).setProperties(textField(Expression.coalesce(
                        Expression.get("name"),Expression.get("name:nonlatin"),
                        Expression.get("name:latin"))));
                }
            }
        }catch(Exception ignored){}
    }
    /** Display OSM house numbers when supplied by the vector map tiles. */
    private void enableBuildingNumbers(Style style){
        try{
            if(style.getSource("openmaptiles")==null)return;
            SymbolLayer numbers=new SymbolLayer("avtodnevnik-house-numbers","openmaptiles")
                .withSourceLayer("housenumber")
                .withProperties(
                    textField(Expression.get("housenumber")),
                    textSize(12f),
                    textColor(Color.rgb(54,66,73)),
                    textHaloColor(Color.WHITE),
                    textHaloWidth(1.5f)
                );
            numbers.setMinZoom(17.0f);
            style.addLayer(numbers);
        }catch(Exception ignored){}
    }
    private void openDetailedMap(String provider){
        if(!Double.isFinite(selectedLat)||!Double.isFinite(selectedLon)){
            Toast.makeText(this,"GPS-точка пока не получена",Toast.LENGTH_LONG).show();
            return;
        }
        String url=provider.equals("2gis")
            ?String.format(Locale.US,"https://2gis.ru/geo/%.7f,%.7f",selectedLon,selectedLat)
            :String.format(Locale.US,"https://yandex.ru/maps/?ll=%.7f,%.7f&z=18",selectedLon,selectedLat);
        try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}
        catch(Exception e){Toast.makeText(this,"Не удалось открыть карту",Toast.LENGTH_LONG).show();}
    }

    private FeatureCollection collection(List<Feature> items){
        return FeatureCollection.fromFeatures(items.toArray(new Feature[0]));
    }
    private void showRoute(boolean fit){
        if(!styleReady || map==null)return;
        try{
            JSONObject data=store.mapSnapshot();
            JSONArray paths=data.optJSONArray("segments");
            JSONArray stopList=data.optJSONArray("stops");
            List<Feature> lines=new ArrayList<>();
            List<Feature> pins=new ArrayList<>();
            List<LatLng> seen=new ArrayList<>();
            int count=0;

            if(paths!=null)for(int s=0;s<paths.length();s++){
                JSONArray segment=paths.optJSONArray(s);
                if(segment==null)continue;
                List<Point> list=new ArrayList<>();
                for(int i=0;i<segment.length();i++){
                    JSONArray p=segment.optJSONArray(i);
                    if(p==null)continue;
                    double lat=p.optDouble(0), lon=p.optDouble(1);
                    if(Math.abs(lat)>90 || Math.abs(lon)>180)continue;
                    list.add(Point.fromLngLat(lon,lat));
                    seen.add(new LatLng(lat,lon));count++;
                }
                if(list.size()>1)lines.add(Feature.fromGeometry(LineString.fromLngLats(list)));
            }
            if(stopList!=null)for(int i=0;i<stopList.length();i++){
                JSONObject p=stopList.optJSONObject(i);
                if(p==null || p.optBoolean("excluded"))continue;
                double lat=p.optDouble("lat"),lon=p.optDouble("lon");
                if(Math.abs(lat)>90 || Math.abs(lon)>180)continue;
                pins.add(Feature.fromGeometry(Point.fromLngLat(lon,lat)));
            }
            Style style=map.getStyle();
            if(style==null)return;
            GeoJsonSource linesSource=(GeoJsonSource)style.getSource("trip-route");
            GeoJsonSource stopsSource=(GeoJsonSource)style.getSource("trip-stops");
            GeoJsonSource lastSource=(GeoJsonSource)style.getSource("trip-last");
            if(linesSource!=null)linesSource.setGeoJson(collection(lines));
            if(stopsSource!=null)stopsSource.setGeoJson(collection(pins));
            List<Feature> last=new ArrayList<>();
            if(!seen.isEmpty()){
                LatLng ll=seen.get(seen.size()-1);
                if(!Double.isFinite(selectedLat)){
                    selectedLat=ll.getLatitude();selectedLon=ll.getLongitude();
                }
                last.add(Feature.fromGeometry(Point.fromLngLat(ll.getLongitude(),ll.getLatitude())));
            }
            if(lastSource!=null)lastSource.setGeoJson(collection(last));
            subtitle.setText(count>0?"GPS-точек: "+count+" · Стоянок: "+pins.size():"Ожидание первой GPS-точки");

            stopItems.removeAllViews();
            if(stopList==null || stopList.length()==0){
                TextView none=txt("Стоянок пока нет",13,Color.rgb(125,141,142),false);
                none.setGravity(Gravity.CENTER_VERTICAL);stopItems.addView(none);
            }else{
                int number=1;
                for(int i=0;i<stopList.length();i++){
                    JSONObject p=stopList.optJSONObject(i);
                    if(p==null||p.optBoolean("excluded"))continue;
                    final double lat=p.optDouble("lat"),lon=p.optDouble("lon");
                    String name=p.optString("note","");
                    if(name.isEmpty())name="Стоянка №"+number;
                    String address=p.optString("address","");
                    if(!address.isEmpty())name+=" · "+address;
                    final String label=name;
                    Button b=new Button(this);
                    b.setAllCaps(false);
                    b.setText(name+" · "+p.optLong("duration")+" мин");
                    b.setTextSize(12);
                    b.setTextColor(NAVY);
                    b.setBackground(surface(Color.rgb(228,244,238),13));
                    LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-2,dp(42));
                    bp.setMargins(0,0,dp(9),0);
                    stopItems.addView(b,bp);
                    b.setOnClickListener(v->{
                        selectedLat=lat;selectedLon=lon;
                        map.animateCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(lat,lon),18.0),700);
                        subtitle.setText(label);
                        voice.say("Показываю остановку на карте.");
                    });
                    number++;
                }
            }

            if(fit && !cameraSet){
                cameraSet=true;
                if(seen.isEmpty()){
                    // Neutral initial center until the first position is recorded.
                    map.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(50.290,127.535),11));
                }else if(seen.size()==1){
                    map.moveCamera(CameraUpdateFactory.newLatLngZoom(seen.get(0),17.5));
                }else{
                    double minLat=90,maxLat=-90,minLon=180,maxLon=-180;
                    for(LatLng p:seen){
                        minLat=Math.min(minLat,p.getLatitude());maxLat=Math.max(maxLat,p.getLatitude());
                        minLon=Math.min(minLon,p.getLongitude());maxLon=Math.max(maxLon,p.getLongitude());
                    }
                    if(maxLat-minLat<0.0001 && maxLon-minLon<0.0001){
                        map.moveCamera(CameraUpdateFactory.newLatLngZoom(seen.get(seen.size()-1),17.5));
                    }else{
                        LatLngBounds box=new LatLngBounds.Builder()
                            .include(new LatLng(minLat,minLon))
                            .include(new LatLng(maxLat,maxLon)).build();
                        mapView.post(()->{
                            if(map!=null)map.animateCamera(CameraUpdateFactory.newLatLngBounds(box,dp(45)),650);
                        });
                    }
                }
            }
        }catch(Exception e){
            subtitle.setText("Ошибка данных маршрута");
        }
    }
    @Override protected void onStart(){super.onStart();if(mapView!=null)mapView.onStart();}
    @Override protected void onResume(){super.onResume();if(mapView!=null)mapView.onResume();}
    @Override protected void onPause(){if(mapView!=null)mapView.onPause();super.onPause();}
    @Override protected void onStop(){if(mapView!=null)mapView.onStop();super.onStop();}
    @Override protected void onDestroy(){handler.removeCallbacks(tick);if(mapView!=null)mapView.onDestroy();if(voice!=null)voice.release();super.onDestroy();}
    @Override public void onLowMemory(){super.onLowMemory();if(mapView!=null)mapView.onLowMemory();}
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);if(mapView!=null)mapView.onSaveInstanceState(out);}
}
