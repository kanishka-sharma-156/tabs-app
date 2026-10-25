package com.example.tab_able;

import android.app.Dialog;
import android.location.Address;
import android.location.Geocoder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.example.tab_able.data.Place;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lets the user drop a pin on a map to say where an expense happened.
 *
 * Tap anywhere on the map to move the pin, then confirm. The place name is
 * reverse-geocoded on the device (android.location.Geocoder), which needs no API
 * key — important because the Maps key in this project is restricted, and a
 * Places lookup would silently fail. If geocoding is unavailable the coordinates
 * are used as the name, so the pin still works.
 */
public class LocationPickerDialog extends DialogFragment implements OnMapReadyCallback {

    /** Called with the chosen place, or null when the user clears the location. */
    public interface OnLocationPicked {
        void onPicked(@Nullable Place place);
    }

    private static final String ARG_LAT = "lat";
    private static final String ARG_LNG = "lng";
    private static final String ARG_NAME = "name";

    /** Rough centre of India, used before the user has picked anything. */
    private static final LatLng INDIA_CENTRE = new LatLng(20.5937, 78.9629);

    private GoogleMap map;
    private Marker pin;
    private LatLng chosen;
    private String chosenName = "";

    private TextView tvChosen, tvHint, btnConfirm, btnClear, btnCancel;

    private OnLocationPicked listener;
    private final ExecutorService geocoderExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static LocationPickerDialog newInstance(@Nullable Place existing) {
        LocationPickerDialog dialog = new LocationPickerDialog();
        Bundle args = new Bundle();
        if (existing != null) {
            args.putDouble(ARG_LAT, existing.getLat());
            args.putDouble(ARG_LNG, existing.getLng());
            args.putString(ARG_NAME, existing.getName());
        }
        dialog.setArguments(args);
        return dialog;
    }

    public void setListener(OnLocationPicked listener) {
        this.listener = listener;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Dialog dialog = super.onCreateDialog(savedInstanceState);
        Window window = dialog.getWindow();
        if (window != null) {
            window.requestFeature(Window.FEATURE_NO_TITLE);
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT);
        }
        return dialog;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_location_picker, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        tvChosen = view.findViewById(R.id.tv_picked_place);
        tvHint = view.findViewById(R.id.tv_picker_hint);
        btnConfirm = view.findViewById(R.id.btn_confirm_location);
        btnClear = view.findViewById(R.id.btn_clear_location);
        btnCancel = view.findViewById(R.id.btn_cancel_location);

        Bundle args = getArguments();
        boolean hasExisting = args != null && args.containsKey(ARG_NAME);
        if (hasExisting) {
            double lat = args.getDouble(ARG_LAT, 0);
            double lng = args.getDouble(ARG_LNG, 0);
            chosen = new LatLng(lat, lng);
            chosenName = args.getString(ARG_NAME, "");
            tvChosen.setText(chosenName);
            btnClear.setVisibility(View.VISIBLE);
        } else {
            tvChosen.setText("no location chosen yet");
            btnClear.setVisibility(View.GONE);
        }
        updateConfirmState();

        btnCancel.setOnClickListener(v -> dismiss());

        btnClear.setOnClickListener(v -> {
            if (listener != null) listener.onPicked(null);
            dismiss();
        });

        btnConfirm.setOnClickListener(v -> {
            if (chosen == null) {
                Toast.makeText(getContext(), "Tap the map to choose a spot first",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            String name = chosenName != null && !chosenName.isEmpty()
                    ? chosenName
                    : String.format(Locale.US, "%.4f, %.4f", chosen.latitude, chosen.longitude);
            if (listener != null) {
                listener.onPicked(new Place("", name, chosen.latitude, chosen.longitude));
            }
            dismiss();
        });

        SupportMapFragment mapFragment = (SupportMapFragment)
                getChildFragmentManager().findFragmentById(R.id.picker_map);
        if (mapFragment != null) {
            mapFragment.getMapAsync(this);
        }
    }

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        map = googleMap;

        // Drop a pin wherever the user taps. This is the whole interaction, so it
        // needs no search box, no API key and no network.
        map.setOnMapClickListener(latLng -> selectPoint(latLng, true));

        if (chosen != null) {
            selectPoint(chosen, true);
        } else {
            // Start somewhere sensible. The map's own location dot is enabled when
            // the permission has been granted; otherwise the user simply pans.
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(INDIA_CENTRE, 4f));
            try {
                map.setMyLocationEnabled(true);
            } catch (SecurityException ignored) {
                // Location permission not granted - panning and tapping still work.
            }
        }
    }

    private void selectPoint(LatLng latLng, boolean reverseGeocode) {
        if (map == null) return;
        chosen = latLng;

        if (pin == null) {
            pin = map.addMarker(new MarkerOptions().position(latLng).title("expense location"));
        } else {
            pin.setPosition(latLng);
        }
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 16f));

        chosenName = "";
        tvChosen.setText("locating…");
        updateConfirmState();

        if (reverseGeocode) {
            reverseGeocode(latLng);
        }
    }

    /**
     * Turns coordinates into a readable name using the platform geocoder.
     *
     * Runs off the main thread because Geocoder does blocking network I/O, and is
     * fully defensive: on any failure the coordinates become the label so a place
     * is always usable.
     */
    private void reverseGeocode(final LatLng latLng) {
        geocoderExecutor.execute(() -> {
            String label = null;
            if (Geocoder.isPresent()) {
                try {
                    Geocoder geocoder = new Geocoder(requireContext().getApplicationContext(),
                            Locale.getDefault());
                    List<Address> results = geocoder.getFromLocation(latLng.latitude, latLng.longitude, 1);
                    if (results != null && !results.isEmpty()) {
                        label = describe(results.get(0));
                    }
                } catch (IOException | IllegalArgumentException | SecurityException e) {
                    label = null;
                }
            }
            if (label == null || label.trim().isEmpty()) {
                label = String.format(Locale.US, "%.4f, %.4f", latLng.latitude, latLng.longitude);
            }

            final String finalLabel = label;
            mainHandler.post(() -> {
                // Ignore a stale result if the user already tapped elsewhere.
                if (chosen == null || chosen.latitude != latLng.latitude
                        || chosen.longitude != latLng.longitude) {
                    return;
                }
                chosenName = finalLabel;
                tvChosen.setText(finalLabel);
                if (pin != null) pin.setTitle(finalLabel);
                updateConfirmState();
            });
        });
    }

    /** Builds the friendliest short label an Address can give us. */
    private static String describe(Address address) {
        String name = firstNonEmpty(address.getFeatureName(), address.getThoroughfare(),
                address.getSubLocality(), address.getLocality());
        String area = firstNonEmpty(address.getSubLocality(), address.getLocality(),
                address.getSubAdminArea(), address.getAdminArea());

        if (name != null && area != null && !name.equalsIgnoreCase(area)) {
            return name + ", " + area;
        }
        if (name != null) return name;
        if (area != null) return area;
        return firstNonEmpty(address.getAddressLine(0));
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        return null;
    }

    private void updateConfirmState() {
        boolean ready = chosen != null && chosenName != null && !chosenName.isEmpty()
                && !"locating…".equals(tvChosen.getText().toString());
        btnConfirm.setEnabled(ready);
        btnConfirm.setAlpha(ready ? 1f : 0.5f);
        tvHint.setText(ready
                ? "tap the map to adjust, then confirm"
                : "tap anywhere on the map to place the pin");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        geocoderExecutor.shutdownNow();
    }
}
