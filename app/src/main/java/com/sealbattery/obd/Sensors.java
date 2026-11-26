package com.sealbattery.obd;

import java.util.Locale;

/** ที่อยู่ (กล่องควบคุม + รหัส) ของแต่ละค่า สร้างให้เสร็จก่อนส่งให้เธรดอ่านค่า */
public final class Sensors {
    private final int[] headers = new int[Metric.values().length];
    private final int[] dids = new int[Metric.values().length];

    public Sensors() {
        for (Metric m : Metric.values()) set(m, m.defaultHeader(), m.pid);
    }

    /** ที่อยู่สมมติสำหรับโหมดทดลองเท่านั้น ไม่ใช่รหัสของรถจริง */
    public static Sensors demo() {
        Sensors s = new Sensors();
        s.set(Metric.COOLANT, 0x7E0, 0xFF01);
        s.set(Metric.MOTOR_REAR, 0x7E2, 0xFF02);
        return s;
    }

    public void set(Metric m, int header, int did) {
        headers[m.ordinal()] = header;
        dids[m.ordinal()] = did;
    }

    public boolean has(Metric m) {
        return dids[m.ordinal()] >= 0;
    }

    public int header(Metric m) {
        return headers[m.ordinal()];
    }

    public int did(Metric m) {
        return dids[m.ordinal()];
    }

    /** รูปแบบสำหรับบันทึก เช่น 7E3:000F */
    public String encode(Metric m) {
        return has(m) ? String.format(Locale.US, "%03X:%04X", header(m), did(m)) : "";
    }

    /** อ่านค่าที่บันทึกไว้ ถ้ารูปแบบผิดจะคงค่าเดิม */
    public void decode(Metric m, String text) {
        if (text == null || !text.matches("7E[0-7]:[0-9A-F]{4}")) return;
        set(m, Integer.parseInt(text.substring(0, 3), 16), Integer.parseInt(text.substring(4), 16));
    }
}
