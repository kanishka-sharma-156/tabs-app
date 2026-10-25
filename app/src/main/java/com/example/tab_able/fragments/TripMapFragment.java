package com.example.tab_able.fragments;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.tab_able.R;
import com.example.tab_able.data.Place;
import com.example.tab_able.data.Space;
import com.example.tab_able.data.SpaceExpense;
import com.example.tab_able.data.TabRepository;
import com.example.tab_able.logic.Money;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.Dash;
import com.google.android.gms.maps.model.Gap;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.PatternItem;
import com.google.android.gms.maps.model.PolylineOptions;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Map of where the money went.
 *
 * Every expense that has a location becomes a pin, and expenses at the same spot
 * are combined into one pin showing the TOTAL spent there - which is the useful
 * number. A toggle switches between your own spending and the whole group's.
 *
 * Expenses saved without a location simply do not appear here; the map is for
 * places, not for totals.
 */
public class TripMapFragment extends Fragment implements OnMapReadyCallback {

    /** Whose spending the pins show. */
    private enum Scope { MINE, GROUP }

    /** Broad fallback view, used only until there is something to frame. */
    private static final LatLng INDIA_CENTRE = new LatLng(20.5937, 78.9629);

    private GoogleMap mMap;
    private Scope scope = Scope.MINE;
    private int selectedDayIndex = -1; // -1 = all days
    /** True once the camera has been positioned, so refreshes stop moving it. */
    private boolean cameraPlaced = false;

    private LinearLayout layoutDaysFilter, cardPinInfo, cardTransitSummary;
    private TextView tvPlaceName, tvPlaceDetails, tvPaidBy, tvBalancePill, btnClosePopup;
    private TextView btnScopeMine, btnScopeGroup, tvMapSummary;
    private TextView tvTransitTotal, tvTransitSplit;
    /** Total across the pins currently drawn, recomputed on every refresh. */
    private long visibleTotalPaise = 0;

    private final List<SpaceExpense> allExpenses = new ArrayList<>();

    /** Grouped spending per place, rebuilt on every refresh. */
    private final Map<LatLng, PlaceSummary> summaries = new LinkedHashMap<>();
    /** marker id -> the place it represents. */
    private final Map<String, PlaceSummary> markerLookup = new HashMap<>();

    /** One place with everything spent there. */
    private static class PlaceSummary {
        final String name;
        final LatLng position;
        long totalPaise;
        int count;
        final Map<String, Long> perPayer = new LinkedHashMap<>();
        final List<SpaceExpense> expenses = new ArrayList<>();
        boolean hasTransit;

        PlaceSummary(String name, LatLng position) {
            this.name = name;
            this.position = position;
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_trip_map, container, false);

        layoutDaysFilter = view.findViewById(R.id.layout_days_filter);        cardPinInfo = view.findViewById(R.id.card_pin_info);

        tvPlaceName = view.findViewById(R.id.tv_place_name);
        tvPlaceDetails = view.findViewById(R.id.tv_place_details);
        tvPaidBy = view.findViewById(R.id.tv_paid_by);
        tvBalancePill = view.findViewById(R.id.tv_balance_pill);
        btnClosePopup = view.findViewById(R.id.btn_close_popup);
        tvMapSummary = view.findViewById(R.id.tv_map_summary);

        cardTransitSummary = view.findViewById(R.id.card_transit_summary);
        tvTransitTotal = view.findViewById(R.id.tv_transit_total);
        tvTransitSplit = view.findViewById(R.id.tv_transit_split);

        btnScopeMine = view.findViewById(R.id.btn_scope_mine);
        btnScopeGroup = view.findViewById(R.id.btn_scope_group);

        btnClosePopup.setOnClickListener(v -> cardPinInfo.setVisibility(View.GONE));

        btnScopeMine.setOnClickListener(v -> setScope(Scope.MINE));
        btnScopeGroup.setOnClickListener(v -> setScope(Scope.GROUP));

        SupportMapFragment mapFragment = (SupportMapFragment)
                getChildFragmentManager().findFragmentById(R.id.map);
        if (mapFragment != null) {
            mapFragment.getMapAsync(this);
        }

        loadExpenses();
        return view;
    }

