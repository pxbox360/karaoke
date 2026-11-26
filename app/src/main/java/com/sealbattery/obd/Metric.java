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
    TEMP_AVG     (0x0032, 1, 1,        -40,  -39,   120, false),
    // ค่าด้านล่างเปลี่ยนที่อยู่ได้จากในแอพ (ดู Sensors.java)
    // มอเตอร์หน้า: มีผู้ใช้ Seal รายงานไว้รายเดียว | น้ำหล่อเย็นและมอเตอร์หลัง: ยังไม่พบรหัส (-1)
    COOLANT      (-1,     1, 1,        -40,  -39,   150, false),
    MOTOR_FRONT  (0x000F, 1, 1,        -40,  -39,   200, false),
    MOTOR_REAR   (-1,     1, 1,        -40,  -39,   200, false),
    // ความจุตามที่ BMS คาลิเบรตไว้ (Ah) อยู่ที่ไบต์ข้อมูลที่ 3-4 ใช้คำนวณ SOH
    // อ้างอิงจากข้อมูลของ BYD Atto 3 และ e2 ยังไม่ยืนยันกับ Seal
    CAPACITY_NOW (0x1FFC, 2, 0.01,       0,   50,   220, false, 2),
    CAPACITY_REF (0x1FFE, 2, 0.01,       0,   50,   220, false, 2),
    // SOH ที่อ่านตรงจากรถ: ยังไม่พบรหัสที่ยืนยัน (-1) เลือกได้จากหน้าค้นหาเซนเซอร์
    SOH_DIRECT   (-1,     1, 1,          0,   50,   110, false);

    /** กล่องแบตเตอรี่ */
    public static final int BMS = 0x7E7;

    public final int pid;
    public final int minBytes;
    public final double scale, offset, min, max;
    public final boolean fast;
    /** ข้ามไบต์ข้อมูลช่วงต้นกี่ไบต์ */
    public final int start;

    Metric(int pid, int minBytes, double scale, double offset, double min, double max, boolean fast) {
        this(pid, minBytes, scale, offset, min, max, fast, 0);
    }

    Metric(int pid, int minBytes, double scale, double offset, double min, double max, boolean fast, int start) {
        this.start = start;
        this.pid = pid;
        this.minBytes = minBytes;
        this.scale = scale;
        this.offset = offset;
        this.min = min;
        this.max = max;
        this.fast = fast;
    }

    /** กล่องควบคุมเริ่มต้นของค่านี้ */
    public int defaultHeader() {
        return this == MOTOR_FRONT ? 0x7E3 : BMS;
    }

    public boolean configurable() {
        return this == COOLANT || this == MOTOR_FRONT || this == MOTOR_REAR || this == SOH_DIRECT;
    }

    public static String command(int did) {
        return String.format(java.util.Locale.US, "22%04X", did);
    }

    /** ดึงเฉพาะไบต์ข้อมูล (เลขฐานสิบหก) จากคำตอบของรหัส did คืน null ถ้าไม่ใช่คำตอบที่สำเร็จ */
    public static String payload(String response, int did) {
        if (response == null) return null;
        String head = String.format(java.util.Locale.US, "62%04X", did);
        for (String line : response.split("[\\r\\n]+")) {
            String hex = line.replace(" ", "").trim().toUpperCase(java.util.Locale.US);
            if (hex.startsWith(head) && hex.matches("[0-9A-F]+")) return hex.substring(head.length());
        }
        return null;
    }

    /** แปลงคำตอบจากอะแดปเตอร์เป็นค่าจริง คืน null ถ้าอ่านไม่ได้หรือค่าอยู่นอกช่วง */
    public Double decode(String response) {
        return decode(response, pid);
    }

    public Double decode(String response, int did) {
        String data = payload(response, did);
        if (data == null) return null;
        int available = data.length() / 2 - start;
        if (available < minBytes) return null;
        // อ่านสูงสุด 2 ไบต์ (ตัวนับรอบชาร์จอาจส่งมา 1 หรือ 2 ไบต์) ค่าอื่นอ่านตามจำนวนที่กำหนด
        int n = this == CHARGE_COUNT ? Math.min(2, available) : minBytes;
        int raw = 0;
        for (int i = 0; i < n; i++) {
            raw |= Integer.parseInt(data.substring((start + i) * 2, (start + i) * 2 + 2), 16) << (8 * i);
        }
        double value = raw * scale + offset;
        return value < min - 1e-9 || value > max + 1e-9 ? null : value;
    }
}
