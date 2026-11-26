package com.bydseal.batterypro.obd;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.webkit.WebView;
import java.util.Map;
import java.util.UUID;

/** เชื่อมต่อ ELM327 แบบ Bluetooth Classic ที่จับคู่ไว้แล้ว และส่งค่าไปที่หน้าจอ */
public class ObdSession extends Thread {
    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private final WebView web;
    private volatile boolean running = true;
    private volatile BluetoothSocket socket;

    public ObdSession(WebView web) { this.web = web; }

    public void shutdown() { running = false; interrupt(); close(); }

    @Override public void run() {
        while (running) {
            try {
                status("กำลังเชื่อมต่อ OBD2...");
                BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
                if (bt == null || !bt.isEnabled()) throw new Exception("Bluetooth ปิดอยู่");
                BluetoothDevice dev = null;
                for (BluetoothDevice d : bt.getBondedDevices()) {
                    String n = d.getName() == null ? "" : d.getName().toUpperCase();
                    if (n.contains("OBD")) { dev = d; break; }
                }
                if (dev == null) throw new Exception("ไม่พบอแดปเตอร์ OBD ที่จับคู่ไว้");
                socket = dev.createRfcommSocketToServiceRecord(SPP);
                socket.connect();
                Elm327Client elm = new Elm327Client(socket.getInputStream(), socket.getOutputStream(), 5000);
                elm.init();
                while (running) {
                    Map<String, Number> data = elm.readAll();
                    js("updateObd(" + Elm327Client.toJson(data) + ")");
                    Thread.sleep(1000);
                }
            } catch (SecurityException e) {
                status("แอพยังไม่ได้รับสิทธิ์ Bluetooth");
            } catch (Exception e) {
                status(String.valueOf(e.getMessage()));
            } finally {
                close();
            }
            if (running) try { Thread.sleep(5000); } catch (InterruptedException e) { return; }
        }
    }

    private void close() {
        try { if (socket != null) socket.close(); } catch (Exception ignored) { }
    }

    private void status(String s) {
        js("obdStatus('" + s.replace("\\", "").replace("'", "").replace("\n", " ").replace("\r", " ") + "')");
    }

    private void js(String code) {
        web.post(() -> { if (running) web.evaluateJavascript(code, null); });
    }
}
