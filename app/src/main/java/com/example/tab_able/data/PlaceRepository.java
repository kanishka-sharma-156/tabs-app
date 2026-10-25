package com.example.tab_able.data;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PlaceRepository {
    private static PlaceRepository instance;
    private final List<Place> cachedPlaces = new ArrayList<>();
    private boolean isLoaded = false;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public interface OnPlacesLoadedListener {
        void onLoaded(List<Place> places);
    }

    private PlaceRepository() {}

    public static synchronized PlaceRepository getInstance() {
        if (instance == null) {
            instance = new PlaceRepository();
        }
        return instance;
    }

    public void loadPlacesAsync(Context context, OnPlacesLoadedListener listener) {
        if (isLoaded) {
            if (listener != null) listener.onLoaded(cachedPlaces);
            return;
        }

        executor.execute(() -> {
            List<Place> loaded = new ArrayList<>();
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(context.getAssets().open("chandigarh_places.geojson")));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();

                JSONObject root = new JSONObject(sb.toString());
                JSONArray features = root.getJSONArray("features");

                for (int i = 0; i < features.length(); i++) {
                    JSONObject feature = features.getJSONObject(i);
                    JSONObject props = feature.optJSONObject("properties");
                    if (props == null) continue;

                    String name = props.optString("name", "");
                    if (name.isEmpty()) continue;

                    JSONObject geom = feature.optJSONObject("geometry");
                    if (geom == null) continue;

                    String type = geom.optString("type", "Point");
                    JSONArray coords = geom.optJSONArray("coordinates");
                    if (coords == null) continue;

                    double lat = 0.0;
                    double lng = 0.0;

                    if ("Point".equalsIgnoreCase(type) && coords.length() >= 2) {
                        lng = coords.getDouble(0);
                        lat = coords.getDouble(1);
                    } else if (coords.length() > 0) {
                        // Centroid approximation for Polygon / LineString
                        double sumLat = 0, sumLng = 0;
                        int count = 0;
                        if ("Polygon".equalsIgnoreCase(type)) {
                            JSONArray ring = coords.optJSONArray(0);
                            if (ring != null) {
                                for (int j = 0; j < ring.length(); j++) {
                                    JSONArray pt = ring.getJSONArray(j);
                                    if (pt.length() >= 2) {
                                        sumLng += pt.getDouble(0);
                                        sumLat += pt.getDouble(1);
                                        count++;
                                    }
                                }
                            }
                        } else {
                            for (int j = 0; j < coords.length(); j++) {
                                JSONArray pt = coords.getJSONArray(j);
                                if (pt.length() >= 2) {
                                    sumLng += pt.getDouble(0);
                                    sumLat += pt.getDouble(1);
                                    count++;
                                }
                            }
                        }
                        if (count > 0) {
                            lng = sumLng / count;
                            lat = sumLat / count;
                        }
                    }

                    if (lat != 0.0 && lng != 0.0) {
                        String placeId = feature.optString("id", "place_" + i);
                        // Check duplicates: same name and nearly identical coordinates (within ~10 meters)
                        boolean isDuplicate = false;
                        for (Place p : loaded) {
                            if (p.getName().equalsIgnoreCase(name) &&
                                    Math.abs(p.getLat() - lat) < 0.0001 &&
                                    Math.abs(p.getLng() - lng) < 0.0001) {
                                isDuplicate = true;
                                break;
                            }
                        }
                        if (!isDuplicate) {
                            loaded.add(new Place(placeId, name, lat, lng));
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

            synchronized (this) {
                cachedPlaces.clear();
                cachedPlaces.addAll(loaded);
                isLoaded = true;
            }

            if (listener != null) {
                new Handler(Looper.getMainLooper()).post(() -> listener.onLoaded(cachedPlaces));
            }
        });
    }

    public List<Place> getAll() {
        synchronized (this) {
            return new ArrayList<>(cachedPlaces);
        }
    }

    public List<Place> search(String query) {
        List<Place> result = new ArrayList<>();
        if (query == null || query.trim().isEmpty()) return getAll();
        String q = query.trim().toLowerCase();
        synchronized (this) {
            for (Place p : cachedPlaces) {
                if (p.getName().toLowerCase().contains(q)) {
                    result.add(p);
                }
            }
        }
        return result;
    }

    public Place nearest(double lat, double lng) {
        Place nearest = null;
        double minDistance = Double.MAX_VALUE;
        synchronized (this) {
            for (Place p : cachedPlaces) {
                double dist = Math.pow(p.getLat() - lat, 2) + Math.pow(p.getLng() - lng, 2);
                if (dist < minDistance) {
                    minDistance = dist;
                    nearest = p;
                }
            }
        }
        return nearest;
    }
}
