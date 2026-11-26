package com.sealbattery.obd;

/** ค่าล่าสุดของแบตเตอรี่ พร้อมเวลาที่อ่านได้ */
public final class BatteryData {
    private final double[] values = new double[Metric.values().length];
    private final long[] times = new long[Metric.values().length];

    public void set(Metric m, double v, long now) {
        values[m.ordinal()] = v;
        times[m.ordinal()] = now;
    }

    /** คืน null ถ้ายังไม่เคยอ่านได้ */
    public Double get(Metric m) {
        return times[m.ordinal()] == 0 ? null : values[m.ordinal()];
    }

    /** true ถ้าค่าไม่ได้อัปเดตนานเกิน maxAge มิลลิวินาที */
    public boolean isStale(Metric m, long now, long maxAge) {
        return times[m.ordinal()] != 0 && now - times[m.ordinal()] > maxAge;
    }

    /** กำลังไฟ kW (บวก = แบตจ่ายไฟ, ลบ = ชาร์จเข้าแบต) */
    public Double powerKw(boolean invert) {
        Double v = get(Metric.VOLTAGE), a = get(Metric.CURRENT);
        if (v == null || a == null) return null;
        return v * a / 1000.0 * (invert ? -1 : 1);
    }

    public BatteryData copy() {
        BatteryData d = new BatteryData();
        System.arraycopy(values, 0, d.values, 0, values.length);
        System.arraycopy(times, 0, d.times, 0, times.length);
        return d;
    }
}
