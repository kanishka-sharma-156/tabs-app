package com.example.tab_able;

import android.app.Application;
import android.util.Log;

import com.google.android.gms.maps.MapsInitializer;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreSettings;
import com.google.firebase.firestore.MemoryCacheSettings;
import com.google.firebase.firestore.PersistentCacheSettings;

public class TabApp extends Application {

    private static final String TAG = "TabApp";

    @Override
    public void onCreate() {
        super.onCreate();

        MapsInitializer.initialize(this, MapsInitializer.Renderer.LEGACY, renderer -> {});

        configureFirestore();
    }

    /**
     * Turns on Firestore's local disk cache.
     *
     * Why this matters: without it, a write made with no connection (or a very
     * slow one) can be dropped, and the app then looks like it is "not saving
     * anything". With a persistent cache, every write is applied locally first,
     * survives app restarts, and is replayed to the server automatically once
     * the device is online.
     *
     * Must run before any other Firestore call. Everything here is wrapped so a
     * configuration problem can never stop the app from starting.
     */
    private void configureFirestore() {
        try {
            FirebaseFirestoreSettings settings = new FirebaseFirestoreSettings.Builder()
                    .setLocalCacheSettings(
                            PersistentCacheSettings.newBuilder()
                                    .setSizeBytes(100L * 1024 * 1024)
                                    .build())
                    .build();

            FirebaseFirestore.getInstance().setFirestoreSettings(settings);
            Log.i(TAG, "Firestore persistent cache enabled");
        } catch (Exception e) {
            // Fall back to the in-memory cache so the app still runs.
            Log.w(TAG, "Persistent Firestore cache unavailable, using memory cache", e);
            try {
                FirebaseFirestoreSettings fallback = new FirebaseFirestoreSettings.Builder()
                        .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build())
                        .build();
                FirebaseFirestore.getInstance().setFirestoreSettings(fallback);
            } catch (Exception ignored) {
                Log.e(TAG, "Could not configure Firestore at all", ignored);
            }
        }
    }
}
