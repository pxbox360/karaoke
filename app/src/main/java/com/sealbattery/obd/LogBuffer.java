package com.sealbattery.obd;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/** เก็บบันทึกการคุยกับอะแดปเตอร์ไว้ใช้หาสาเหตุเวลาอ่านค่าไม่ได้ */
public final class LogBuffer {
    private static final int MAX = 600;
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();

    private LogBuffer() {}

    public static synchronized void add(String s) {
        String t = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        LINES.addLast(t + "  " + s.replace("\r", " ").replace("\n", " ").trim());
        while (LINES.size() > MAX) LINES.removeFirst();
    }

    public static synchronized String dump() {
        StringBuilder b = new StringBuilder();
        for (String s : LINES) b.append(s).append('\n');
        return b.toString();
    }
}
