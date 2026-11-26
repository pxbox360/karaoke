package com.sealbattery.obd;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ค้นหาเซนเซอร์: อ่านรหัส 0001-0060 จากกล่อง 7E0-7E7 (ใช้คำสั่งอ่าน 0x22 เท่านั้น)
 * เก็บเฉพาะคำตอบ 1 ไบต์ เพราะอุณหภูมิของ BYD ที่รู้จักใช้ 1 ไบต์ (ค่า - 40)
 */
public final class Scanner {
    public static final int FIRST = 0x0001, LAST = 0x0060;
    private static final int[] PROBES = {0x000F, 0x0005, 0x0008, 0x0001};

    public static final class Hit {
        public final int header, did, raw;

        public Hit(int header, int did, int raw) {
            this.header = header;
            this.did = did;
            this.raw = raw;
        }

        public int celsius() { return raw - 40; }

        public String key() {
            return String.format(Locale.US, "%03X:%04X", header, did);
        }
    }

    public interface Progress {
        /** คืน false เพื่อหยุดค้นหา */
        boolean update(int percent);
    }

    private Scanner() {}

    public static List<Hit> scan(Elm327 elm, Progress progress) throws IOException {
        List<Hit> hits = new ArrayList<>();
        int perEcu = LAST - FIRST + 1;
        for (int e = 0; e < 8; e++) {
            int header = 0x7E0 + e;
            if (!progress.update(e * 100 / 8)) return hits;
            if (!elm.select(header) || !present(elm)) continue;
            for (int did = FIRST; did <= LAST; did++) {
                if (!progress.update((e * perEcu + did - FIRST) * 100 / (8 * perEcu))) return hits;
                if (knownBatteryValue(header, did)) continue;
                String data = Metric.payload(elm.command(Metric.command(did), 1500), did);
                if (data == null || data.length() != 2) continue;
                int raw = Integer.parseInt(data, 16);
                // ตัดค่าว่าง (00 / FF) และค่าที่เกินช่วงอุณหภูมิ
                if (raw > 0 && raw <= 240) hits.add(new Hit(header, did, raw));
            }
        }
        return hits;
    }

    private static boolean knownBatteryValue(int header, int did) {
        for (Metric m : Metric.values()) {
            if (!m.configurable() && header == Metric.BMS && m.pid == did) return true;
        }
        return false;
    }

    /** กล่องมีอยู่ถ้าตอบกลับ ไม่ว่าจะตอบค่าหรือปฏิเสธ */
    private static boolean present(Elm327 elm) throws IOException {
        for (int did : PROBES) {
            String r = elm.command(Metric.command(did), 1500);
            if (Metric.payload(r, did) != null || r.replace(" ", "").contains("7F22")) return true;
        }
        return false;
    }

    public static String save(List<Hit> hits) {
        StringBuilder b = new StringBuilder();
        for (Hit h : hits) b.append(h.key()).append(':').append(h.raw).append(';');
        return b.toString();
    }

    public static List<Hit> load(String text) {
        List<Hit> hits = new ArrayList<>();
        for (String item : text.split(";")) {
            String[] p = item.split(":");
            if (p.length != 3) continue;
            try {
                hits.add(new Hit(Integer.parseInt(p[0], 16), Integer.parseInt(p[1], 16), Integer.parseInt(p[2])));
            } catch (NumberFormatException ignored) {
                // ข้ามรายการที่เสีย
            }
        }
        return hits;
    }
}
