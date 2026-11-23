plugins { id("com.android.application") }
android {
 namespace = "ru.avtodnevnik"
 compileSdk = 35
 defaultConfig { applicationId = "ru.avtodnevnik"; minSdk = 26; targetSdk = 34; versionCode = 8; versionName = "0.7.0" }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}

// Native MapLibre map: no Google Maps key or Google Play Services required.
dependencies { implementation("org.maplibre.gl:android-sdk:11.8.0") }
