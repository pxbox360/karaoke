package com.sealbattery.obd;

/**
 * รหัสข้อมูล (UDS service 0x22) ของกล่อง 7E7 รถ BYD
 * ที่มา: ข้อมูลที่ชุมชนแชร์ (ไม่ใช่เอกสารจาก BYD) หากค่าไหนเพี้ยนให้แก้ที่ไฟล์นี้ไฟล์เดียว
 * ข้อมูลหลายไบต์เรียงแบบ little-endian (ไบต์ต่ำมาก่อน)
 */
public enum Metric {
    //            PID   ไบต์ขั้นต่ำ  คูณ    บวก     ต่ำสุด  สูงสุด  อ่านถี่
    SOC          (0x1FFC, 2, 0.01,       0,    0,   105, true),
    VOLTAGE      (0x0008, 2, 1,          0,  200,  1000, true),
    CURRENT      (0x0009, 2, 0.1,     -500, -600,  1000, true),
    CHARGE_COUNT (0x000B, 1, 1,          0,    0, 65534, false),
    KWH_CHARGED  (0x0011, 2, 1,          0,    0, 65534, false),
    KWH_DISCHARGED(0x0012, 2, 1,         0,    0, 65534, false),
    CELL_MIN_NO  (0x002A, 1, 1,          1,    1,   255, false),
    CELL_MIN_V   (0x002B, 2, 0.001,      0,  1.5,   4.5, false),
    CELL_MAX_NO  (0x002C, 1, 1,          1,    1,   255, false),
    CELL_MAX_V   (0x002D, 2, 0.001,      0,  1.5,   4.5, false),
    TEMP_MIN     (0x002F, 1, 1,        -40,  -39,   120, false),
    TEMP_MAX     (0x0031, 1, 1,        -40,  -39,   120, false),
    TEMP_AVG     (0x0032, 1, 1,        -40,  -39,   120, false);

    public final int pid;
    public final int minBytes;
    public final double scale, offset, min, max;
    public final boolean fast;

    Metric(int pid, int minBytes, double scale, double offset, double min, double max, boolean fast) {
        this.pid = pid;
        this.minBytes = minBytes;
        this.scale = scale;
        this.offset = offset;
        this.min = min;
        this.max = max;
        this.fast = fast;
    }

    public String command() {
        return String.format(java.util.Locale.US, "22%04X", pid);
    }

    /** แปลงคำตอบจากอะแดปเตอร์เป็นค่าจริง คืน null ถ้าอ่านไม่ได้หรือค่าอยู่นอกช่วง */
    public Double decode(String response) {
        if (response == null) return null;
        String head = String.format(java.util.Locale.US, "62%04X", pid);
        for (String line : response.split("[\\r\\n]+")) {
            String hex = line.replace(" ", "").trim().toUpperCase(java.util.Locale.US);
            if (!hex.startsWith(head) || !hex.matches("[0-9A-F]+")) continue;
            String data = hex.substring(head.length());
            int available = data.length() / 2;
            if (available < minBytes) continue;
            // อ่านสูงสุด 2 ไบต์ (ตัวนับรอบชาร์จอาจส่งมา 1 หรือ 2 ไบต์) ค่าอื่นอ่านตามจำนวนที่กำหนด
            int n = this == CHARGE_COUNT ? Math.min(2, available) : minBytes;
            int raw = 0;
            for (int i = 0; i < n; i++) {
                raw |= Integer.parseInt(data.substring(i * 2, i * 2 + 2), 16) << (8 * i);
            }
            double value = raw * scale + offset;
            if (value < min - 1e-9 || value > max + 1e-9) return null;
            return value;
        }
        return null;
    }
}
