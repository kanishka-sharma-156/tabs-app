package com.example.tab_able.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

public class CustomMapView extends View {

    public interface OnPinSelectedListener {
        void onPinSelected(Pin pin);
    }

    public static class Pin {
        public String id;
        public String name;
        public String emoji;
        public String details;
        public String paidBy;
        public String balanceInfo;
        public float relX; // 0.0 to 1.0
        public float relY; // 0.0 to 1.0

        public Pin(String id, String name, String emoji, String details, String paidBy, String balanceInfo, float relX, float relY) {
            this.id = id;
            this.name = name;
            this.emoji = emoji;
            this.details = details;
            this.paidBy = paidBy;
            this.balanceInfo = balanceInfo;
            this.relX = relX;
            this.relY = relY;
        }
    }

    public static class Route {
        public Pin from;
        public Pin to;
        public String fare;

        public Route(Pin from, Pin to, String fare) {
            this.from = from;
            this.to = to;
            this.fare = fare;
        }
    }

    private Paint gridPaint;
    private Paint linePaint;
    private Paint pinBgPaint;
    private Paint pinSelectedBgPaint;
    private Paint pinBorderPaint;
    private Paint shadowPaint;
    private Paint textPaint;
    private Paint lakePaint;
    private Paint overlayPaint;

    private List<Pin> pins;
    private List<Route> routes;
    private Pin selectedPin;
    private boolean showOverlay = true;

    private OnPinSelectedListener listener;

