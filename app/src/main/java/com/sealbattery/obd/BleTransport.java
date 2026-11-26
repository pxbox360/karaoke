package com.sealbattery.obd;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Bluetooth Low Energy (BLE) ไม่ต้องจับคู่ก่อน */
@SuppressLint("MissingPermission")
@SuppressWarnings("deprecation")
public final class BleTransport implements Transport {
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    /** บริการที่อะแดปเตอร์ OBD2 แบบ BLE นิยมใช้ (ตัวแรกคือของ Vgate) */
    private static final String[] KNOWN = {"e7810a71", "000018f0", "0000fff0", "0000ffe0"};

    private final Context context;
    private final BluetoothDevice device;
    private final LinkedBlockingQueue<byte[]> inbox = new LinkedBlockingQueue<>();
    private final CountDownLatch ready = new CountDownLatch(1);
    private final Semaphore writeDone = new Semaphore(0);
    private volatile BluetoothGatt gatt;
    private volatile BluetoothGattCharacteristic writer;
    private volatile boolean connected;
    private volatile String error;
    private byte[] leftover;

    public BleTransport(Context context, BluetoothDevice device) {
        this.context = context;
        this.device = device;
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                if (!g.discoverServices()) fail("ค้นหาบริการ BLE ไม่ได้");
            } else {
                fail("BLE หลุด (รหัส " + status + ")");
            }
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            List<BluetoothGattService> ordered = new ArrayList<>();
            for (String prefix : KNOWN) {
                for (BluetoothGattService s : g.getServices()) {
                    if (s.getUuid().toString().startsWith(prefix)) ordered.add(s);
                }
            }
            for (BluetoothGattService s : g.getServices()) {
                String id = s.getUuid().toString();
                if (!ordered.contains(s) && !id.startsWith("00001800") && !id.startsWith("00001801")) {
                    ordered.add(s);
                }
            }
            for (BluetoothGattService s : ordered) {
                BluetoothGattCharacteristic notify = null, write = null;
                for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                    int p = c.getProperties();
                    if (notify == null && (p & (BluetoothGattCharacteristic.PROPERTY_NOTIFY
                            | BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0) notify = c;
                    if (write == null && (p & (BluetoothGattCharacteristic.PROPERTY_WRITE
                            | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0) write = c;
                }
                if (notify == null || write == null) continue;
                BluetoothGattDescriptor d = notify.getDescriptor(CCCD);
                if (d == null || !g.setCharacteristicNotification(notify, true)) continue;
                writer = write;
                if ((write.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) == 0) {
                    write.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                }
                LogBuffer.add("ใช้บริการ BLE " + s.getUuid());
                boolean canNotify = (notify.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0;
                d.setValue(canNotify ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
                if (!g.writeDescriptor(d)) fail("เปิดรับข้อมูล BLE ไม่ได้");
                return;
            }
            fail("ไม่พบช่องรับส่งข้อมูลของอะแดปเตอร์");
        }

        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("เปิดรับข้อมูล BLE ไม่สำเร็จ (" + status + ")");
                return;
            }
            connected = true;
            ready.countDown();
        }

        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            writeDone.release();
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            byte[] v = c.getValue();
            if (v != null && v.length > 0) inbox.offer(v.clone());
        }
    };

    private void fail(String why) {
        error = why;
        connected = false;
        ready.countDown();
        writeDone.release();
    }

    @Override public void connect() throws IOException {
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
        if (gatt == null) throw new IOException("เปิดการเชื่อมต่อ BLE ไม่ได้");
        try {
            if (!ready.await(15, TimeUnit.SECONDS)) throw new IOException("เชื่อมต่อ BLE หมดเวลา");
        } catch (InterruptedException e) {
            throw new IOException("ถูกขัดจังหวะ");
        }
        if (!connected) throw new IOException(error != null ? error : "เชื่อมต่อ BLE ไม่ได้");
    }

    @Override public void write(byte[] data) throws IOException {
        BluetoothGatt g = gatt;
        if (!connected || g == null) throw new IOException(error != null ? error : "BLE ไม่ได้เชื่อมต่อ");
        for (int i = 0; i < data.length; i += 20) {
            byte[] part = java.util.Arrays.copyOfRange(data, i, Math.min(data.length, i + 20));
            writeDone.drainPermits();
            writer.setValue(part);
            if (!g.writeCharacteristic(writer)) throw new IOException("ส่งข้อมูล BLE ไม่ได้");
            try {
                if (!writeDone.tryAcquire(2, TimeUnit.SECONDS)) throw new IOException("ส่งข้อมูล BLE หมดเวลา");
            } catch (InterruptedException e) {
                throw new IOException("ถูกขัดจังหวะ");
            }
        }
    }

    @Override public int read(byte[] buffer, long timeoutMs) throws IOException {
        if (!connected) throw new IOException(error != null ? error : "BLE ไม่ได้เชื่อมต่อ");
        byte[] chunk = leftover;
        leftover = null;
        if (chunk == null) {
            try {
                chunk = inbox.poll(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                throw new IOException("ถูกขัดจังหวะ");
            }
        }
        if (chunk == null) return 0;
        int n = Math.min(buffer.length, chunk.length);
        System.arraycopy(chunk, 0, buffer, 0, n);
        if (n < chunk.length) leftover = java.util.Arrays.copyOfRange(chunk, n, chunk.length);
        return n;
    }

    @Override public synchronized void close() {
        connected = false;
        ready.countDown();
        writeDone.release();
        BluetoothGatt g = gatt;
        gatt = null;
        if (g != null) {
            g.disconnect();
            g.close();
        }
    }
}
