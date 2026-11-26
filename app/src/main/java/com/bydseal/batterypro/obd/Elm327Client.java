package com.bydseal.batterypro.obd;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** อ่านค่า BMS ของ BYD ผ่าน ELM327 (ส่งเฉพาะคำสั่ง AT และ UDS 0x22 = อ่านอย่างเดียว) */
public class Elm327Client {
    private final InputStream in;
    private final OutputStream out;
    private final long timeoutMs;

    public Elm327Client(InputStream in, OutputStream out, long timeoutMs) {
        this.in = in; this.out = out; this.timeoutMs = timeoutMs;
    }

    public void init() throws IOException {
        send("ATZ");
        for (String c : new String[]{"ATE0", "ATL0", "ATH0", "ATSP6", "ATSH7E7"}) {
            String r = send(c);
            if (!r.contains("OK")) throw new IOException("อแดปเตอร์ไม่รับคำสั่ง " + c + ": " + r.trim());
        }
        send("ATCRA7EF"); // กรองเฉพาะคำตอบจาก BMS (อแดปเตอร์บางตัวไม่รองรับ ข้ามได้)
    }

    String send(String cmd) throws IOException {
        while (in.available() > 0) in.read(); // ทิ้งข้อมูลค้าง
        out.write((cmd + "\r").getBytes(StandardCharsets.US_ASCII));
        out.flush();
        StringBuilder sb = new StringBuilder();
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (in.available() > 0) {
                int c = in.read();
                if (c < 0) throw new IOException("การเชื่อมต่อหลุด");
                if (c == '>') return sb.toString();
                sb.append((char) c);
            } else {
                try { Thread.sleep(5); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("หยุดการอ่าน"); }
            }
        }
        throw new IOException("หมดเวลารอคำตอบ: " + cmd);
    }

    /** คืนค่าแบบ little-endian ขนาด size ไบต์ หรือ null ถ้ารถไม่ตอบค่านี้ */
    Integer read(int did, int size) throws IOException {
        String prefix = String.format(Locale.US, "62%04X", did);
        String reply = send(String.format(Locale.US, "22%04X", did));
        for (String line : reply.split("[\r\n]+")) {
            String h = line.replace(" ", "").toUpperCase(Locale.US);
            if (!h.startsWith(prefix) || !h.matches("[0-9A-F]+") || h.length() < 6 + size * 2) continue;
            int v = 0;
            for (int i = 0; i < size; i++)
                v |= Integer.parseInt(h.substring(6 + i * 2, 8 + i * 2), 16) << (8 * i);
            return v;
        }
        return null;
    }

    /** อ่านทุกค่า 1 รอบ ค่าที่อ่านไม่ได้จะไม่ใส่ใน Map */
    public Map<String, Number> readAll() throws IOException {
        Map<String, Number> m = new LinkedHashMap<>();
        Integer soc = read(0x1FFC, 2), volt = read(0x0008, 2), cur = read(0x0009, 2),
                minMv = read(0x002B, 2), maxMv = read(0x002D, 2),
                tMin = read(0x002F, 1), tMax = read(0x0031, 1);
        if (soc != null && soc <= 10000) m.put("soc", soc / 100.0);
        if (volt != null) m.put("packVolt", volt);
        if (cur != null) m.put("packCurr", (cur - 5000) / 10.0);
        if (volt != null && cur != null) m.put("powerW", Math.round(volt * (cur - 5000) / 10.0));
        if (minMv != null) m.put("minMv", minMv);
        if (maxMv != null) m.put("maxMv", maxMv);
        if (minMv != null && maxMv != null) m.put("diffMv", maxMv - minMv);
        if (tMin != null) m.put("minTemp", tMin - 40);
        if (tMax != null) m.put("maxTemp", tMax - 40);
        return m;
    }

    public static String toJson(Map<String, Number> m) {
        StringBuilder s = new StringBuilder("{");
        for (Map.Entry<String, Number> e : m.entrySet()) {
            if (s.length() > 1) s.append(',');
            s.append('"').append(e.getKey()).append("\":").append(e.getValue());
        }
        return s.append('}').toString();
    }
}
