package com.sealbattery.obd;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

import java.util.Locale;

/** แดชบอร์ดวาดเอง ปรับตามจอแนวตั้ง (มือถือ) และแนวนอน (จอรถ) */
public final class DashboardView extends View {
    private static final int BG = 0xFF0B1020, CARD = 0xFF151D31, TRACK = 0xFF26314B;
    private static final int TEXT = 0xFFF1F5F9, MUTED = 0xFF8E9AB3;
    private static final int CYAN = 0xFF22D3EE, GREEN = 0xFF34D399, AMBER = 0xFFFBBF24, RED = 0xFFF87171;
    private static final long STALE_MS = 15000;
    private static final float PORTRAIT_W = 400, PORTRAIT_H = 1082;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Path path = new Path();
    private final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
    private final Typeface bold = Typeface.create("sans-serif-medium", Typeface.BOLD);

    private BatteryData data = new BatteryData();
    private final float[] history = new float[90];
    private int historyCount;
    private long lastHistory;
    private float capacityKwh = 82.5f, consumption = 18f;
    private boolean invertCurrent;

    public DashboardView(Context context) {
        super(context);
    }

    public void setSettings(float capacityKwh, float consumption, boolean invertCurrent) {
        if (invertCurrent != this.invertCurrent) historyCount = 0;
        this.capacityKwh = capacityKwh;
        this.consumption = consumption;
        this.invertCurrent = invertCurrent;
        invalidate();
    }

    public void clear() {
        data = new BatteryData();
        historyCount = 0;
        invalidate();
    }

    public void setData(BatteryData d) {
        data = d;
        Double kw = d.powerKw(invertCurrent);
        long now = System.currentTimeMillis();
        if (kw != null && now - lastHistory >= 1000) {
            lastHistory = now;
            if (historyCount == history.length) {
                System.arraycopy(history, 1, history, 0, history.length - 1);
                historyCount--;
            }
            history[historyCount++] = kw.floatValue();
        }
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int h = MeasureSpec.getSize(heightSpec);
        // อยู่ใน ScrollView (แนวตั้ง) ความสูงจะไม่ถูกกำหนด ให้คำนวณจากความกว้าง
        if (MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED) {
            h = Math.round(w / PORTRAIT_W * PORTRAIT_H);
        }
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(BG);
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        c.save();
        if (w > h) {
            float s = Math.min(w / 960f, h / 480f);
            c.scale(s, s);
            float vw = w / s, vh = h / s, g = 12;
            float colW = (vw - g * 4) / 3f;
            float x1 = g, x2 = x1 + colW + g, x3 = x2 + colW + g;
            float top = g, bottom = vh - g, inner = bottom - top - g;
            socCard(c, x1, top, colW, bottom - top);
            float powerH = inner * 0.56f;
            powerCard(c, x2, top, colW, powerH);
            lifeCard(c, x2, top + powerH + g, colW, inner - powerH);
            float cellH = inner * 0.56f;
            cellCard(c, x3, top, colW, cellH);
            tempCard(c, x3, top + cellH + g, colW, inner - cellH);
        } else {
            float s = w / PORTRAIT_W;
            c.scale(s, s);
            float x = 12, cw = PORTRAIT_W - 24, y = 12;
            socCard(c, x, y, cw, 372);          y += 384;
            powerCard(c, x, y, cw, 236);        y += 248;
            cellCard(c, x, y, cw, 188);         y += 200;
            tempCard(c, x, y, cw, 118);         y += 130;
            lifeCard(c, x, y, cw, 96);
        }
        c.restore();
    }

    // ---------- การ์ดแต่ละใบ ----------

    private void socCard(Canvas c, float x, float y, float w, float h) {
        card(c, x, y, w, h, "ระดับแบตเตอรี่ (SOC)");
        Double soc = data.get(Metric.SOC);
        float chipH = 62;
        float areaTop = y + 40, areaBottom = y + h - chipH - 22;
        float radius = Math.min((w - 56) / 2f, (areaBottom - areaTop) / 1.76f);
        float cx = x + w / 2f;
        float cy = areaTop + radius + Math.max(4, (areaBottom - areaTop - radius * 1.76f) / 2f);
        float stroke = radius * 0.15f;
        r.set(cx - radius, cy - radius, cx + radius, cy + radius);
        int color = soc == null ? MUTED : soc < 15 ? RED : soc < 30 ? AMBER : GREEN;
        arc(c, 135, 270, TRACK, stroke);
        if (soc != null) {
            float sweep = (float) (270 * Math.max(0, Math.min(100, soc)) / 100);
            arc(c, 135, sweep, (color & 0x00FFFFFF) | 0x33000000, stroke * 1.55f);
            arc(c, 135, sweep, color, stroke);
        }
        String big = soc == null ? "--" : fmt("%.1f", soc);
        text(c, big, cx, cy + radius * 0.14f, radius * 0.52f, dim(TEXT, Metric.SOC), bold, Paint.Align.CENTER);
        text(c, "%", cx, cy + radius * 0.46f, radius * 0.2f, MUTED, regular, Paint.Align.CENTER);

        float cw = (w - 36) / 2f, cyTop = y + h - chipH - 12;
        Double energy = soc == null ? null : capacityKwh * soc / 100.0;
        Double range = energy == null || consumption <= 0 ? null : energy / consumption * 100.0;
        chip(c, x + 12, cyTop, cw, chipH, "พลังงานคงเหลือ*",
                energy == null ? "--" : fmt("%.1f", energy), "kWh");
        chip(c, x + 24 + cw, cyTop, cw, chipH, "ระยะทางคาดการณ์*",
                range == null ? "--" : fmt("%.0f", range), "กม.");
    }