    private void setScope(Scope newScope) {
        if (scope == newScope) return;
        scope = newScope;

        boolean mine = scope == Scope.MINE;
        btnScopeMine.setBackgroundResource(mine ? R.drawable.bg_pill_black : R.drawable.bg_pill_outlined);
        btnScopeMine.setTextColor(getResources().getColor(mine ? R.color.white : R.color.ink));
        btnScopeGroup.setBackgroundResource(mine ? R.drawable.bg_pill_outlined : R.drawable.bg_pill_black);
        btnScopeGroup.setTextColor(getResources().getColor(mine ? R.color.ink : R.color.white));

        cardPinInfo.setVisibility(View.GONE);
        refreshMapMarkers();
    }

    /** Loads every expense the user can see, across all their spaces. */
    private void loadExpenses() {
        allExpenses.clear();
        FirebaseFirestore firestore = FirebaseFirestore.getInstance();
        List<Space> spaces = TabRepository.getInstance().getSpaces();

        if (spaces.isEmpty()) {
            buildDayChipsAndRefreshMap();
            return;
        }

        final int[] pending = {spaces.size()};
        for (Space sp : spaces) {
            firestore.collection("spaces").document(sp.getId()).collection("expenses").get()
                    .addOnCompleteListener(task -> {
                        if (task.isSuccessful() && task.getResult() != null) {
                            synchronized (allExpenses) {
                                for (DocumentSnapshot doc : task.getResult().getDocuments()) {
                                    SpaceExpense exp = doc.toObject(SpaceExpense.class);
                                    if (exp != null) allExpenses.add(exp);
                                }
                            }
                        }
                        if (--pending[0] <= 0) buildDayChipsAndRefreshMap();
                    });
        }
    }

    // ------------------------------------------------------------------ filters

    private void buildDayChipsAndRefreshMap() {
        if (layoutDaysFilter == null) return;
        layoutDaysFilter.removeAllViews();

        layoutDaysFilter.addView(createFilterChip("all days", -1));

        // Only days that actually have a located expense are worth offering.
        Map<Integer, String> dayLabels = new LinkedHashMap<>();
        for (SpaceExpense exp : allExpenses) {
            if (!hasLocation(exp)) continue;
            int day = exp.getDayIndex() > 0 ? exp.getDayIndex() : 1;
            if (!dayLabels.containsKey(day)) dayLabels.put(day, formatDayLabel(exp));
        }

        for (Map.Entry<Integer, String> entry : dayLabels.entrySet()) {
            layoutDaysFilter.addView(createFilterChip(entry.getValue(), entry.getKey()));
        }

        refreshMapMarkers();
    }

    private String formatDayLabel(SpaceExpense exp) {
        long when = exp.getOccurredAt();
        if (when <= 0) return "day " + (exp.getDayIndex() > 0 ? exp.getDayIndex() : 1);
        return new SimpleDateFormat("d MMM", Locale.getDefault()).format(new Date(when));
    }

    private TextView createFilterChip(String label, int dayIndex) {
        TextView chip = new TextView(getContext());
        boolean selected = selectedDayIndex == dayIndex;
        chip.setText(label);
        chip.setTextSize(12f);
        chip.setTypeface(null, Typeface.BOLD);
        chip.setPadding(32, 16, 32, 16);
        chip.setBackgroundResource(selected ? R.drawable.bg_pill_black : R.drawable.bg_pill_outlined);
        chip.setTextColor(getResources().getColor(selected ? R.color.white : R.color.ink));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 12, 0);
        chip.setLayoutParams(lp);

        chip.setOnClickListener(v -> {
            selectedDayIndex = dayIndex;
            cardPinInfo.setVisibility(View.GONE);
            buildDayChipsAndRefreshMap();
        });

