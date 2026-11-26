package com.sealbattery.obd;

import java.io.IOException;

/** เธรดเบื้องหลัง: เชื่อมต่อ ตั้งค่า แล้ววนอ่านค่าจากรถ (อ่านอย่างเดียว ไม่สั่งงานรถ) */
public final class Poller extends Thread {
    public interface Factory { Transport create(); }

    public interface Listener {
        void onStatus(Poller source, String text, int level);
        void onData(Poller source, BatteryData data);
    }

    public static final int WORKING = 0, OK = 1, ERROR = 2, FAILED = 3;
    private static final int MAX_RETRY = 4;

    private final Factory factory;
    private final Listener listener;
    private final BatteryData data = new BatteryData();
    private volatile boolean running = true;
    private volatile Transport transport;

    public Poller(Factory factory, Listener listener) {
        this.factory = factory;
        this.listener = listener;
    }

    public void shutdown() {
        running = false;
        Transport t = transport;
        if (t != null) t.close();
        interrupt();
    }

    @Override
    public void run() {
        int failures = 0;
        while (running) {
            try {
                listener.onStatus(this, "กำลังเชื่อมต่ออะแดปเตอร์…", WORKING);
                transport = factory.create();
                if (!running) break;
                transport.connect();
                Elm327 elm = new Elm327(transport);
                listener.onStatus(this, "กำลังตั้งค่าอะแดปเตอร์…", WORKING);
                elm.initialize();
                failures = 0;
                poll(elm);
            } catch (IOException | RuntimeException e) {
                if (!running) break;
                LogBuffer.add("ผิดพลาด: " + e);
                if (++failures > MAX_RETRY) {
                    listener.onStatus(this, "เชื่อมต่อไม่สำเร็จ กดปุ่มเชื่อมต่อเพื่อลองใหม่", FAILED);
                    break;
                }
                listener.onStatus(this, "หลุดการเชื่อมต่อ กำลังลองใหม่ (" + failures + "/" + MAX_RETRY + ")", ERROR);
                pause(2500);
            } finally {
                Transport t = transport;
                if (t != null) t.close();
            }
        }
    }

    private void poll(Elm327 elm) throws IOException {
        Metric[] all = Metric.values();
        int slowCount = 0;
        for (Metric m : all) if (!m.fast) slowCount++;
        Metric[] slow = new Metric[slowCount];
        int i = 0;
        for (Metric m : all) if (!m.fast) slow[i++] = m;

        int next = 0, emptyRounds = 0;
        // รอบแรกอ่านครบทุกค่า
        for (Metric m : all) {
            if (!running) return;
            read(elm, m);
        }
        while (running) {
            boolean any = false;
            for (Metric m : all) {
                if (m.fast && running) any |= read(elm, m);
            }
            // ค่าที่เปลี่ยนช้า อ่านรอบละ 2 ค่า
            for (int k = 0; k < 2 && running; k++) {
                any |= read(elm, slow[next]);
                next = (next + 1) % slow.length;
            }
            emptyRounds = any ? 0 : emptyRounds + 1;
            if (emptyRounds >= 3) {
                listener.onStatus(this, "ต่ออะแดปเตอร์ได้ แต่รถไม่ตอบ ตรวจว่าเปิดระบบรถแล้ว (ดูปุ่มบันทึก)", ERROR);
                pause(2000);
            }
            pause(300);
        }
    }

    private boolean read(Elm327 elm, Metric m) throws IOException {
        Double v = m.decode(elm.command(m.command(), 1500));
        if (v != null) {
            data.set(m, v, System.currentTimeMillis());
            if (running) listener.onStatus(this, "เชื่อมต่อแล้ว กำลังอ่านค่า", OK);
        }
        if (running) listener.onData(this, data.copy());
        return v != null;
    }

    private void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            // ถูกปลุกเพื่อหยุดการทำงาน
        }
    }
}