    private void powerCard(Canvas c, float x, float y, float w, float h) {
        card(c, x, y, w, h, "กำลังไฟ");
        Double kw = data.powerKw(invertCurrent);
        String state = "รอข้อมูล";
        int color = MUTED;
        if (kw != null) {
            if (kw > 0.8) { state = "จ่ายไฟออก"; color = CYAN; }
            else if (kw < -0.8) { state = "ชาร์จเข้าแบต"; color = GREEN; }
            else { state = "เกือบนิ่ง"; color = MUTED; }
        }
        text(c, state, x + w - 14, y + 25, 13, color, bold, Paint.Align.RIGHT);

        String big = kw == null ? "--" : fmt("%.1f", Math.abs(kw));
        float baseline = y + 76;
        text(c, big, x + 14, baseline, 44, dim(TEXT, Metric.CURRENT), bold, Paint.Align.LEFT);
        p.setTextSize(44);
        p.setTypeface(bold);
        text(c, "kW", x + 20 + p.measureText(big), baseline, 17, MUTED, regular, Paint.Align.LEFT);

        Double volts = data.get(Metric.VOLTAGE), amps = data.get(Metric.CURRENT);
        float half = w / 2f, ly = y + 102;
        text(c, "แรงดันแพ็ก", x + 14, ly, 12, MUTED, regular, Paint.Align.LEFT);
        text(c, volts == null ? "--" : fmt("%.0f V", volts), x + 14, ly + 23, 21,
                dim(TEXT, Metric.VOLTAGE), bold, Paint.Align.LEFT);
        text(c, "กระแส", x + half, ly, 12, MUTED, regular, Paint.Align.LEFT);
        text(c, amps == null ? "--" : fmt("%.1f A", amps * (invertCurrent ? -1 : 1)), x + half, ly + 23, 21,
                dim(TEXT, Metric.CURRENT), bold, Paint.Align.LEFT);

        // กราฟกำลังไฟย้อนหลังประมาณ 90 วินาที เส้นกลางคือ 0 kW
        float gx = x + 14, gw = w - 28, gy = ly + 36, gh = y + h - 12 - gy;
        if (gh < 20) return;
        float mid = gy + gh / 2f;
        line(c, gx, mid, gx + gw, mid, TRACK, 1.5f);
        if (historyCount < 2) return;
        float peak = 20;
        for (int i = 0; i < historyCount; i++) peak = Math.max(peak, Math.abs(history[i]));
        path.reset();
        for (int i = 0; i < historyCount; i++) {
            float px = gx + gw * (history.length - historyCount + i) / (history.length - 1);
            float py = mid - history[i] / peak * (gh / 2f - 2);
            if (i == 0) path.moveTo(px, py); else path.lineTo(px, py);
        }
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2.5f);
        p.setColor(CYAN);
        c.drawPath(path, p);
    }

    private void cellCard(Canvas c, float x, float y, float w, float h) {
        card(c, x, y, w, h, "แรงดันเซลล์");
        Double hi = data.get(Metric.CELL_MAX_V), lo = data.get(Metric.CELL_MIN_V);
        Double hiNo = data.get(Metric.CELL_MAX_NO), loNo = data.get(Metric.CELL_MIN_NO);
        float step = Math.min(52, (h - 90) / 2f);
        float row = y + 62;
        cellRow(c, x, row, w, "สูงสุด", hi, hiNo, AMBER, Metric.CELL_MAX_V);
        cellRow(c, x, row + step, w, "ต่ำสุด", lo, loNo, CYAN, Metric.CELL_MIN_V);

        float by = y + h - 52;
        r.set(x + 12, by, x + w - 12, by + 40);
        fill(c, r, 10, TRACK);
        String diff = "--", verdict = "";
        int color = MUTED;
        if (hi != null && lo != null) {
            long mv = Math.round(Math.max(0, hi - lo) * 1000);
            diff = mv + " mV";
            // เกณฑ์คร่าว ๆ สำหรับดูแนวโน้มเท่านั้น
            if (mv <= 30) { verdict = "สมดุลดี"; color = GREEN; }
            else if (mv <= 80) { verdict = "ควรติดตาม"; color = AMBER; }
            else { verdict = "ต่างกันมาก"; color = RED; }
        }
        text(c, "ส่วนต่าง", x + 24, by + 26, 13, MUTED, regular, Paint.Align.LEFT);
        text(c, diff, x + 88, by + 27, 18, TEXT, bold, Paint.Align.LEFT);
        text(c, verdict, x + w - 24, by + 26, 14, color, bold, Paint.Align.RIGHT);
    }

    private void cellRow(Canvas c, float x, float y, float w, String label, Double v, Double no,
                         int color, Metric m) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        c.drawCircle(x + 20, y - 5, 5, p);
        text(c, label, x + 33, y, 14, TEXT, regular, Paint.Align.LEFT);
        text(c, no == null ? "เซลล์ --" : fmt("เซลล์ %.0f", no), x + 33, y + 17, 11, MUTED, regular, Paint.Align.LEFT);
        text(c, v == null ? "--" : fmt("%.3f V", v), x + w - 14, y + 10, 28, dim(TEXT, m), bold, Paint.Align.RIGHT);
    }

    private void tempCard(Canvas c, float x, float y, float w, float h) {
        card(c, x, y, w, h, "อุณหภูมิแบตเตอรี่");
        float colW = (w - 24) / 3f;
        float ly = y + 36 + (h - 36) / 2f;
        tempColumn(c, x + 12 + colW * 0.5f, ly, "ต่ำสุด", Metric.TEMP_MIN);
        tempColumn(c, x + 12 + colW * 1.5f, ly, "เฉลี่ย", Metric.TEMP_AVG);
        tempColumn(c, x + 12 + colW * 2.5f, ly, "สูงสุด", Metric.TEMP_MAX);
    }

    private void tempColumn(Canvas c, float cx, float cy, String label, Metric m) {
        Double t = data.get(m);
        int color = t == null ? MUTED : t >= 50 ? RED : t >= 42 ? AMBER : t < 10 ? CYAN : GREEN;
        text(c, t == null ? "--" : fmt("%.0f°", t), cx, cy + 6, 32, dim(color, m), bold, Paint.Align.CENTER);
        text(c, label, cx, cy + 28, 12, MUTED, regular, Paint.Align.CENTER);
    }

    private void lifeCard(Canvas c, float x, float y, float w, float h) {
        card(c, x, y, w, h, "สถิติสะสม");
        float colW = (w - 24) / 3f;
        float cy = y + 36 + (h - 36) / 2f;
        lifeColumn(c, x + 12 + colW * 0.5f, cy, "ชาร์จเข้า kWh", Metric.KWH_CHARGED);
        lifeColumn(c, x + 12 + colW * 1.5f, cy, "ใช้ออก kWh", Metric.KWH_DISCHARGED);
        lifeColumn(c, x + 12 + colW * 2.5f, cy, "ครั้งที่ชาร์จ", Metric.CHARGE_COUNT);
    }

    private void lifeColumn(Canvas c, float cx, float cy, String label, Metric m) {
        Double v = data.get(m);
        text(c, v == null ? "--" : fmt("%,.0f", v), cx, cy + 2, 24, dim(TEXT, m), bold, Paint.Align.CENTER);
        text(c, label, cx, cy + 23, 11, MUTED, regular, Paint.Align.CENTER);
    }

    // ---------- ตัวช่วยวาด ----------

    private void card(Canvas c, float x, float y, float w, float h, String title) {
        r.set(x, y, x + w, y + h);
        fill(c, r, 18, CARD);
        text(c, title, x + 14, y + 25, 14, MUTED, bold, Paint.Align.LEFT);
    }

    private void chip(Canvas c, float x, float y, float w, float h, String label, String value, String unit) {
        r.set(x, y, x + w, y + h);
        fill(c, r, 12, TRACK);
        float cx = x + w / 2f;
        text(c, label, cx, y + 20, 11, MUTED, regular, Paint.Align.CENTER);
        text(c, value + " " + unit, cx, y + 47, 20, TEXT, bold, Paint.Align.CENTER);
    }

    private void fill(Canvas c, RectF rect, float radius, int color) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        c.drawRoundRect(rect, radius, radius, p);
    }

    private void arc(Canvas c, float start, float sweep, int color, float width) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(width);
        p.setColor(color);
        c.drawArc(r, start, sweep, false, p);
    }

    private void line(Canvas c, float x1, float y1, float x2, float y2, int color, float width) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(width);
        p.setColor(color);
        c.drawLine(x1, y1, x2, y2, p);
    }

    private void text(Canvas c, String s, float x, float y, float size, int color, Typeface face, Paint.Align align) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        p.setTextSize(size);
        p.setTypeface(face);
        p.setTextAlign(align);
        c.drawText(s, x, y, p);
        p.setTextAlign(Paint.Align.LEFT);
    }

    /** ค่าที่ค้างนานจะแสดงเป็นสีจาง */
    private int dim(int color, Metric m) {
        return data.isStale(m, System.currentTimeMillis(), STALE_MS) ? MUTED : color;
    }

    private static String fmt(String f, double v) {
        return String.format(Locale.US, f, v);
    }
}
