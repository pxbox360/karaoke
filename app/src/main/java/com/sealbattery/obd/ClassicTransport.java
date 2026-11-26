package com.sealbattery.obd;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

/** Bluetooth แบบธรรมดา (SPP) ต้องจับคู่ในหน้าตั้งค่าของ Android ก่อน */
@SuppressLint("MissingPermission")
public final class ClassicTransport implements Transport {
    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private final BluetoothAdapter adapter;
    private final BluetoothDevice device;
    private volatile BluetoothSocket socket;
    private volatile boolean closed;
    private InputStream in;
    private OutputStream out;

    public ClassicTransport(BluetoothAdapter adapter, BluetoothDevice device) {
        this.adapter = adapter;
        this.device = device;
    }

    @Override public void connect() throws IOException {
        try { adapter.cancelDiscovery(); } catch (SecurityException ignored) { /* Android 12+ ไม่ได้ให้สิทธิ์สแกน */ }
        try {
            open(device.createRfcommSocketToServiceRecord(SPP));
        } catch (IOException first) {
            if (closed) throw first;
            LogBuffer.add("ลองเชื่อมต่อแบบที่สอง: " + first.getMessage());
            socket.close();
            open(device.createInsecureRfcommSocketToServiceRecord(SPP));
        }
        in = socket.getInputStream();
        out = socket.getOutputStream();
    }

    private void open(BluetoothSocket s) throws IOException {
        socket = s;
        if (closed) {
            s.close();
            throw new IOException("ยกเลิกแล้ว");
        }
        s.connect();
    }

    @Override public void write(byte[] data) throws IOException {
        out.write(data);
        out.flush();
    }

    @Override public int read(byte[] buffer, long timeoutMs) throws IOException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (true) {
            if (closed) throw new IOException("ปิดการเชื่อมต่อแล้ว");
            if (in.available() > 0) return in.read(buffer);
            if (System.currentTimeMillis() >= end) return 0;
            try {
                Thread.sleep(8);
            } catch (InterruptedException e) {
                throw new IOException("ถูกขัดจังหวะ");
            }
        }
    }

    @Override public void close() {
        closed = true;
        BluetoothSocket s = socket;
        if (s != null) {
            try { s.close(); } catch (IOException ignored) { /* ปิดอยู่แล้ว */ }
        }
    }
}
