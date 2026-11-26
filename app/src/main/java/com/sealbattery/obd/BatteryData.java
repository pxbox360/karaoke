package com.sealbattery.obd;

/** ค่าล่าสุดของแบตเตอรี่ พร้อมเวลาที่อ่านได้ */
public final class BatteryData {
    private final double[] values = new double[Metric.values().length];
    private final long[] times = new long[Metric.values().length];

    public void set(Metric m, double v, long now) {
        values[m.ordinal()] = v;
        times[m.ordinal()] = now;
    }

    /** ลบค่าเดิม (ใช้ตอนเปลี่ยนรหัสของเซนเซอร์) */
    public void unset(Metric m) {
        times[m.ordinal()] = 0;
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

    /** ความจุของแบตตอนใหม่โดยประมาณของ Seal Performance: 82.56 kWh / (172 เซลล์ x 3.2 V) */
    public static final double NOMINAL_AH = 150;

    /** ความจุอ้างอิง: ค่าที่ผู้ใช้ตั้ง (ถ้ามากกว่า 0) ไม่งั้นใช้ค่าจากรถ ไม่งั้นใช้ 150 Ah */
    public double referenceAh(double manual) {
        if (manual > 0) return manual;
        Double car = get(Metric.CAPACITY_REF);
        return car != null ? car : NOMINAL_AH;
    }

    /** SOH โดยประมาณ (%) จากความจุปัจจุบันเทียบความจุอ้างอิง */
    public Double estimatedSoh(double manual) {
        Double now = get(Metric.CAPACITY_NOW);
        return now == null ? null : now / referenceAh(manual) * 100;
    }

    public BatteryData copy() {
        BatteryData d = new BatteryData();
        System.arraycopy(values, 0, d.values, 0, values.length);
        System.arraycopy(times, 0, d.times, 0, times.length);
        return d;
    }
}
