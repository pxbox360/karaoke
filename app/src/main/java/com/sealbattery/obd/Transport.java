package com.sealbattery.obd;

import java.io.IOException;

/** ช่องทางรับส่งข้อมูลกับอะแดปเตอร์ */
public interface Transport {
    void connect() throws IOException;
    void write(byte[] data) throws IOException;
    /** รอข้อมูลไม่เกิน timeoutMs คืนจำนวนไบต์ (0 = ไม่มีข้อมูล) */
    int read(byte[] buffer, long timeoutMs) throws IOException;
    void close();
}