    public CustomMapView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initPaints();
        initData();
    }

    private void initPaints() {
        gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        gridPaint.setColor(Color.parseColor("#D8D3C5"));
        gridPaint.setStrokeWidth(3f);

        linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        linePaint.setColor(Color.parseColor("#25232B"));
        linePaint.setStrokeWidth(6f);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setPathEffect(new DashPathEffect(new float[]{12f, 12f}, 0));

        pinBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        pinBgPaint.setColor(Color.parseColor("#B8F04E")); // Lime

        pinSelectedBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        pinSelectedBgPaint.setColor(Color.parseColor("#F2A9DD")); // Pink

        pinBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        pinBorderPaint.setColor(Color.parseColor("#25232B"));
        pinBorderPaint.setStyle(Paint.Style.STROKE);
        pinBorderPaint.setStrokeWidth(5f);

        shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadowPaint.setColor(Color.parseColor("#25232B"));

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.parseColor("#25232B"));
        textPaint.setTextSize(36f);
        textPaint.setTextAlign(Paint.Align.CENTER);

        lakePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        lakePaint.setColor(Color.parseColor("#80B2D8ED")); // Translucent blue lake blob

        overlayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        overlayPaint.setColor(Color.parseColor("#40F2C14E"));
        overlayPaint.setStyle(Paint.Style.FILL);
    }

    private void initData() {
        pins = new ArrayList<>();
        Pin p1 = new Pin("rock", "🗿 Rock Garden", "🗿", "Sightseeing + entry ticket · ₹400", "paid by Riya", "You owe Riya ₹100", 0.25f, 0.15f);
        Pin p2 = new Pin("sec8", "☕ Sector 8 Market", "☕", "Coffee & snacks · ₹920", "paid by You", "Riya & Kabir owe you ₹230 each", 0.65f, 0.28f);
        Pin p3 = new Pin("sec17", "🛍️ Sector 17 Plaza", "🛍️", "Shopping + chole bhature · ₹1,480", "paid by Aman", "You owe Aman ₹370", 0.42f, 0.55f);
        Pin p4 = new Pin("sukhna", "🚣 Sukhna Lake", "🚣", "Boating & ice cream · ₹800", "paid by Kabir", "You owe Kabir ₹200", 0.80f, 0.08f);
        Pin p5 = new Pin("elante", "🎬 Elante Mall", "🎬", "Movie & arcade · ₹1,400", "paid by Aman", "You owe Aman ₹350", 0.75f, 0.78f);

        pins.add(p1);
        pins.add(p2);
        pins.add(p3);
        pins.add(p4);
        pins.add(p5);

        selectedPin = p3; // Default Sector 17

        routes = new ArrayList<>();
        routes.add(new Route(p1, p2, "🛺 ₹90"));
        routes.add(new Route(p2, p4, "🛺 ₹60"));
        routes.add(new Route(p2, p3, "🚕 ₹140"));
        routes.add(new Route(p3, p5, "🚕 ₹210"));
    }

    public void setOnPinSelectedListener(OnPinSelectedListener listener) {
        this.listener = listener;
    }

    public void setShowOverlay(boolean showOverlay) {
        this.showOverlay = showOverlay;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();

        // 1. Draw Sector Grid
        int cols = 6;
        int rows = 8;
        float stepX = w / (float) cols;
        float stepY = h / (float) rows;

        for (int i = 1; i < cols; i++) {
            canvas.drawLine(i * stepX, 0, i * stepX, h, gridPaint);
        }
        for (int j = 1; j < rows; j++) {
            canvas.drawLine(0, j * stepY, w, j * stepY, gridPaint);
        }

        // 2. Draw Sukhna Lake blob
        RectF lakeRect = new RectF(w * 0.70f, h * 0.02f, w * 0.98f, h * 0.16f);
        canvas.drawOval(lakeRect, lakePaint);

        // 3. Draw Ground Overlay highlight if active
        if (showOverlay) {
            RectF overlayRect = new RectF(w * 0.30f, h * 0.45f, w * 0.58f, h * 0.68f);
            canvas.drawRoundRect(overlayRect, 16f, 16f, overlayPaint);
        }

        // 4. Draw Connecting Route Lines
        for (Route r : routes) {
            float x1 = r.from.relX * w;
            float y1 = r.from.relY * h;
            float x2 = r.to.relX * w;
            float y2 = r.to.relY * h;

            canvas.drawLine(x1, y1, x2, y2, linePaint);

            // Fare Badge at midpoint
            float mx = (x1 + x2) / 2f;
            float my = (y1 + y2) / 2f;
            drawFareBadge(canvas, mx, my, r.fare);
        }

        // 5. Draw Location Pins
        for (Pin p : pins) {
            float px = p.relX * w;
            float py = p.relY * h;
            drawPin(canvas, px, py, p);
        }
    }

    private void drawFareBadge(Canvas canvas, float x, float y, String text) {
        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(Color.WHITE);

        Paint bBorder = new Paint(Paint.ANTI_ALIAS_FLAG);
        bBorder.setColor(Color.parseColor("#25232B"));
        bBorder.setStyle(Paint.Style.STROKE);
        bBorder.setStrokeWidth(3f);

        Paint badgeText = new Paint(Paint.ANTI_ALIAS_FLAG);
        badgeText.setColor(Color.parseColor("#25232B"));
        badgeText.setTextSize(26f);
        badgeText.setTextAlign(Paint.Align.CENTER);
        badgeText.setFakeBoldText(true);

        RectF bgRect = new RectF(x - 50f, y - 22f, x + 50f, y + 22f);

        // Shadow
        RectF shadowRect = new RectF(bgRect.left + 3f, bgRect.top + 3f, bgRect.right + 3f, bgRect.bottom + 3f);
        canvas.drawRoundRect(shadowRect, 20f, 20f, shadowPaint);

        canvas.drawRoundRect(bgRect, 20f, 20f, bgPaint);
        canvas.drawRoundRect(bgRect, 20f, 20f, bBorder);
        canvas.drawText(text, x, y + 8f, badgeText);
    }

    private void drawPin(Canvas canvas, float x, float y, Pin pin) {
        boolean isSelected = pin.equals(selectedPin);
        float radius = isSelected ? 40f : 34f;

        // Shadow
        canvas.drawCircle(x + 5f, y + 5f, radius, shadowPaint);

        // Pin Circle
        Paint fill = isSelected ? pinSelectedBgPaint : pinBgPaint;
        canvas.drawCircle(x, y, radius, fill);
        canvas.drawCircle(x, y, radius, pinBorderPaint);

        // Emoji Text
        textPaint.setTextSize(isSelected ? 38f : 32f);
        canvas.drawText(pin.emoji, x, y + 12f, textPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            float touchX = event.getX();
            float touchY = event.getY();
            int w = getWidth();
            int h = getHeight();

            for (Pin p : pins) {
                float px = p.relX * w;
                float py = p.relY * h;
                float dist = (float) Math.hypot(touchX - px, touchY - py);
                if (dist < 60f) { // Tap radius
                    selectedPin = p;
                    invalidate();
                    if (listener != null) {
                        listener.onPinSelected(p);
                    }
                    return true;
                }
            }
        }
        return super.onTouchEvent(event);
    }
}