        return chip;
    }

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        mMap = googleMap;
        // The toggle buttons are styled for "you spent" by default, so make the
        // initial state match what the pins show.
        setScope(Scope.MINE);
        refreshMapMarkers();
    }

    // -------------------------------------------------------------- map drawing

    private boolean hasLocation(SpaceExpense exp) {
        Place place = exp.getPlace();
        if (place == null) return false;
        boolean hasName = place.getName() != null && !place.getName().trim().isEmpty();
        boolean hasCoords = place.getLat() != 0.0 || place.getLng() != 0.0;
        return hasName && hasCoords;
    }

    private void refreshMapMarkers() {
        if (mMap == null) return;
        mMap.clear();
        markerLookup.clear();
        summaries.clear();

        String myUid = TabRepository.getInstance().getCurrentUserId();

        // 1. Group the matching expenses by place.
        for (SpaceExpense exp : allExpenses) {
            if (!hasLocation(exp)) continue;

            int day = exp.getDayIndex() > 0 ? exp.getDayIndex() : 1;
            if (selectedDayIndex != -1 && selectedDayIndex != day) continue;

            // In "you spent" mode, only count expenses you actually paid for.
            if (scope == Scope.MINE && !myUid.equals(exp.getPaidByUid())) continue;

            Place place = exp.getPlace();
            LatLng position = new LatLng(place.getLat(), place.getLng());

            PlaceSummary summary = summaries.get(position);
            if (summary == null) {
                summary = new PlaceSummary(place.getName(), position);
                summaries.put(position, summary);
            }

            summary.totalPaise += exp.getAmountPaise();
            summary.count++;
            summary.expenses.add(exp);
            if (exp.isTransit()) summary.hasTransit = true;

            Long previous = summary.perPayer.get(exp.getPaidByUid());
            summary.perPayer.put(exp.getPaidByUid(),
                    (previous != null ? previous : 0L) + exp.getAmountPaise());
        }

        // 2. Drop one pin per place, labelled with the total spent there.
        LatLngBounds.Builder bounds = null;
        visibleTotalPaise = 0;
        for (PlaceSummary summary : summaries.values()) {
            visibleTotalPaise += summary.totalPaise;

            String emoji = summary.hasTransit ? "🛺" : "📍";
            String title = summary.name + " · " + Money.formatInr(summary.totalPaise);

            Marker marker = mMap.addMarker(new MarkerOptions()
                    .position(summary.position)
                    .title(title)
                    .snippet(summary.count + (summary.count == 1 ? " expense" : " expenses"))
                    .icon(BitmapDescriptorFactory.fromBitmap(
                            createPinBitmap(requireContext(), emoji))));

            if (marker != null) {
                markerLookup.put(marker.getId(), summary);
                if (bounds == null) bounds = LatLngBounds.builder();
                bounds.include(summary.position);
            }
        }

        updateMapSummary();
        updateTransitSummary();

        // 3. Frame everything that is on screen.
        if (bounds != null) {
            try {
                mMap.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 120));
            } catch (IllegalStateException e) {
                // Layout not measured yet - fall back to the first pin.
                LatLng first = summaries.keySet().iterator().next();
                mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(first, 13f));
            }
        } else if (!cameraPlaced) {
            // Nothing to show yet (for example the user has not picked a location on
            // any expense). Frame a broad view once, so the map is not left blank
            // with no context, but do not yank the camera on every later refresh.
            cameraPlaced = true;
            mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(INDIA_CENTRE, 4f));
        }

        drawTransitRoutes(myUid);

        mMap.setOnMarkerClickListener(marker -> {
            PlaceSummary summary = markerLookup.get(marker.getId());
            if (summary != null) showSummary(summary);
            return true;  // we render our own card, so suppress the default bubble
        });
    }

    /**
     * Summarises what the current filters are showing, so the header always
     * matches the pins rather than describing a hardcoded trip.
     */
    private void updateMapSummary() {
        if (tvMapSummary == null) return;

        if (summaries.isEmpty()) {
            int located = 0;
            for (SpaceExpense exp : allExpenses) {
                if (hasLocation(exp)) located++;
            }
            if (allExpenses.isEmpty()) {
                tvMapSummary.setText("no expenses yet - add one with a location");
            } else if (located == 0) {
                tvMapSummary.setText("no expense has a location yet - set one when adding");
            } else {
                tvMapSummary.setText("nothing here for this filter");
            }
            return;
        }

        String whose = scope == Scope.MINE ? "you spent" : "everyone spent";
        tvMapSummary.setText(summaries.size() + (summaries.size() == 1 ? " place" : " places")
                + " · " + whose + " " + Money.formatInr(visibleTotalPaise));
    }

    /**
     * Real transit figures for the current filters.
     *
     * This replaced a card with hardcoded text ("₹500", "split 4 ways") that never
     * changed no matter what was actually spent. It now hides itself when there is
     * no transit spending to report.
     */
    private void updateTransitSummary() {
        if (cardTransitSummary == null) return;

        String myUid = TabRepository.getInstance().getCurrentUserId();
        long transitPaise = 0;
        int transitCount = 0;
        java.util.Set<String> payers = new java.util.HashSet<>();

        for (SpaceExpense exp : allExpenses) {
            if (!exp.isTransit()) continue;

            int day = exp.getDayIndex() > 0 ? exp.getDayIndex() : 1;
            if (selectedDayIndex != -1 && selectedDayIndex != day) continue;
            if (scope == Scope.MINE && !myUid.equals(exp.getPaidByUid())) continue;

            transitPaise += exp.getAmountPaise();
            transitCount++;
            payers.add(exp.getPaidByUid());
        }

        if (transitCount == 0) {
            cardTransitSummary.setVisibility(View.GONE);
            return;
        }

        cardTransitSummary.setVisibility(View.VISIBLE);
        tvTransitTotal.setText("transit total: " + Money.formatInr(transitPaise));

        int ways = Math.max(payers.size(), 1);
        if (ways == 1) {
            tvTransitSplit.setText("across " + transitCount
                    + (transitCount == 1 ? " ride" : " rides")
                    + " · paid by " + TabRepository.getInstance().nameFor(payers.iterator().next()));
        } else {
            tvTransitSplit.setText("across " + transitCount
                    + (transitCount == 1 ? " ride" : " rides")
                    + " · " + ways + " people paid, "
                    + Money.formatInr(transitPaise / ways) + " each on average");
        }
    }

    /** Dashed lines between the transit stops, in the currently selected scope. */
    private void drawTransitRoutes(String myUid) {
        List<LatLng> transitCoords = new ArrayList<>();
        for (PlaceSummary summary : summaries.values()) {
            if (summary.hasTransit) transitCoords.add(summary.position);
        }
        if (transitCoords.size() < 2) return;

        List<PatternItem> dashed = Arrays.asList(new Dash(35f), new Gap(25f));
        for (int i = 0; i < transitCoords.size() - 1; i++) {
            mMap.addPolyline(new PolylineOptions()
                    .add(transitCoords.get(i), transitCoords.get(i + 1))
                    .color(Color.parseColor("#25232B"))
                    .width(8f)
                    .pattern(dashed));
        }
    }

    private void showSummary(PlaceSummary summary) {
        cardPinInfo.setVisibility(View.VISIBLE);

        tvPlaceName.setText(summary.name);
        tvPlaceDetails.setText(summary.count + (summary.count == 1 ? " expense" : " expenses")
                + " here · " + Money.formatInr(summary.totalPaise)
                + (scope == Scope.MINE ? " (yours)" : " (everyone)"));

        // Who paid what, by name rather than by uid.
        StringBuilder payers = new StringBuilder();
        for (Map.Entry<String, Long> entry : summary.perPayer.entrySet()) {
            if (payers.length() > 0) payers.append("\n");
            payers.append(TabRepository.getInstance().nameFor(entry.getKey()))
                    .append(" paid ").append(Money.formatInr(entry.getValue()));
        }
        tvPaidBy.setText(payers.length() == 0 ? "no payments recorded" : payers.toString());

        tvBalancePill.setText(scope == Scope.MINE
                ? "you spent " + Money.formatInr(summary.totalPaise)
                : "group spent " + Money.formatInr(summary.totalPaise));
    }

    /** The chunky round pin used across the app's map. */
    private Bitmap createPinBitmap(Context context, String emoji) {
        int width = 130;
        int height = 150;
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadowPaint.setColor(Color.parseColor("#25232B"));

        Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillPaint.setColor(Color.parseColor("#B8F04E"));

        Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        borderPaint.setColor(Color.parseColor("#25232B"));
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(6f);

        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setTextSize(52f);
        textPaint.setTextAlign(Paint.Align.CENTER);

        canvas.drawCircle(width / 2f + 6f, height / 2f + 2f, 48f, shadowPaint);
        canvas.drawCircle(width / 2f, height / 2f - 4f, 48f, fillPaint);
        canvas.drawCircle(width / 2f, height / 2f - 4f, 48f, borderPaint);
        canvas.drawText(emoji, width / 2f, height / 2f + 14f, textPaint);

        return bitmap;
    }

    @Override
    public void onResume() {
        super.onResume();
        // An expense may have just been added with a new location.
        loadExpenses();
    }
}
