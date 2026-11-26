package com.sealbattery.obd;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** โหมดทดลอง: จำลองคำตอบของรถ เพื่อดูหน้าจอโดยไม่ต้องต่ออะแดปเตอร์ */
public final class DemoTransport implements Transport {
    private byte[] pending;
    private final long start = System.currentTimeMillis();

    @Override public void connect() {}

    @Override public void write(byte[] data) {
        String cmd = new String(data, StandardCharsets.US_ASCII).trim();
        String reply = "OK";
        if (cmd.startsWith("22")) {
            int pid = Integer.parseInt(cmd.substring(2, 6), 16);
            double t = (System.currentTimeMillis() - start) / 1000.0;
            double amps = 45 + 70 * Math.sin(t / 4.0);
            switch (pid) {
                case 0x1FFC: reply = le(pid, 7640, 2); break;
                case 0x0008: reply = le(pid, (int) Math.round(566 - amps / 20), 2); break;
                case 0x0009: reply = le(pid, (int) Math.round(amps * 10) + 5000, 2); break;
                case 0x000B: reply = le(pid, 214, 2); break;
                case 0x0011: reply = le(pid, 11830, 2); break;
                case 0x0012: reply = le(pid, 11245, 2); break;
                case 0x002A: reply = le(pid, 43, 1); break;
                case 0x002B: reply = le(pid, 3308, 2); break;
                case 0x002C: reply = le(pid, 87, 1); break;
                case 0x002D: reply = le(pid, 3322, 2); break;
                case 0x002F: reply = le(pid, 31 + 40, 1); break;
                case 0x0031: reply = le(pid, 34 + 40, 1); break;
                case 0x0032: reply = le(pid, 32 + 40, 1); break;
                default: reply = "NO DATA";
            }
        }
        pending = (reply + "\r\r>").getBytes(StandardCharsets.US_ASCII);
    }

    private static String le(int pid, int value, int bytes) {
        StringBuilder b = new StringBuilder(String.format(Locale.US, "62%04X", pid));
        for (int i = 0; i < bytes; i++) {
            b.append(String.format(Locale.US, "%02X", (value >> (8 * i)) & 0xFF));
        }
        return b.toString();
    }

    @Override public int read(byte[] buffer, long timeoutMs) {
        if (pending == null) return 0;
        if (timeoutMs > 0) {
            try { Thread.sleep(40); } catch (InterruptedException ignored) { return 0; }
        }
        int n = Math.min(buffer.length, pending.length);
        System.arraycopy(pending, 0, buffer, 0, n);
        pending = null;
        return n;
    }

    @Override public void close() {}
}
