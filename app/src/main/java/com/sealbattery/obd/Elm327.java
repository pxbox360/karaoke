package com.sealbattery.obd;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** คุยกับชิปแบบ ELM327: ส่งคำสั่ง 1 บรรทัด แล้วรอจนเจอเครื่องหมาย '>' */
public final class Elm327 {
    private final Transport transport;
    private int silentCount;

    public Elm327(Transport transport) {
        this.transport = transport;
    }

    public String command(String cmd, long timeoutMs) throws IOException {
        byte[] buf = new byte[256];
        // ล้างข้อมูลค้างจากคำสั่งก่อนหน้า
        for (int i = 0; i < 20 && transport.read(buf, 0) > 0; i++) { /* ทิ้ง */ }

        LogBuffer.add("> " + cmd);
        transport.write((cmd + "\r").getBytes(StandardCharsets.US_ASCII));

        StringBuilder out = new StringBuilder();
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            int n = transport.read(buf, 50);
            if (n <= 0) continue;
            out.append(new String(buf, 0, n, StandardCharsets.US_ASCII));
            int prompt = out.indexOf(">");
            if (prompt >= 0) {
                silentCount = 0;
                String r = out.substring(0, prompt).replace("\0", "").trim();
                LogBuffer.add("< " + r);
                return r;
            }
        }
        LogBuffer.add("< (หมดเวลา) " + out);
        // เงียบสนิทติดกันหลายครั้ง = การเชื่อมต่อน่าจะหลุด
        if (out.length() == 0 && ++silentCount >= 5) {
            throw new IOException("อะแดปเตอร์ไม่ตอบสนอง");
        }
        return out.toString().trim();
    }

    /** ตั้งค่าอะแดปเตอร์ให้คุยกับกล่อง 7E7 บน CAN 500k */
    public void initialize() throws IOException {
        command("ATZ", 4000);
        String echo = command("ATE0", 2000);
        if (!echo.toUpperCase(java.util.Locale.US).contains("OK")) {
            echo = command("ATE0", 2000);
            if (!echo.toUpperCase(java.util.Locale.US).contains("OK")) {
                throw new IOException("อุปกรณ์นี้ไม่ตอบแบบอะแดปเตอร์ OBD2");
            }
        }
        String[] setup = {"ATL0", "ATS0", "ATH0", "ATSP6", "ATCAF1", "ATAT1", "ATST96", "ATSH7E7", "ATCRA7EF"};
        for (String s : setup) command(s, 2000);
    }
}
