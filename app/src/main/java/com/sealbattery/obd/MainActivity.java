package com.sealbattery.obd;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@SuppressLint("MissingPermission")
public class MainActivity extends Activity implements Poller.Listener {
    private static final int REQUEST_PERMISSIONS = 1;
    private static final String CLASSIC = "classic", BLE = "ble", DEMO = "demo";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private DashboardView dashboard;
    private LinearLayout content;
    private TextView status;
    private Button connectButton;
    private Poller poller;
    private boolean resumeOnStart;
    private String currentType, currentAddress;
    private BluetoothLeScanner scanner;
    private ScanCallback scanCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0B1020);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(6), dp(8), dp(2));

        TextView title = new TextView(this);
        title.setText("Seal Battery");
        title.setTextSize(18);
        title.setTextColor(0xFFF1F5F9);
        bar.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        bar.addView(button("บันทึก", v -> showLog()));
        bar.addView(button("ตั้งค่า", v -> showSettings()));
        connectButton = button("เชื่อมต่อ", v -> {
            if (poller != null) disconnect("ตัดการเชื่อมต่อแล้ว");
            else chooseDevice();
        });
        bar.addView(connectButton);
        root.addView(bar);

        status = new TextView(this);
        status.setTextSize(13);
        status.setPadding(dp(12), dp(2), dp(12), dp(2));
        root.addView(status);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        root.addView(content, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        dashboard = new DashboardView(this);
        applySettings();
        arrange(getResources().getConfiguration());
        setContentView(root);
        showStatus("กดปุ่ม “เชื่อมต่อ” เพื่อเริ่ม", Poller.WORKING);
    }

    /** แนวตั้งให้เลื่อนได้ แนวนอนแสดงพอดีจอ */
    private void arrange(Configuration config) {
        if (dashboard.getParent() != null) ((android.view.ViewGroup) dashboard.getParent()).removeView(dashboard);
        content.removeAllViews();
        LinearLayout.LayoutParams full = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT);
        if (config.orientation == Configuration.ORIENTATION_PORTRAIT) {
            ScrollView scroll = new ScrollView(this);
            scroll.addView(dashboard, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
            content.addView(scroll, full);
        } else {
            content.addView(dashboard, full);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        arrange(newConfig);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (resumeOnStart && currentType != null) {
            resumeOnStart = false;
            start(currentType, currentAddress);
        }
    }

    @Override
    protected void onStop() {
        // หยุดส่งข้อมูลเมื่อออกจากหน้าแอพ เพื่อไม่ให้รบกวนรถตอนจอดหรือล็อก
        stopScan();
        if (poller != null) {
            disconnect("พักการเชื่อมต่อ");
            resumeOnStart = true;
        }
        super.onStop();
    }

    // ---------- การเชื่อมต่อ ----------

    private BluetoothAdapter adapter() {
        BluetoothManager m = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        return m == null ? null : m.getAdapter();
    }

    private String[] requiredPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            return new String[]{Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN};
        }
        return new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION};
    }

    private boolean granted(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void chooseDevice() {
        for (String permission : requiredPermissions()) {
            if (!granted(permission)) {
                requestPermissions(requiredPermissions(), REQUEST_PERMISSIONS);
                return;
            }
        }
        showDevices();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        if (code != REQUEST_PERMISSIONS) return;
        // Android 11 ลงไป สิทธิ์ตำแหน่งใช้แค่ตอนค้นหา BLE ถึงไม่ให้ก็ยังใช้แบบจับคู่ได้
        if (Build.VERSION.SDK_INT >= 31 && !granted(Manifest.permission.BLUETOOTH_CONNECT)) {
            toast("ยังไม่ได้อนุญาต Bluetooth ใช้ได้เฉพาะโหมดทดลอง");
        }
        showDevices();
    }

    private void showDevices() {
        final List<String[]> entries = new ArrayList<>(); // {ชนิด, ที่อยู่, ชื่อที่แสดง}
        final ArrayAdapter<String> list = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1);
        final Set<String> seenBle = new HashSet<>();
        addEntry(entries, list, DEMO, "", "▶ โหมดทดลอง (ข้อมูลจำลอง)");

        BluetoothAdapter bt = adapter();
        boolean allowed = Build.VERSION.SDK_INT < 31 || granted(Manifest.permission.BLUETOOTH_CONNECT);
        String title = "เลือกอะแดปเตอร์";
        if (bt != null && allowed) {
            if (!bt.isEnabled()) {
                title = "เปิด Bluetooth ก่อน แล้วกดเชื่อมต่อใหม่";
            } else {
                for (BluetoothDevice d : bt.getBondedDevices()) {
                    boolean le = d.getType() == BluetoothDevice.DEVICE_TYPE_LE;
                    addEntry(entries, list, le ? BLE : CLASSIC, d.getAddress(),
                            name(d.getName()) + (le ? "  [BLE]" : "  [จับคู่แล้ว]") + "\n" + d.getAddress());
                }
                startScan(bt, entries, list, seenBle);
            }
        }

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setAdapter(list, (dialog, which) -> {
                    stopScan();
                    String[] e = entries.get(which);
                    start(e[0], e[1]);
                })
                .setNegativeButton("ปิด", null)
                .setOnDismissListener(dialog -> stopScan())
                .show();
    }

    private void addEntry(List<String[]> entries, ArrayAdapter<String> list, String type, String address, String label) {
        entries.add(new String[]{type, address, label});
        list.add(label);
    }

    private static String name(String n) {
        return n == null || n.isEmpty() ? "(ไม่มีชื่อ)" : n;
    }

    private void startScan(BluetoothAdapter bt, List<String[]> entries, ArrayAdapter<String> list, Set<String> seen) {
        stopScan();
        if (Build.VERSION.SDK_INT >= 31 && !granted(Manifest.permission.BLUETOOTH_SCAN)) return;
        scanner = bt.getBluetoothLeScanner();
        if (scanner == null) return;
        final ScanCallback cb = new ScanCallback() {
            @Override public void onScanResult(int callbackType, ScanResult result) {
                ui.post(() -> {
                    if (scanCallback != this) return;
                    BluetoothDevice d = result.getDevice();
                    String n = d.getName();
                    if (n == null && result.getScanRecord() != null) n = result.getScanRecord().getDeviceName();
                    // แสดงเฉพาะอุปกรณ์ที่มีชื่อ
                    if (n == null || !seen.add(d.getAddress())) return;
                    addEntry(entries, list, BLE, d.getAddress(), n + "  [BLE]\n" + d.getAddress());
                });
            }
        };
        scanCallback = cb;
        try {
            scanner.startScan(cb);
        } catch (SecurityException | IllegalStateException e) {
            LogBuffer.add("สแกน BLE ไม่ได้: " + e);
        }
        ui.postDelayed(() -> { if (scanCallback == cb) stopScan(); }, 15000);
    }

    private void stopScan() {
        if (scanner != null && scanCallback != null) {
            try {
                scanner.stopScan(scanCallback);
            } catch (SecurityException | IllegalStateException e) {
                LogBuffer.add("หยุดสแกนไม่ได้: " + e);
            }
        }
        scanCallback = null;
    }

    private void start(String type, String address) {
        if (poller != null) poller.shutdown();
        currentType = type;
        currentAddress = address;
        dashboard.clear();
        Context app = getApplicationContext();
        BluetoothAdapter bt = adapter();
        Poller.Factory factory;
        if (DEMO.equals(type)) {
            factory = DemoTransport::new;
        } else if (bt == null) {
            toast("เครื่องนี้ไม่มี Bluetooth");
            return;
        } else if (BLE.equals(type)) {
            factory = () -> new BleTransport(app, bt.getRemoteDevice(address));
        } else {
            factory = () -> new ClassicTransport(bt, bt.getRemoteDevice(address));
        }
        poller = new Poller(factory, this);
        connectButton.setText("หยุด");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        poller.start();
    }

    private void disconnect(String message) {
        if (poller != null) poller.shutdown();
        poller = null;
        connectButton.setText("เชื่อมต่อ");
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        showStatus(message, Poller.WORKING);
    }

    @Override
    public void onStatus(Poller source, String text, int level) {
        ui.post(() -> {
            if (source != poller) return;
            boolean demo = DEMO.equals(currentType);
            // เลิกพยายามแล้ว คืนปุ่มเป็น "เชื่อมต่อ"
            if (level == Poller.FAILED) disconnect("");
            showStatus(demo && level == Poller.OK ? "โหมดทดลอง (ไม่ใช่ค่าจากรถจริง)" : text,
                    demo && level == Poller.OK ? Poller.WORKING : level);
        });
    }

    @Override
    public void onData(Poller source, BatteryData data) {
        ui.post(() -> {
            if (source == poller) dashboard.setData(data);
        });
    }

    private void showStatus(String text, int level) {
        status.setText("● " + text);
        status.setTextColor(level == Poller.OK ? 0xFF34D399 : level >= Poller.ERROR ? 0xFFF87171 : 0xFFFBBF24);
    }

    // ---------- ตั้งค่าและบันทึก ----------

    private void applySettings() {
        dashboard.setSettings(prefs.getFloat("capacity", 82.5f), prefs.getFloat("consumption", 18f),
                prefs.getBoolean("invert", false));
    }

    private void showSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        EditText capacity = numberField(box, "ความจุแบตเตอรี่ (kWh)", prefs.getFloat("capacity", 82.5f));
        EditText consumption = numberField(box, "อัตราสิ้นเปลืองเฉลี่ย (kWh/100 กม.)", prefs.getFloat("consumption", 18f));
        CheckBox invert = new CheckBox(this);
        invert.setText("กลับเครื่องหมายกระแส (ถ้าตอนชาร์จขึ้นว่าจ่ายไฟออก)");
        invert.setChecked(prefs.getBoolean("invert", false));
        box.addView(invert);
        TextView note = new TextView(this);
        note.setText("* พลังงานคงเหลือและระยะทางเป็นค่าคำนวณจากสองช่องด้านบน ไม่ได้อ่านจากรถ");
        note.setTextSize(12);
        box.addView(note);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);

        new AlertDialog.Builder(this)
                .setTitle("ตั้งค่า")
                .setView(scroll)
                .setPositiveButton("บันทึก", (DialogInterface d, int w) -> {
                    prefs.edit()
                            .putFloat("capacity", parse(capacity, 82.5f, 20, 150))
                            .putFloat("consumption", parse(consumption, 18f, 5, 60))
                            .putBoolean("invert", invert.isChecked())
                            .apply();
                    applySettings();
                })
                .setNegativeButton("ยกเลิก", null)
                .show();
    }

    private EditText numberField(LinearLayout box, String label, float value) {
        TextView t = new TextView(this);
        t.setText(label);
        box.addView(t);
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setText(String.valueOf(value));
        box.addView(e);
        return e;
    }

    private static float parse(EditText e, float fallback, float min, float max) {
        try {
            float v = Float.parseFloat(e.getText().toString().trim());
            return v >= min && v <= max ? v : fallback;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private void showLog() {
        String log = LogBuffer.dump();
        final String body = log.isEmpty() ? "ยังไม่มีบันทึก" : log;
        TextView t = new TextView(this);
        t.setText(body);
        t.setTextSize(11);
        t.setTextIsSelectable(true);
        t.setPadding(dp(16), dp(8), dp(16), dp(8));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(t);
        new AlertDialog.Builder(this)
                .setTitle("บันทึกการสื่อสาร")
                .setView(scroll)
                .setPositiveButton("แชร์", (d, w) -> {
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("text/plain");
                    send.putExtra(Intent.EXTRA_TEXT, body);
                    startActivity(Intent.createChooser(send, "ส่งบันทึก"));
                })
                .setNegativeButton("ปิด", null)
                .show();
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    // ---------- ทั่วไป ----------

    private Button button(String text, View.OnClickListener action) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(0xFFF1F5F9);
        b.setTextSize(14);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(14), 0, dp(14), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF26314B);
        bg.setCornerRadius(dp(10));
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40));
        lp.setMargins(dp(4), 0, dp(4), 0);
        b.setLayoutParams(lp);
        b.setOnClickListener(action);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
